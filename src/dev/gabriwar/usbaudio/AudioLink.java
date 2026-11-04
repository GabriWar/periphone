package dev.gabriwar.usbaudio;

import android.content.Context;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * 5 ms PCM chunks to/from the PC over whatever link is set: raw PCM over the USB tunnel straight to
 * PipeWire, or Remote packets (PCM or Opus) to phone-relay. Lost packets are concealed (Opus PLC
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
    private final byte[] pkt = new byte[2048], stash = new byte[2048];
    private int conceal, stashLen = -1;

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
        if (conceal > 0) {
            conceal--;
            fill(null, 0, pcm);
            return;
        }
        if (stashLen >= 0) {
            int n = stashLen;
            stashLen = -1;
            if (fill(stash, n, pcm)) return;
        }
        while (true) {
            int n = remote.recv(pkt);
            int g = remote.gap();
            if (g > 0 && g <= 8) {         // lost packets: conceal them first, play this one after
                System.arraycopy(pkt, 0, stash, 0, n);
                stashLen = n;
                conceal = g - 1;
                fill(null, 0, pcm);
                return;
            }
            if (fill(pkt, n, pcm)) return;
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
        } else if (enc != 0) {
            int n = Opus.encode(enc, pcm, FRAMES, pkt);
            if (n < 0) throw new IOException("Opus encode error " + n);
            remote.send(pkt, 0, n);
        } else {
            remote.send(pcm, 0, chunk);
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
