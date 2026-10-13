package dev.gabriwar.usbaudio;

import android.media.AudioDeviceInfo;

/** Estado compartilhado entre servico e tela (mesmo processo). */
final class Stats {
    private Stats() {}

    static volatile boolean running;

    // amostras pro espectrograma (ja depois do mute: mostra o que realmente sai/entra)
    static final SpectrumTap spkTap = new SpectrumTap();
    static final SpectrumTap micTap = new SpectrumTap();

    // mute: a conexao continua de pe, so vai silencio
    static volatile boolean muteSpk;
    static volatile boolean muteMic;

    // ganho por software, 1.0 = 100%
    static volatile float spkGain = 1f;
    static volatile float micGain = 1f;

    // dispositivo escolhido no celular (null = automatico); os pipes trocam ao vivo
    static volatile AudioDeviceInfo outDevice;
    static volatile AudioDeviceInfo inDevice;

    static volatile boolean spkConnected;
    static volatile long spkBytes;
    static volatile int spkUnderruns;
    static volatile long spkDroppedMs;
    static volatile int spkQueuedMs;      // atraso na fila do AudioTrack
    static volatile String spkError = "";

    static volatile boolean micConnected;
    static volatile long micBytes;
    static volatile int micPeak;          // 0..32767, com decaimento
    static volatile String micError = "";
}
