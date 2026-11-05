package dev.gabriwar.periphone;

/** Ring of mono 16-bit samples, fed by the audio pipes and read by the screen. */
final class SpectrumTap {
    static final int SIZE = 16384;
    private static final int MASK = SIZE - 1;

    private final short[] ring = new short[SIZE];
    private volatile long wp;

    void pushMono(byte[] b, int len) {
        long p = wp;
        for (int i = 0; i + 1 < len; i += 2) {
            ring[(int) (p++ & MASK)] = (short) ((b[i + 1] << 8) | (b[i] & 0xff));
        }
        wp = p;
    }

    /** Stereo S16LE -> mono (average of the channels). */
    void pushStereo(byte[] b, int len) {
        long p = wp;
        for (int i = 0; i + 3 < len; i += 4) {
            int l = (short) ((b[i + 1] << 8) | (b[i] & 0xff));
            int r = (short) ((b[i + 3] << 8) | (b[i + 2] & 0xff));
            ring[(int) (p++ & MASK)] = (short) ((l + r) / 2);
        }
        wp = p;
    }

    long writePos() {
        return wp;
    }

    void copy(long from, short[] dst, int n) {
        for (int i = 0; i < n; i++) dst[i] = ring[(int) ((from + i) & MASK)];
    }
}
