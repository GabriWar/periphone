package dev.gabriwar.usbaudio;

import android.content.Context;
import android.content.SharedPreferences;

/** Fixed format constants (must match the PC's PipeWire config) and preference keys. */
final class Cfg {
    private Cfg() {}

    // Fixed on purpose: there is no field to get the rate wrong and pitch the audio down.
    static final int RATE = 48000;
    static final int DEFAULT_SPK_PORT = 12345;   // PC -> phone, S16LE stereo
    static final int DEFAULT_MIC_PORT = 12346;   // phone -> PC, S16LE mono
    static final int DEFAULT_CAM_PORT = 12347;   // phone -> PC, framed H.264 (see CameraPipe)

    static final String K_SPK = "spk";
    static final String K_MIC = "mic";
    static final String K_CAM = "cam";
    static final String K_BUF = "buf";
    static final String K_SRC = "src";
    static final String K_AWAKE = "awake";
    static final String K_MIC_BUF = "micBuf";
    static final String K_SPK_PORT = "spkPort";
    static final String K_MIC_PORT = "micPort";
    static final String K_CAM_PORT = "camPort";
    static final String K_OUT_DEV = "outDev";   // "type:name:address", empty = automatic
    static final String K_IN_DEV = "inDev";
    static final String K_SPK_VOL = "spkVol";   // %, 0..200
    static final String K_MIC_VOL = "micVol";
    static final String K_CAM_FRONT = "camFront";
    static final String K_CAM_SIZE = "camSize";       // "1280x720"
    static final String K_CAM_FPS = "camFps";
    static final String K_CAM_QUALITY = "camQuality"; // 0 low, 1 medium, 2 high
    static final String K_CAM_ROT = "camRot";         // -1 auto (phone orientation), else 0/90/180/270
    static final String K_CAM_MIRROR = "camMirror";
    static final String K_CAM_PREVIEW = "camPreview";
    static final String K_ADVANCED = "advanced";      // advanced section expanded

    static final int MIN_BUF_MS = 10, MAX_BUF_MS = 200;
    static final int DEFAULT_BUF_MS = 30;
    static final int DEFAULT_MIC_BUF_MS = 20;
    static final int DEFAULT_SRC = 1; // MediaRecorder.AudioSource.MIC
    static final String DEFAULT_CAM_SIZE = "1280x720";
    static final int DEFAULT_CAM_FPS = 30;
    static final int DEFAULT_CAM_QUALITY = 1;

    /** Latency presets: {output buffer ms, mic buffer ms}. */
    static final int[][] PRESETS = {{15, 10}, {30, 20}, {80, 40}};

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static int clampBuf(int ms) {
        return Math.max(MIN_BUF_MS, Math.min(MAX_BUF_MS, ms));
    }

    static boolean validPort(int p) {
        return p >= 1024 && p <= 65535;
    }
}
