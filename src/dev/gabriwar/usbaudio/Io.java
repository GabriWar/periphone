package dev.gabriwar.usbaudio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

final class Io {
    private Io() {}

    /** Ganho em PCM S16LE, com saturacao (sem estourar pra ruido). */
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
            if (n < 0) throw new EOFException("conexao fechada pelo PC");
            off += n;
        }
    }

    /** Descarta exatamente len bytes (ou lanca EOF). Retorna len. */
    static long skipFully(InputStream in, long len) throws IOException {
        long left = len;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) {
                if (in.read() < 0) throw new EOFException("conexao fechada pelo PC");
                n = 1;
            }
            left -= n;
        }
        return len;
    }
}
