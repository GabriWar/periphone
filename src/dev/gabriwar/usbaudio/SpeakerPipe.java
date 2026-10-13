package dev.gabriwar.usbaudio;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * PC -> celular. Le PCM S16LE estereo 48 kHz da porta (via adb reverse) e toca num AudioTrack.
 *
 * O atraso que importa e o que esta na fila do AudioTrack (escrito - tocado), entao e ele que
 * controlamos, mantendo entre 1x e 2x o colchao:
 *   - fila > 2x colchao: descarta o pedaco (limita a latencia; so acontece em rajada)
 *   - fila secou:        recoloca o colchao de silencio (um soluco, em vez de crackle seguido)
 * O buffer do AudioTrack nunca enche, entao o write nao bloqueia e o ritmo vem do socket.
 */
final class SpeakerPipe extends Thread {
    private static final int FRAME = 4;                  // 2 canais x 2 bytes
    private static final int CHUNK_FRAMES = 240;         // 5 ms
    private static final int CHUNK = FRAME * CHUNK_FRAMES;

    private volatile boolean run = true;
    private volatile Socket sock;
    private final int cushionFrames;
    private final int port;

    SpeakerPipe(int bufferMs, int port) {
        super("spk-pipe");
        this.port = port;
        this.cushionFrames = Cfg.RATE * bufferMs / 1000;
    }

    void shutdown() {
        run = false;
        Socket s = sock;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        interrupt();
    }

    private AudioTrack newTrack() {
        int minBuf = AudioTrack.getMinBufferSize(
                Cfg.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(minBuf, FRAME * (2 * cushionFrames + 4 * CHUNK_FRAMES));
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Cfg.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(size)
                .build();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final byte[] buf = new byte[CHUNK];
        final byte[] silence = new byte[FRAME * cushionFrames];
        AudioTrack track = null;
        long written = 0;                                // frames escritos no track atual
        long lastLog = 0;
        boolean devApplied = false;
        AudioDeviceInfo appliedDev = null;

        try {
            while (run) {
                Socket s = new Socket();
                sock = s;
                try {
                    s.setReceiveBufferSize(16 * 1024);   // antes do connect
                    s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                    s.setTcpNoDelay(true);
                    InputStream in = s.getInputStream();

                    boolean first = true;
                    while (run) {
                        Io.readFully(in, buf, CHUNK);
                        if (first) {
                            first = false;
                            Stats.spkConnected = true;
                            Stats.spkError = "";
                        }
                        if (track == null) {
                            track = newTrack();
                            track.write(silence, 0, silence.length);    // colchao inicial
                            written = cushionFrames;
                            track.play();
                        }

                        AudioDeviceInfo wantDev = Stats.outDevice;
                        if (!devApplied || wantDev != appliedDev) {
                            track.setPreferredDevice(wantDev);     // null = volta pro automatico
                            appliedDev = wantDev;
                            devApplied = true;
                        }

                        long queued = written - (track.getPlaybackHeadPosition() & 0xffffffffL);
                        Stats.spkQueuedMs = (int) (queued * 1000 / Cfg.RATE);

                        if (queued > 2L * cushionFrames) {          // rajada: limita a latencia
                            Stats.spkDroppedMs += CHUNK_FRAMES * 1000 / Cfg.RATE;
                            continue;
                        }
                        if (queued < CHUNK_FRAMES) {                // secou: recompoe o colchao
                            track.write(silence, 0, silence.length);
                            written += cushionFrames;
                        }

                        if (Stats.muteSpk) Arrays.fill(buf, 0, CHUNK, (byte) 0);
                        else Io.gain(buf, CHUNK, Stats.spkGain);
                        Stats.spkTap.pushStereo(buf, CHUNK);
                        int w = track.write(buf, 0, CHUNK);
                        if (w < 0) throw new IOException("AudioTrack erro " + w);
                        written += w / FRAME;
                        Stats.spkBytes += CHUNK;
                        Stats.spkUnderruns = track.getUnderrunCount();

                        long now = SystemClock.elapsedRealtime();
                        if (now - lastLog >= 5000) {
                            lastLog = now;
                            Log.i("UsbAudio", "spk queuedMs=" + Stats.spkQueuedMs
                                    + " underruns=" + Stats.spkUnderruns
                                    + " droppedMs=" + Stats.spkDroppedMs);
                        }
                    }
                } catch (IOException e) {
                    if (run) Stats.spkError = String.valueOf(e.getMessage());
                } finally {
                    Stats.spkConnected = false;
                    sock = null;
                    try { s.close(); } catch (IOException ignored) {}
                }

                if (run) {
                    try { Thread.sleep(400); } catch (InterruptedException e) { break; }
                }
            }
        } finally {
            if (track != null) {
                try { track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
            }
            Stats.spkConnected = false;
        }
    }
}
