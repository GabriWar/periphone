package dev.gabriwar.usbaudio;

import android.content.Context;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 5 ms PCM chunks to/from the PC over whatever link is set: raw PCM over the USB tunnel straight to
 * PipeWire, or Remote packets (PCM or Opus) to phonebridge. Lost packets are concealed (Opus PLC
 * or silence) so the timing downstream stays right.
 */
final class AudioLink implements Closeable {
    static final int FRAMES = 240;

    private final Context ctx;
    private final Cfg.Link link;
    private final int role, usbPort, channels;
    final int chunk;
    private volatile Socket usb;
    private volatile Remote remote;
    private InputStream in;
    private OutputStream out;
    private long enc, dec;
    private static final byte[] LOST = new byte[0];
    private final byte[] pkt = new byte[4096], red = new byte[4096];
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();   // to play next; LOST = conceal
    private byte[] prev = LOST;              // last chunk sent, repeated in the next packet

    AudioLink(Context ctx, Cfg.Link link, int role, int usbPort, int channels) {
        this.ctx = ctx;
        this.link = link;
        this.role = role;
        this.usbPort = usbPort;
        this.channels = channels;
        this.chunk = FRAMES * channels * 2;
    }

    void open() throws IOException {
        boolean sends = role != Proto.ROLE_SPK;
        if (link.usb()) {
            Socket s = new Socket();
            usb = s;
            // before connect: keeps the kernel queues (= delay) short
            if (sends) s.setSendBufferSize(8 * 1024 * channels);
            else s.setReceiveBufferSize(16 * 1024);
            s.connect(new InetSocketAddress("127.0.0.1", usbPort), 1000);
            s.setTcpNoDelay(true);
            if (sends) Io.probe(s);
            in = s.getInputStream();
            out = s.getOutputStream();
            return;
        }
        Remote r = new Remote(ctx, link, role);
        remote = r;
        r.connect();
        if (link.opus) {
            try {
                if (sends) enc = Opus.encCreate(channels, channels == 1 ? 64_000 : 160_000);
                else dec = Opus.decCreate(channels);
            } catch (LinkageError e) {      // no native lib for this CPU: an Error, would kill the app
                throw new IOException(ctx.getString(R.string.err_opus));
            }
            if (enc == 0 && dec == 0) throw new IOException(ctx.getString(R.string.err_opus));
        }
    }

    void read(byte[] pcm) throws IOException {
        if (usb != null) {
            Io.readFully(in, pcm, chunk);
            return;
        }
        while (true) {
            byte[] p = pending.poll();
            if (p != null) {
                if (fill(p == LOST ? null : p, p.length, pcm)) return;
                continue;
            }
            int n = remote.recv(pkt);
            int g = remote.gap();
            byte[] cur, before = null;
            if (remote.redundant) {
                if (n < 2) continue;
                int cl = ((pkt[0] & 0xff) << 8) | (pkt[1] & 0xff);
                if (2 + cl > n) continue;
                cur = Arrays.copyOfRange(pkt, 2, 2 + cl);
                if (n > 2 + cl) before = Arrays.copyOfRange(pkt, 2 + cl, n);
            } else {
                cur = Arrays.copyOf(pkt, n);
            }
            if (g > 0 && g <= 8) {         // lost packets: the last one comes back from this packet,
                for (int i = before != null ? 1 : 0; i < g; i++) pending.add(LOST);   // others concealed
                if (before != null) pending.add(before);
            }
            pending.add(cur);
        }
    }

    /** Decodes or copies one packet; p null = conceal a lost one. False if the packet was unusable. */
    private boolean fill(byte[] p, int n, byte[] pcm) {
        if (dec != 0) {
            if (Opus.decode(dec, p, n, pcm, FRAMES) == FRAMES) return true;
            if (p != null) return false;
        } else if (p != null) {
            if (n != chunk) return false;
            System.arraycopy(p, 0, pcm, 0, chunk);
            return true;
        }
        Arrays.fill(pcm, 0, chunk, (byte) 0);
        return true;
    }

    void write(byte[] pcm) throws IOException {
        if (usb != null) {
            out.write(pcm, 0, chunk);
        } else {
            byte[] cur;
            if (enc != 0) {
                int n = Opus.encode(enc, pcm, FRAMES, pkt);
                if (n < 0) throw new IOException("Opus encode error " + n);
                cur = Arrays.copyOf(pkt, n);
            } else {
                cur = Arrays.copyOf(pcm, chunk);
            }
            if (remote.redundant) {
                // the copy only if the packet stays under the Wi-Fi MTU (a fragmented one is
                // lost when either half is): always with Opus, not with raw stereo
                int extra = 2 + cur.length + prev.length <= Proto.MAX_REDUNDANT ? prev.length : 0;
                red[0] = (byte) (cur.length >>> 8);
                red[1] = (byte) cur.length;
                System.arraycopy(cur, 0, red, 2, cur.length);
                System.arraycopy(prev, 0, red, 2 + cur.length, extra);
                remote.send(red, 0, 2 + cur.length + extra);
                prev = cur;
            } else {
                remote.send(cur, 0, cur.length);
            }
        }
    }

    /** Any thread: unblocks a read/write in progress. */
    void abort() {
        Socket s = usb;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        Remote r = remote;
        if (r != null) r.close();
    }

    /** The pipe's own thread, when done (frees the codec, which mustn't be in use). */
    @Override
    public void close() {
        abort();
        if (enc != 0) { Opus.encFree(enc); enc = 0; }
        if (dec != 0) { Opus.decFree(dec); dec = 0; }
    }
}
