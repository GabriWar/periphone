package dev.gabriwar.usbaudio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One stream to pc/bin/phone-relay: UDP over Wi-Fi / Bluetooth tethering (TCP for the camera, it
 * must not lose data), or RFCOMM for Bluetooth direct. Does the PIN handshake, then carries sealed
 * DATA messages (see Proto). close() may be called from another thread to unblock the pipe.
 */
final class Remote implements Closeable {
    private static final String BT_UUID = "a8f3c5d0-5b1e-4c7a-9e2d-7f10c0de000";   // + role (phone-relay)
    private static final int TOS_VOICE = 0xB8, TOS_VIDEO = 0x88;  // DSCP EF / AF41: Wi-Fi voice / video queue
    private static final long PING_MS = 1000, DEAD_MS = 6000, HANDSHAKE_MS = 4000;
    private static final int MAX_MSG = 4 << 20;

    private final Context ctx;
    private final Cfg.Link link;
    private final int role;
    private volatile boolean closed;
    private volatile DatagramSocket udp;
    private volatile Closeable stream;     // Socket or BluetoothSocket
    private InputStream in;
    private OutputStream out;
    private Proto.Seal tx, rx;
    private boolean discovered;
    private int seqOut;
    private long lastSeq = -1;             // unsigned 32-bit, -1 = nothing yet
    private int gap;
    private long lastHeard, lastPing, lastPoll;
    private byte[] buf = new byte[4096];
    private byte[] sendBuf = new byte[4096];
    private final DatagramPacket pkt = new DatagramPacket(buf, buf.length);

