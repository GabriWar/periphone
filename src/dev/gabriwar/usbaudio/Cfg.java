package dev.gabriwar.usbaudio;

import android.content.Context;
import android.content.SharedPreferences;

/** Constantes fixas do formato (tem que casar com o PipeWire do PC) e preferencias. */
final class Cfg {
    private Cfg() {}

    // Formato fixo de proposito: nao existe campo pra errar a taxa e causar downpitch.
    static final int RATE = 48000;
    static final int DEFAULT_SPK_PORT = 12345;   // PC -> celular, S16LE estereo
    static final int DEFAULT_MIC_PORT = 12346;   // celular -> PC, S16LE mono

    static final String K_SPK = "spk";
    static final String K_MIC = "mic";
    static final String K_BUF = "buf";
    static final String K_SRC = "src";
    static final String K_AWAKE = "awake";
    static final String K_MIC_BUF = "micBuf";
    static final String K_SPK_PORT = "spkPort";
    static final String K_MIC_PORT = "micPort";
    static final String K_OUT_DEV = "outDev";   // "tipo:nome", vazio = automatico
    static final String K_IN_DEV = "inDev";
    static final String K_SPK_VOL = "spkVol";   // %, 0..200
    static final String K_MIC_VOL = "micVol";

    static final int DEFAULT_BUF_MS = 30;
    static final int DEFAULT_MIC_BUF_MS = 20;
    static final int DEFAULT_SRC = 1; // MediaRecorder.AudioSource.MIC

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }
}
