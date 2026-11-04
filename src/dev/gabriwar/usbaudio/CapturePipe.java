package dev.gabriwar.usbaudio;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Process;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;

/**
 * Phone sound -> PC speakers (Android 10+). Captures what the other apps play (playback capture,
 * which Android only allows with the screen-capture consent, see BridgeService) and sends it as
 * S16LE stereo 48 kHz. Our own output (PC audio) is excluded, so nothing loops back.
 * Apps can opt out of being captured (calls, some DRM video); those stay silent.
 */
final class CapturePipe extends Thread {
    private static final int CHUNK = 4 * AudioLink.FRAMES;   // stereo, 5 ms

    private final Context ctx;
    private final int port;
    private final Cfg.Link link;
    private volatile boolean run = true;
    private volatile AudioLink conn;

    CapturePipe(Context ctx, int port, Cfg.Link link) {
        super("cap-pipe");
        this.ctx = ctx;
        this.port = port;
        this.link = link;
    }

    void shutdown() {
        run = false;
        AudioLink c = conn;
        if (c != null) c.abort();
        interrupt();
    }

    @TargetApi(29)
    private AudioRecord open(MediaProjection mp) throws IOException {
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build();
        int minBuf = AudioRecord.getMinBufferSize(
                Cfg.RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r;
        try {
            r = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Cfg.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, CHUNK * 4))
                    .build();
        } catch (RuntimeException e) {   // SecurityException without RECORD_AUDIO, or a bad config
            throw new IOException(ctx.getString(R.string.err_cap_open, e.getMessage()));
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IOException(ctx.getString(R.string.err_cap_open, "init"));
        }
        return r;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[CHUNK];
        if (Build.VERSION.SDK_INT < 29) {
            Stats.capError = ctx.getString(R.string.err_cap_android);
            return;
        }
        while (run) {
            MediaProjection mp = Stats.projection;
            if (mp == null) {          // consent not given yet, or ended from the status bar
                Stats.capError = ctx.getString(R.string.err_cap_consent);
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                continue;
            }
            AudioLink c = new AudioLink(ctx, link, Proto.ROLE_AUDIO, port, 2);
            conn = c;
            AudioRecord rec = null;
            try {
                c.open();
                rec = open(mp);
                rec.startRecording();
                Stats.capConnected = true;
                Stats.capError = "";
                while (run && Stats.projection == mp) {
                    int off = 0;
                    while (off < CHUNK) {
                        int n = rec.read(buf, off, CHUNK - off);
                        if (n < 0) throw new IOException(ctx.getString(R.string.err_audio_record, n));
                        off += n;
                    }
                    c.write(buf);
                    Stats.capBytes += CHUNK;
                }
            } catch (ConnectException e) {
                if (run) Stats.capError = ctx.getString(R.string.err_no_tunnel, port);
            } catch (EOFException | SocketException e) {
                if (run) Stats.capError = ctx.getString(R.string.err_pc_not_listening, port);
            } catch (IOException | RuntimeException e) {
                if (run) Stats.capError = String.valueOf(e.getMessage());
            } finally {
                Stats.capConnected = false;
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
        Stats.capConnected = false;
    }
}
