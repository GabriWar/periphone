package dev.gabriwar.usbaudio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;

/**
 * Phone -> PC. Captures the mic (mono S16LE 48 kHz) and sends it to the PipeWire port.
 * Only records while there is a PC on the other end, so the mic indicator isn't lit for nothing.
 */
final class MicPipe extends Thread {
    private static final int CHUNK = 2 * 240;        // mono, 5 ms

    private final Context ctx;
    private volatile boolean run = true;
    private volatile Socket sock;
    private final int source;
    private final int bufferMs;
    private final int port;

    MicPipe(Context ctx, int source, int bufferMs, int port) {
        super("mic-pipe");
        this.ctx = ctx;
        this.port = port;
        this.source = source;
        this.bufferMs = bufferMs;
    }

    void shutdown() {
        run = false;
        Socket s = sock;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        interrupt();
    }

    private AudioRecord open(int src) throws IOException {
        int minBuf = AudioRecord.getMinBufferSize(
                Cfg.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(minBuf, Math.max(CHUNK * 2, 2 * Cfg.RATE * bufferMs / 1000));
        AudioRecord r;
        try {
            r = new AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Cfg.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(size)
                    .build();
        } catch (RuntimeException e) {
            if (src != MediaRecorder.AudioSource.MIC) return open(MediaRecorder.AudioSource.MIC);
            throw new IOException(ctx.getString(R.string.err_mic_open, e.getMessage()));
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            if (src != MediaRecorder.AudioSource.MIC) return open(MediaRecorder.AudioSource.MIC);
            throw new IOException(ctx.getString(R.string.err_mic_init));
        }
        return r;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[CHUNK];

        while (run) {
            Socket s = new Socket();
            sock = s;
            AudioRecord rec = null;
            try {
                s.setSendBufferSize(8 * 1024);           // before connect
                s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                s.setTcpNoDelay(true);
                Io.probe(s);

                OutputStream out = s.getOutputStream();
                rec = open(source);
                AudioDeviceInfo appliedDev = Stats.inDevice;
                rec.setPreferredDevice(appliedDev);
                rec.startRecording();
                Stats.micConnected = true;
                Stats.micError = "";

                while (run) {
                    AudioDeviceInfo wantDev = Stats.inDevice;
                    if (wantDev != appliedDev) {
                        rec.setPreferredDevice(wantDev);
                        appliedDev = wantDev;
                    }
                    int off = 0;
                    while (off < CHUNK) {
                        int n = rec.read(buf, off, CHUNK - off);
                        if (n < 0) throw new IOException(ctx.getString(R.string.err_audio_record, n));
                        off += n;
                    }
                    if (Stats.muteMic) Arrays.fill(buf, 0, CHUNK, (byte) 0);
                    else Io.gain(buf, CHUNK, Stats.micGain);
                    Stats.micTap.pushMono(buf, CHUNK);
                    int peak = 0;
                    for (int i = 0; i < CHUNK; i += 2) {
                        int v = (short) ((buf[i + 1] << 8) | (buf[i] & 0xff));
                        if (v < 0) v = -v;
                        if (v > peak) peak = v;
                    }
                    Stats.micPeak = Math.max(peak, Stats.micPeak - 400);
                    out.write(buf, 0, CHUNK);
                    Stats.micBytes += CHUNK;
                }
            } catch (ConnectException e) {
                if (run) Stats.micError = ctx.getString(R.string.err_no_tunnel, port);
            } catch (EOFException | SocketException e) {   // closed / reset by the PC
                if (run) Stats.micError = ctx.getString(R.string.err_pc_not_listening, port);
            } catch (IOException | RuntimeException e) {   // AudioRecord throws unchecked too
                if (run) Stats.micError = String.valueOf(e.getMessage());
            } finally {
                Stats.micConnected = false;
                Stats.micPeak = 0;
                sock = null;
                if (rec != null) {
                    try { rec.stop(); } catch (IllegalStateException ignored) {}
                    rec.release();
                }
                try { s.close(); } catch (IOException ignored) {}
            }

            if (run) {
                try { Thread.sleep(400); } catch (InterruptedException e) { break; }
            }
        }
        Stats.micConnected = false;
    }
}
