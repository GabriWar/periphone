package dev.gabriwar.periphone;

/** libopus (jni/opus_jni.c), 48 kHz, PCM as S16LE bytes. Handles are 0 when creation failed. */
final class Opus {
    private Opus() {}

    static {
        System.loadLibrary("uabopus");
    }

    /** Restricted low-delay mode (CELT only): no lookahead beyond the 5 ms frame we feed it. */
    static native long encCreate(int channels, int bitrate);

    static native int encode(long h, byte[] pcm, int frames, byte[] out);

    static native void encFree(long h);

    static native long decCreate(int channels);

    /** pkt null = the packet was lost: conceal it. Returns frames decoded or a negative error. */
    static native int decode(long h, byte[] pkt, int len, byte[] pcm, int frames);

    static native void decFree(long h);
}