    Remote(Context ctx, Cfg.Link link, int role) {
        this.ctx = ctx;
        this.link = link;
        this.role = role;
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    private IOException fail(int res, Object... args) {
        return new IOException(ctx.getString(res, args));
    }

    // ------------------------------------------------------------------ connect

    void connect() throws IOException {
        if (link.pin.isEmpty()) throw fail(R.string.err_no_pin);
        long wait = Stats.pinRetryAt - now();
        if (wait > 0) throw fail(R.string.err_wrong_pin_wait, (wait + 999) / 1000);
        Proto.Client hs = new Proto.Client(role, link.flags(), link.pin);
        String where;
        if (link.mode == Cfg.LINK_BT) {
            connectBt();
            streamHandshake(hs);
            where = ctx.getString(R.string.via_bt);
        } else {
            InetAddress pc = pcAddress();
            if (role == Proto.ROLE_CAM) {
                connectTcp(pc);
                streamHandshake(hs);
            } else {
                udpHandshake(hs, pc);
            }
            where = ctx.getString(link.mode == Cfg.LINK_PAN ? R.string.via_pan : R.string.via_wifi,
                    pc.getHostAddress());
        }
        tx = new Proto.Seal(hs.sendKey, link.encrypt);
        rx = new Proto.Seal(hs.recvKey, link.encrypt);
        Stats.pinFails = 0;
        lastHeard = lastPing = lastPoll = now();
        Stats.linkWhere = where;
    }

    private InetAddress pcAddress() throws IOException {
        if (!link.host.isEmpty()) {
            try {
                return InetAddress.getByName(link.host);
            } catch (UnknownHostException e) {
                throw fail(R.string.err_bad_host, link.host);
            }
        }
        InetAddress known = Stats.pcAddr;
        if (known != null) return known;
        discovered = true;
        return discover();
    }

    /** Broadcast WHO on every interface (Wi-Fi, Bluetooth tethering...) until phone-relay answers. */
    private InetAddress discover() throws IOException {
        List<InetAddress> targets = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
                }
            }
        } catch (SocketException ignored) {
        }
        targets.add(InetAddress.getByName("255.255.255.255"));
        DatagramSocket s = new DatagramSocket();
        udp = s;
        try {
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] who = {'W'};
            long end = now() + HANDSHAKE_MS;
            while (now() < end && !closed) {
                for (InetAddress t : targets) {
                    try {
                        s.send(new DatagramPacket(who, 1, t, Cfg.NET_PORT));
                    } catch (IOException ignored) {   // that interface can't broadcast
                    }
                }
                try {
                    pkt.setData(buf);
                    s.receive(pkt);
                    if (pkt.getLength() == 1 && buf[0] == 'w') {
                        Stats.pcAddr = pkt.getAddress();
                        return pkt.getAddress();
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } finally {
            s.close();
            udp = null;
        }
        throw fail(R.string.err_no_pc_found);
    }

    private void udpHandshake(Proto.Client hs, InetAddress pc) throws IOException {
        DatagramSocket s = new DatagramSocket();
        udp = s;
        if (closed) throw new SocketException("closed");
        s.setTrafficClass(TOS_VOICE);
        s.connect(pc, Cfg.NET_PORT);
        s.setSoTimeout(300);
        byte[] hello = hs.hello(), confirm = null;
        boolean badProof = false;
        long end = now() + HANDSHAKE_MS;
        while (confirm == null) {
            if (now() > end) {
                if (badProof) throw wrongPin();
                if (discovered) Stats.pcAddr = null;   // maybe the PC moved: look again next time
                throw fail(R.string.err_pc_no_answer, pc.getHostAddress());
            }
            sendRaw(hello, hello.length);
            int n = recvUdp();
            if (n == Proto.REPLY_LEN && buf[0] == 'R' && hs.sameSid(buf, 1)) {
                // a REPLY that doesn't prove the PIN may be forged by someone on the network:
                // keep waiting for the real PC, count it as a mismatch only if none shows up
                confirm = hs.finish(Arrays.copyOf(buf, n));
                if (confirm == null) badProof = true;
            } else if (n >= 10 && buf[0] == 'E' && hs.sameSid(buf, 1)) {
                throw error(buf[9]);
            }
        }
        end = now() + HANDSHAKE_MS;
        while (true) {
            if (now() > end) throw fail(R.string.err_pc_no_answer, pc.getHostAddress());
            sendRaw(confirm, confirm.length);
            int n = recvUdp();
            if (n >= 9 && buf[0] == 'O' && hs.sameSid(buf, 1)) break;
            if (n > 0 && buf[0] == 'D') break;                 // our OK got lost, data is already flowing
            if (n >= 10 && buf[0] == 'E' && hs.sameSid(buf, 1)) throw error(buf[9]);
        }
    }

    private void connectTcp(InetAddress pc) throws IOException {
        Socket s = new Socket();
        stream = s;
        if (closed) throw new SocketException("closed");
        s.setTrafficClass(TOS_VIDEO);
        s.setSendBufferSize(64 * 1024);
        try {
            s.connect(new InetSocketAddress(pc, Cfg.NET_PORT), 3000);
        } catch (IOException e) {
            if (closed) throw e;
            throw fail(R.string.err_relay_down, pc.getHostAddress());
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout((int) HANDSHAKE_MS);
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    @SuppressWarnings("MissingPermission")   // checked by the screen; a SecurityException is reported below
    private void connectBt() throws IOException {
        if (link.btAddr.isEmpty()) throw fail(R.string.err_bt_pick_pc);
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) throw fail(R.string.err_bt_none);
        try {
            if (!ad.isEnabled()) throw fail(R.string.err_bt_off);
            BluetoothSocket s = ad.getRemoteDevice(link.btAddr)
                    .createRfcommSocketToServiceRecord(UUID.fromString(BT_UUID + role));
            stream = s;
            if (closed) throw new SocketException("closed");
            try {
                s.connect();
            } catch (IOException e) {
                if (closed) throw e;
                throw fail(R.string.err_bt_connect);
            }
            in = s.getInputStream();
            out = s.getOutputStream();
        } catch (SecurityException e) {
            throw fail(R.string.err_bt_permission);
        } catch (IllegalArgumentException e) {
            throw fail(R.string.err_bt_pick_pc);
        }
    }

    private void streamHandshake(Proto.Client hs) throws IOException {
        byte[] hello = hs.hello();
        writeMsg(hello, hello.length);
        int n = readMsg();
        if (n >= 10 && buf[0] == 'E') throw error(buf[9]);
        if (n != Proto.REPLY_LEN || buf[0] != 'R' || !hs.sameSid(buf, 1)) throw fail(R.string.err_pc_unsupported);
        byte[] confirm = hs.finish(Arrays.copyOf(buf, n));
        if (confirm == null) throw wrongPin();
        writeMsg(confirm, confirm.length);
        n = readMsg();
        if (n >= 10 && buf[0] == 'E') throw error(buf[9]);
        if (n < 9 || buf[0] != 'O') throw fail(R.string.err_pc_unsupported);
        if (stream instanceof Socket) ((Socket) stream).setSoTimeout(0);
    }

    /**
     * PIN mismatch: back off 3 s, 6 s... up to 60 s before the next try. A fake PC answering
     * discovery then gets ~1 PIN guess a minute, and a spoofed error (they aren't authenticated)
     * only delays us, it can't lock us out. Reset by a success, new settings or Start.
     */
    private IOException wrongPin() {
        int fails = Math.min(++Stats.pinFails, 5);
        Stats.pinRetryAt = now() + Math.min(60_000L, 1_500L << fails);
        if (discovered) Stats.pcAddr = null;   // maybe we found the wrong PC: look again next time
        return fail(R.string.err_wrong_pin);
    }

    private IOException error(int code) {
        switch (code) {
            case Proto.E_PIN: return wrongPin();
            case Proto.E_DOWN: return fail(R.string.err_pc_side_down);
            case Proto.E_BUSY: return fail(R.string.err_pc_busy);
            case Proto.E_UNKNOWN: return fail(R.string.err_pc_restarted);
            default: return fail(R.string.err_pc_unsupported);
        }
    }

    // ------------------------------------------------------------------ messages

    private void sendRaw(byte[] m, int len) throws IOException {
        udp.send(new DatagramPacket(m, 0, len));
    }

    /** One datagram into buf; -1 on timeout. */
    private int recvUdp() throws IOException {
        try {
            pkt.setData(buf);
            udp.receive(pkt);
            return pkt.getLength();
        } catch (SocketTimeoutException e) {
            return -1;
        } catch (PortUnreachableException e) {
            throw fail(R.string.err_relay_down, String.valueOf(udp.getInetAddress()).replace("/", ""));
        }
    }

    private void writeMsg(byte[] m, int len) throws IOException {
        if (sendBuf.length < len + 4) sendBuf = new byte[len + 4 + 1024];
        putLen(sendBuf, len);
        System.arraycopy(m, 0, sendBuf, 4, len);
        out.write(sendBuf, 0, len + 4);
    }

    private static void putLen(byte[] b, int len) {
        b[0] = (byte) (len >>> 24);
        b[1] = (byte) (len >>> 16);
        b[2] = (byte) (len >>> 8);
        b[3] = (byte) len;
    }

    private int readMsg() throws IOException {
        Io.readFully(in, buf, 4);
        int len = ((buf[0] & 0xff) << 24) | ((buf[1] & 0xff) << 16) | ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
        if (len <= 0 || len > MAX_MSG) throw new EOFException("bad message length " + len);
        if (buf.length < len) buf = new byte[len + 1024];
        Io.readFully(in, buf, len);
        return len;
    }

    /** One DATA message with b[off..off+len). */
    void send(byte[] b, int off, int len) throws IOException {
        int need = 4 + Proto.HDR + len + Proto.TAG;
        if (sendBuf.length < need) sendBuf = new byte[need + 64 * 1024];
        if (udp != null) {
            int n = tx.seal(seqOut++, b, off, len, sendBuf, 0);
            sendRaw(sendBuf, n);
            long t = now();
            if (t - lastPoll >= PING_MS) {       // the PC pings every second: is it still there?
                lastPoll = t;
                poll();
                if (t - lastHeard > DEAD_MS) throw fail(R.string.err_pc_gone);
            }
        } else {
            int n = tx.seal(seqOut++, b, off, len, sendBuf, 4);
            putLen(sendBuf, n);
            out.write(sendBuf, 0, n + 4);
        }
    }

    /** Drains what the PC sent on a send-only UDP stream (pings, errors), without blocking. */
    private void poll() throws IOException {
        udp.setSoTimeout(1);
        int n;
        while ((n = recvUdp()) > 0) {
            lastHeard = now();
            if (n >= 10 && buf[0] == 'E' && buf[9] == Proto.E_UNKNOWN) throw fail(R.string.err_pc_restarted);
        }
    }

    /** Next DATA payload into dst; returns its length. gap() = packets lost just before it. */
    int recv(byte[] dst) throws IOException {
        while (true) {
            int n;
            if (udp != null) {
                long t = now();
                if (t - lastPing >= PING_MS) {
                    lastPing = t;
                    sendRaw(new byte[]{'P'}, 1);
                }
                if (t - lastHeard > DEAD_MS) throw fail(R.string.err_pc_gone);
                n = recvUdp();
                if (n <= 0) continue;
            } else {
                n = readMsg();
            }
            lastHeard = now();
            if (buf[0] == 'D' && n >= Proto.HDR) {
                long s = Proto.seq(buf, 0) & 0xffffffffL;
                long d = lastSeq < 0 ? 1 : (s - lastSeq) & 0xffffffffL;
                if (d == 0 || d > 0x80000000L) continue;          // duplicate / late / replayed
                int len = rx.open(buf, 0, n, dst);
                if (len < 0) continue;                             // forged or corrupt
                gap = (int) Math.min(d - 1, 1000);
                lastSeq = s;
                return len;
            }
            if (buf[0] == 'E' && n >= 10 && buf[9] == Proto.E_UNKNOWN) throw fail(R.string.err_pc_restarted);
        }
    }

    int gap() {
        return gap;
    }

    @Override
    public void close() {
        closed = true;
        final DatagramSocket u = udp;
        if (u != null) {
            if (tx != null && u.isConnected()) {
                // tell the PC right away instead of letting it time out; not on the caller's
                // thread, it may be the main thread (no network there)
                new Thread(() -> {
                    try { u.send(new DatagramPacket(new byte[]{'B'}, 1)); } catch (IOException ignored) {}
                    u.close();
                }, "bye").start();
            } else {
                u.close();
            }
        }
        Closeable s = stream;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
