package dev.gabriwar.usbaudio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

final class Io {
    private Io() {}

    /** Gain on PCM S16LE, saturating (clips instead of wrapping around into noise). */
    static void gain(byte[] b, int len, float g) {
        if (g == 1f) return;
        for (int i = 0; i + 1 < len; i += 2) {
            int v = (int) (((short) ((b[i + 1] << 8) | (b[i] & 0xff))) * g);
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            b[i] = (byte) v;
            b[i + 1] = (byte) (v >> 8);
        }
    }

    static void readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new EOFException("connection closed by the PC");
            off += n;
        }
    }

    /**
     * The adb tunnel accepts the connection even with nothing listening on the PC, and closes it
     * right after. Peek for a moment so we don't open the mic/camera for nothing.
     */
    static void probe(Socket s) throws IOException {
        s.setSoTimeout(150);
        try {
            if (s.getInputStream().read() < 0) throw new EOFException("nothing listening on the PC");
        } catch (SocketTimeoutException alive) {
            // timeout = live connection, that's what we want
        }
        s.setSoTimeout(0);
    }
}
