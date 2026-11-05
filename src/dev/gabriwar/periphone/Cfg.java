package dev.gabriwar.periphone;

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
    static final int AUDIO_PORT = 12348;         // phone -> PC, S16LE stereo: the phone's own sound
    static final int NET_PORT = 12350;           // periphone on the PC (Wi-Fi / Bluetooth), UDP + TCP

    static final int LINK_USB = 0, LINK_WIFI = 1, LINK_PAN = 2, LINK_BT = 3;
    static final int CODEC_AUTO = 0, CODEC_PCM = 1, CODEC_OPUS = 2;

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
    static final String K_CAP = "cap";                // phone audio -> PC
    static final String K_LINK = "link";              // LINK_*
    static final String K_HOST = "host";              // PC address for Wi-Fi / tethering, empty = find it
    static final String K_PIN = "pin";                // shown by periphone on the PC
    static final String K_ENCRYPT = "encrypt";
    static final String K_CODEC = "codec";            // CODEC_*
    static final String K_BT_ADDR = "btAddr";         // paired PC for Bluetooth direct
    static final String K_BATT_ASKED = "battAsked";   // asked once for the battery exemption

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

    /** Settings sent by the PC (adb: the Autostart alias or SettingsReceiver) into the prefs. */
    static void applyExtras(SharedPreferences.Editor e, android.content.Intent i) {
        if (i.hasExtra("spk")) e.putBoolean(Cfg.K_SPK, i.getBooleanExtra("spk", true));
        if (i.hasExtra("mic")) e.putBoolean(Cfg.K_MIC, i.getBooleanExtra("mic", true));
        if (i.hasExtra("cam")) e.putBoolean(Cfg.K_CAM, i.getBooleanExtra("cam", false));
        if (i.hasExtra("camfront")) e.putBoolean(Cfg.K_CAM_FRONT, i.getBooleanExtra("camfront", false));
        if (i.hasExtra("camsize")) {
            String s = i.getStringExtra("camsize");
            if (s != null && s.matches("\\d{3,4}x\\d{3,4}")) e.putString(Cfg.K_CAM_SIZE, s);
    }
    if (i.hasExtra("camfps")) e.putInt(Cfg.K_CAM_FPS, Math.max(5, Math.min(60, i.getIntExtra("camfps", 30))));
    if (i.hasExtra("buf")) e.putInt(Cfg.K_BUF, Cfg.clampBuf(i.getIntExtra("buf", Cfg.DEFAULT_BUF_MS)));
    if (i.hasExtra("micbuf")) e.putInt(Cfg.K_MIC_BUF, Cfg.clampBuf(i.getIntExtra("micbuf", Cfg.DEFAULT_MIC_BUF_MS)));
    if (i.hasExtra("src")) e.putInt(Cfg.K_SRC, i.getIntExtra("src", Cfg.DEFAULT_SRC));
    if (i.hasExtra("cap")) e.putBoolean(Cfg.K_CAP, i.getBooleanExtra("cap", false));
    if (i.hasExtra("link")) {
        e.putInt(Cfg.K_LINK, Math.max(Cfg.LINK_USB, Math.min(Cfg.LINK_BT, i.getIntExtra("link", Cfg.LINK_USB))));
    }
    if (i.hasExtra("host")) e.putString(Cfg.K_HOST, String.valueOf(i.getStringExtra("host")).trim());
    if (i.hasExtra("pin")) e.putString(Cfg.K_PIN, String.valueOf(i.getStringExtra("pin")).trim());
    if (i.hasExtra("encrypt")) e.putBoolean(Cfg.K_ENCRYPT, i.getBooleanExtra("encrypt", true));
    if (i.hasExtra("codec")) {
        e.putInt(Cfg.K_CODEC, Math.max(Cfg.CODEC_AUTO, Math.min(Cfg.CODEC_OPUS, i.getIntExtra("codec", 0))));
    }
    if (i.hasExtra("btaddr")) e.putString(Cfg.K_BT_ADDR, String.valueOf(i.getStringExtra("btaddr")).trim());
    int p;
    if (Cfg.validPort(p = i.getIntExtra("spkport", -1))) e.putInt(Cfg.K_SPK_PORT, p);
    if (Cfg.validPort(p = i.getIntExtra("micport", -1))) e.putInt(Cfg.K_MIC_PORT, p);
    if (Cfg.validPort(p = i.getIntExtra("camport", -1))) e.putInt(Cfg.K_CAM_PORT, p);
    }

    /** How the pipes reach the PC: a snapshot of the prefs, so a change restarts them. */
    static final class Link {
        final int mode;
        final String host, pin, btAddr;
        final boolean encrypt, opus;

        Link(SharedPreferences p) {
            mode = Math.max(LINK_USB, Math.min(LINK_BT, p.getInt(K_LINK, LINK_USB)));
            host = p.getString(K_HOST, "").trim();
            pin = p.getString(K_PIN, "").trim();
            btAddr = p.getString(K_BT_ADDR, "");
            encrypt = p.getBoolean(K_ENCRYPT, true);
            int codec = p.getInt(K_CODEC, CODEC_AUTO);
            // wireless: Opus. Bluetooth can't carry raw PCM reliably (~2.3 Mbit/s both ways), and on
            // Wi-Fi 10x smaller packets mean less airtime, room for a redundant copy (lost packets
            // rebuilt) and concealment instead of clicks, for ~2.5 ms of codec lookahead
            opus = codec == CODEC_OPUS || (codec == CODEC_AUTO && mode != LINK_USB);
        }

        boolean usb() {
            return mode == LINK_USB;
        }

        int flags() {
            return (encrypt ? Proto.F_ENCRYPT : 0) | (opus ? Proto.F_OPUS : 0);
        }

        String sig() {
            return usb() ? "usb" : mode + "/" + host + "/" + pin + "/" + btAddr + "/" + encrypt + "/" + opus;
        }
    }
}
