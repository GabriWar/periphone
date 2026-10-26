package dev.gabriwar.usbaudio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;

/**
 * PC -> phone. Reads PCM S16LE stereo 48 kHz from the port (via adb reverse) and plays it on an
 * AudioTrack.
 *
 * The delay that matters is what sits in the AudioTrack queue (written - played), so that is what
 * we control, around the "cushion" (the buffer setting):
 *   - burst (queue > 2.5x cushion): drop the chunk, caps the latency right away
 *   - slow drift (smoothed queue outside 0.75x..1.75x): drop or repeat ONE frame per 5 ms chunk.
 *     That is a 0.4% speed nudge, inaudible, and it absorbs the PC/phone clock difference without
 *     the clicks of dropping whole chunks.
 *   - dry queue: put the cushion of silence back (one hiccup instead of a run of crackles)
 * The AudioTrack buffer never fills, so write() never blocks and the pace comes from the socket.
 */
final class SpeakerPipe extends Thread {
    private static final int FRAME = 4;                  // 2 channels x 2 bytes
    private static final int CHUNK_FRAMES = 240;         // 5 ms
    private static final int CHUNK = FRAME * CHUNK_FRAMES;
    private static final int MID = CHUNK_FRAMES / 2;

    private final Context ctx;
    private volatile boolean run = true;
    private volatile Socket sock;
    private final int cushionFrames;
    private final int port;

    SpeakerPipe(Context ctx, int bufferMs, int port) {
        super("spk-pipe");
        this.ctx = ctx;
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
        int size = Math.max(minBuf, FRAME * (4 * cushionFrames + 8 * CHUNK_FRAMES));
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

    private static void release(AudioTrack t) {
        if (t == null) return;
        try { t.stop(); } catch (IllegalStateException ignored) {}
        t.release();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final byte[] buf = new byte[CHUNK + FRAME];      // +1 frame of room for the repeat nudge
        final byte[] silence = new byte[FRAME * cushionFrames];
        final AudioTimestamp ts = new AudioTimestamp();
        final int hardCap = cushionFrames * 5 / 2 + 2 * CHUNK_FRAMES;
        final float hi = cushionFrames * 1.75f, lo = cushionFrames * 0.75f;
        long lastLog = 0, lastLatency = 0;

        while (run) {
            Socket s = new Socket();
            sock = s;
            AudioTrack track = null;
            try {
                s.setReceiveBufferSize(16 * 1024);   // before connect: keeps the kernel queue short
                s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                s.setTcpNoDelay(true);
                InputStream in = s.getInputStream();

                long written = 0;                    // frames written to this track
                float smooth = cushionFrames;        // smoothed queue, in frames
                AudioDeviceInfo appliedDev = null;
                boolean first = true;

                while (run) {
                    Io.readFully(in, buf, CHUNK);
                    // every chunk: a pipe being replaced may still clear the flag on its way out
                    Stats.spkConnected = true;
                    if (first) {
                        first = false;
                        Stats.spkError = "";
                        track = newTrack();
                        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                            throw new IOException(ctx.getString(R.string.err_audio_track, track.getState()));
                        }
                        appliedDev = Stats.outDevice;
                        track.setPreferredDevice(appliedDev);   // null = automatic
                        track.write(silence, 0, silence.length);  // initial cushion
                        written = cushionFrames;
                        track.play();
                    }

                    AudioDeviceInfo wantDev = Stats.outDevice;
                    if (wantDev != appliedDev) {
                        track.setPreferredDevice(wantDev);
                        appliedDev = wantDev;
                    }

                    // the head is a 32-bit counter that wraps after ~24.8 h: diff in 32 bits
                    long queued = (int) written - track.getPlaybackHeadPosition();
                    smooth += 0.02f * (queued - smooth);
                    Stats.spkQueuedMs = (int) (queued * 1000 / Cfg.RATE);

                    if (queued > hardCap) {                    // burst: cap the latency now
                        Stats.spkDroppedMs += CHUNK_FRAMES * 1000 / Cfg.RATE;
                        smooth = Math.min(smooth, hi);
                        continue;
                    }
                    if (queued < CHUNK_FRAMES) {               // ran dry: rebuild the cushion
                        track.write(silence, 0, silence.length);
                        written += cushionFrames;
                        smooth = cushionFrames;
                    }

                    int frames = CHUNK_FRAMES;
                    if (smooth > hi) {                         // drift: drop one frame
                        System.arraycopy(buf, (MID + 1) * FRAME, buf, MID * FRAME, (CHUNK_FRAMES - MID - 1) * FRAME);
                        frames--;
                    } else if (smooth < lo) {                  // drift: repeat one frame
                        System.arraycopy(buf, MID * FRAME, buf, (MID + 1) * FRAME, (CHUNK_FRAMES - MID) * FRAME);
                        frames++;
                    }
                    int len = frames * FRAME;

                    if (Stats.muteSpk) Arrays.fill(buf, 0, len, (byte) 0);
                    else Io.gain(buf, len, Stats.spkGain);
                    Stats.spkTap.pushStereo(buf, len);
                    int w = track.write(buf, 0, len);
                    if (w < 0) throw new IOException(ctx.getString(R.string.err_audio_track, w));
                    written += w / FRAME;
                    Stats.spkBytes += CHUNK;
                    Stats.spkUnderruns = track.getUnderrunCount();

                    long now = SystemClock.elapsedRealtime();
                    if (now - lastLatency >= 250) {
                        lastLatency = now;
                        // what is between our write() and the speaker, including the HAL
                        if (track.getTimestamp(ts)) {
                            long played = ts.framePosition
                                    + (System.nanoTime() - ts.nanoTime) * Cfg.RATE / 1_000_000_000L;
                            long ahead = (int) (written - played);   // framePosition wraps like the head
                            Stats.spkLatencyMs = (int) Math.max(0, ahead * 1000 / Cfg.RATE);
                        } else {
                            Stats.spkLatencyMs = -1;
                        }
                    }
                    if (now - lastLog >= 5000) {
                        lastLog = now;
                        Log.i("UsbAudio", "spk queuedMs=" + Stats.spkQueuedMs
                                + " latencyMs=" + Stats.spkLatencyMs
                                + " underruns=" + Stats.spkUnderruns
                                + " droppedMs=" + Stats.spkDroppedMs);
                    }
                }
            } catch (ConnectException e) {
                if (run) Stats.spkError = ctx.getString(R.string.err_no_tunnel, port);
            } catch (EOFException | SocketException e) {   // closed / reset by the PC
                if (run) Stats.spkError = ctx.getString(R.string.err_pc_closed);
            } catch (IOException | RuntimeException e) {   // AudioTrack throws unchecked on init failures
                if (run) Stats.spkError = String.valueOf(e.getMessage());
            } finally {
                Stats.spkConnected = false;
                Stats.spkQueuedMs = 0;
                Stats.spkLatencyMs = -1;
                sock = null;
                try { s.close(); } catch (IOException ignored) {}
                release(track);   // don't keep the output (and the BT headset) busy while idle
            }

            if (run) {
                try { Thread.sleep(400); } catch (InterruptedException e) { break; }
            }
        }
    }
}
