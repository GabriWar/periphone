package dev.gabriwar.periphone;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.util.Arrays;

/**
 * Phone -> PC. Captures the mic (mono S16LE 48 kHz) and sends it to the PipeWire port (USB tunnel)
 * or to periphone (Wi-Fi / Bluetooth, see AudioLink).
 * Only records while there is a PC on the other end, so the mic indicator isn't lit for nothing.
 */
final class MicPipe extends Thread {
    private static final int CHUNK = 2 * 240;        // mono, 5 ms

    private final Context ctx;
    private volatile boolean run = true;
    private volatile AudioLink conn;
    private final int source;
    private final int bufferMs;
    private final int port;
    private final Cfg.Link link;

    MicPipe(Context ctx, int source, int bufferMs, int port, Cfg.Link link) {
        super("mic-pipe");
        this.ctx = ctx;
        this.port = port;
        this.source = source;
        this.bufferMs = bufferMs;
        this.link = link;
    }

    void shutdown() {
        run = false;
        AudioLink c = conn;
        if (c != null) c.abort();
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
            AudioLink c = new AudioLink(ctx, link, Proto.ROLE_MIC, port, 1);
            conn = c;
            AudioRecord rec = null;
            try {
                c.open();
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
                    c.write(buf);
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
                conn = null;
                if (rec != null) {
                    try { rec.stop(); } catch (IllegalStateException ignored) {}
                    rec.release();
                }
                c.close();
            }

            if (run) {
                try { Thread.sleep(link.usb() ? 400 : 1500); } catch (InterruptedException e) { break; }
            }
        }
        Stats.micConnected = false;
    }
}
