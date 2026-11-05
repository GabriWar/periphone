package dev.gabriwar.periphone;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Phone camera -> PC. Camera2 renders straight into the hardware H.264 encoder's input surface;
 * the encoded stream goes to the PC (pc/periphone (camera.go)), which decodes it into a v4l2loopback
 * webcam. Over the USB tunnel the bytes below go straight to phone-cam; over Wi-Fi each write is
 * one sealed message to periphone (TCP, see Remote), which hands the same bytes to its camera receiver.
 *
 * Wire format, all big endian, a sequence of packets: [type u8][length u32][payload]
 *   CONFIG (1): width u16, height u16, fps u16, rotation u16, flags u8
 *               rotation = clockwise degrees the PC must rotate the image; flags bit0 = mirror.
 *               Starts a new H.264 stream (camera switched, rotated...). The socket stays up, so
 *               the PC keeps the webcam device open and only restarts its decoder.
 *   DATA   (2): Annex-B H.264 (codec config, then frames). Each frame is followed by an access
 *               unit delimiter, so the PC's parser can emit it right away instead of waiting for
 *               the next frame (saves one frame of latency).
 */
final class CameraPipe extends Thread {
    private static final int PKT_CONFIG = 1, PKT_DATA = 2;
    private static final byte[] AUD = {0, 0, 0, 1, 9, (byte) 0xF0};
    private static final float[] BITS_PER_PIXEL = {0.06f, 0.12f, 0.2f};

    private final Context ctx;
    private final int port, wantW, wantH, wantFps, quality, rotSetting;
    private final boolean mirror;
    private volatile boolean run = true;
    private volatile Closeable sock;    // Socket (USB) or Remote (Wi-Fi)
    private final Cfg.Link link;

    private HandlerThread camThread;
    private Handler camHandler;
    private OrientationEventListener orientListener;
    // physical orientation, snapped to 0/90/180/270, adopted after it is stable for a while
    private volatile int orientation, pendingOrient;
    private volatile long pendingSince;
    private volatile boolean orientKnown;

    private volatile String camFailure;   // set by camera callbacks
    private boolean probed, configSent;    // PC answered / this connection got as far as streaming

    CameraPipe(Context ctx, int port, String size, int fps, int quality, int rotSetting, boolean mirror,
               Cfg.Link link) {
        super("cam-pipe");
        this.ctx = ctx;
        this.link = link;
        this.port = port;
        int[] wh = parseSize(size);
        this.wantW = wh[0];
        this.wantH = wh[1];
        this.wantFps = fps;
        this.quality = Math.max(0, Math.min(2, quality));
        this.rotSetting = rotSetting;
        this.mirror = mirror;
    }

    static int[] parseSize(String s) {
        try {
            String[] p = s.split("x");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (RuntimeException e) {
            return new int[]{1280, 720};
        }
    }

    void shutdown() {
        run = false;
        Closeable s = sock;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        interrupt();
    }

    // ---------- camera lookup ----------

    /** Id of the first camera facing that way, or null. */
    static String findCamera(CameraManager cm, boolean front) throws CameraAccessException {
        int want = front ? CameraMetadata.LENS_FACING_FRONT : CameraMetadata.LENS_FACING_BACK;
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == want) return id;
        }
        return null;
    }

    /** The wanted size if the camera can feed it to an encoder, else the closest one with the same shape. */
    static Size pickSize(CameraCharacteristics ch, int w, int h) {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(MediaCodec.class);
        if (sizes == null || sizes.length == 0) return new Size(w, h);
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;   // encoder-safe
            long aspectOff = Math.abs((long) s.getWidth() * h - (long) w * s.getHeight());
            long score = aspectOff * 1000 + Math.abs((long) s.getWidth() * s.getHeight() - (long) w * h) / 100;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best != null ? best : new Size(w, h);
    }

    /** Fixed [fps, fps] if offered, else the range reaching fps with the highest floor. */
    static Range<Integer> pickFps(CameraCharacteristics ch, int fps) {
        Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) return new Range<>(fps, fps);
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (best == null) { best = r; continue; }
            int d = Math.abs(r.getUpper() - fps), bd = Math.abs(best.getUpper() - fps);
            if (d < bd || (d == bd && r.getLower() > best.getLower())) best = r;
        }
        return best;
    }

    /** Clockwise rotation that makes the image upright for the current physical orientation. */
    private int autoRotation(CameraCharacteristics ch, boolean front) {
        Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = so == null ? 90 : so;
        int dev = orientation;
        return front ? (sensor - dev + 360) % 360 : (sensor + dev) % 360;
    }

    private void startOrientation() {
        orientListener = new OrientationEventListener(ctx) {
            @Override
            public void onOrientationChanged(int deg) {
                if (deg == ORIENTATION_UNKNOWN) return;          // lying flat: keep the last one
                int snapped = ((deg + 45) / 90 * 90) % 360;
                int off = Math.abs(deg - snapped);
                if (Math.min(off, 360 - off) > 30) return;       // hysteresis between quadrants
                if (!orientKnown) {                              // first reading: adopt right away
                    orientKnown = true;
                    orientation = pendingOrient = snapped;
                    pendingSince = SystemClock.elapsedRealtime();
                } else if (snapped != pendingOrient) {
                    pendingOrient = snapped;
                    pendingSince = SystemClock.elapsedRealtime();
                } else if (snapped != orientation
                        && SystemClock.elapsedRealtime() - pendingSince > 800) {
                    orientation = snapped;
                }
            }
        };
        if (orientListener.canDetectOrientation()) orientListener.enable();
    }

    // ---------- run ----------

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        camThread = new HandlerThread("cam-callbacks");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        if (rotSetting < 0) startOrientation();

        try {
            while (run) {
                probed = configSent = false;
                Closeable s = null;
                try {
                    if (link.mode == Cfg.LINK_BT) throw new IOException(ctx.getString(R.string.err_cam_bt));
                    OutputStream out;
                    if (link.usb()) {
                        Socket u = new Socket();
                        sock = s = u;
                        u.setSendBufferSize(64 * 1024);
                        u.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                        u.setTcpNoDelay(true);
                        Io.probe(u);
                        out = u.getOutputStream();
                    } else {
                        final Remote r = new Remote(ctx, link, Proto.ROLE_CAM);
                        sock = s = r;
                        r.connect();
                        out = new OutputStream() {
                            @Override public void write(int b) throws IOException {
                                r.send(new byte[]{(byte) b}, 0, 1);
                            }
                            @Override public void write(byte[] b, int off, int len) throws IOException {
                                r.send(b, off, len);
                            }
                        };
                    }
                    probed = true;
                    stream(out);
                } catch (ConnectException e) {
                    if (run) Stats.camError = ctx.getString(R.string.err_no_tunnel, port);
                } catch (EOFException | SocketException e) {
                    if (run) Stats.camError = ctx.getString(R.string.err_cam_pc, port);
                } catch (IOException | RuntimeException e) {   // camera/codec throw unchecked too
                    if (run) Stats.camError = String.valueOf(e.getMessage());
                } catch (InterruptedException e) {
                    break;
                } finally {
                    Stats.camConnected = false;
                    sock = null;
                    if (s != null) {
                        try { s.close(); } catch (IOException ignored) {}
                    }
                }
                if (run) {
                    // failed before streaming (camera busy, size rejected...): don't hammer it
                    long wait = probed && !configSent ? 3000 : link.usb() ? 400 : 1500;
                    try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
                }
            }
        } finally {
            if (orientListener != null) orientListener.disable();
            camThread.quitSafely();
            Stats.camConnected = false;
            Stats.camInfo = "";
        }
    }

    /** One connection: runs camera sessions back to back until something fails. */
    private void stream(OutputStream out) throws IOException, InterruptedException {
        CameraManager cm = ctx.getSystemService(CameraManager.class);
        byte[] pkt = new byte[256 * 1024];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (run) {
            boolean front = Stats.camFront;
            String id;
            CameraCharacteristics ch;
            try {
                id = findCamera(cm, front);
                if (id == null) throw new IOException(ctx.getString(
                        front ? R.string.err_no_front_cam : R.string.err_no_back_cam));
                ch = cm.getCameraCharacteristics(id);
            } catch (CameraAccessException e) {
                throw new IOException(ctx.getString(R.string.err_cam_open, e.getMessage()));
            }
            Size size = pickSize(ch, wantW, wantH);
            Range<Integer> fpsRange = pickFps(ch, wantFps);
            int fps = fpsRange.getUpper();
            int rotation = rotSetting >= 0 ? rotSetting : autoRotation(ch, front);
            int bitrate = (int) Math.max(500_000, Math.min(20_000_000,
                    (long) size.getWidth() * size.getHeight() * fps * BITS_PER_PIXEL[quality]));

            MediaCodec enc = null;
            Surface encSurface = null;
            CameraDevice dev = null;
            CameraCaptureSession session = null;
            camFailure = null;
            try {
                enc = newEncoder(size, fps, bitrate);
                encSurface = enc.createInputSurface();
                enc.start();
                dev = openCamera(cm, id);
                Surface preview = Stats.camPreview;
                session = newSession(dev, encSurface, preview, fpsRange);
                if (session == null && preview != null) {      // preview surface went away mid-way
                    preview = null;
                    session = newSession(dev, encSurface, null, fpsRange);
                }
                if (session == null) throw new IOException(ctx.getString(R.string.err_cam_session));

                writeConfig(out, size.getWidth(), size.getHeight(), fps, rotation);
                configSent = true;
                Stats.camInfo = size.getWidth() + "×" + size.getHeight() + " · " + fps + " fps · "
                        + (bitrate / 100_000) / 10f + " Mbps";
                Stats.camError = "";

                while (run) {
                    int idx = enc.dequeueOutputBuffer(info, 20_000);
                    if (idx >= 0) {
                        ByteBuffer bb = enc.getOutputBuffer(idx);
                        if (bb != null && info.size > 0) {
                            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            int len = info.size + (config ? 0 : AUD.length);
                            if (pkt.length < len + 5) pkt = new byte[len + 5 + 64 * 1024];
                            header(pkt, PKT_DATA, len);
                            bb.position(info.offset);
                            bb.get(pkt, 5, info.size);
                            if (!config) System.arraycopy(AUD, 0, pkt, 5 + info.size, AUD.length);
                            out.write(pkt, 0, len + 5);
                            Stats.camBytes += len;
                            if (!config) {
                                Stats.camFrames++;
                                Stats.camConnected = true;
                            }
                        }
                        enc.releaseOutputBuffer(idx, false);
                    }

                    String fail = camFailure;
                    if (fail != null) throw new IOException(fail);
                    // switched camera or turned the phone: new session, same connection
                    if (Stats.camFront != front) break;
                    if (rotSetting < 0 && autoRotation(ch, front) != rotation) break;
                    Surface want = Stats.camPreview;
                    if (want != preview) {                         // screen opened / closed
                        session.close();
                        preview = want;
                        session = newSession(dev, encSurface, preview, fpsRange);
                        if (session == null && preview != null) {
                            preview = null;
                            session = newSession(dev, encSurface, null, fpsRange);
                        }
                        if (session == null) throw new IOException(ctx.getString(R.string.err_cam_session));
                    }
                }
            } finally {
                if (session != null) session.close();
                if (dev != null) dev.close();
                if (enc != null) {
                    try { enc.stop(); } catch (IllegalStateException ignored) {}
                    enc.release();
                }
                if (encSurface != null) encSurface.release();
            }
        }
    }

    private MediaCodec newEncoder(Size size, int fps, int bitrate) throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                size.getWidth(), size.getHeight());
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);                         // realtime
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);   // keep the PC fed if the camera stalls
        if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LATENCY, 1);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (RuntimeException e) {
            enc.release();
            throw new IOException(ctx.getString(R.string.err_encoder, size.getWidth() + "x" + size.getHeight()));
        }
        return enc;
    }

    private CameraDevice openCamera(CameraManager cm, String id) throws IOException, InterruptedException {
        final CameraDevice[] out = new CameraDevice[1];
        final String[] err = new String[1];
        final boolean[] gaveUp = new boolean[1];
        final CountDownLatch done = new CountDownLatch(1);
        try {
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    synchronized (out) {
                        if (gaveUp[0]) d.close();                  // we stopped waiting: don't leak it
                        else out[0] = d;
                    }
                    done.countDown();
                }
                @Override public void onDisconnected(CameraDevice d) {
                    camFailure = ctx.getString(R.string.err_cam_taken);
                    err[0] = camFailure;
                    d.close();
                    done.countDown();
                }
                @Override public void onError(CameraDevice d, int code) {
                    camFailure = ctx.getString(R.string.err_cam_error, code);
                    err[0] = camFailure;
                    d.close();
                    done.countDown();
                }
            }, camHandler);
        } catch (SecurityException e) {
            throw new IOException(ctx.getString(R.string.err_cam_permission));
        } catch (CameraAccessException e) {
            throw new IOException(ctx.getString(R.string.err_cam_open, e.getMessage()));
        }
        boolean ok = false;
        try {
            ok = done.await(4, TimeUnit.SECONDS);
        } finally {
            synchronized (out) {
                if (!ok) {
                    gaveUp[0] = true;
                    if (out[0] != null) out[0].close();
                    out[0] = null;
                }
            }
        }
        if (!ok) throw new IOException(ctx.getString(R.string.err_cam_timeout));
        if (out[0] == null) throw new IOException(err[0]);
        return out[0];
    }

    /** Capture session into the encoder (and the on-screen preview); null if it failed to configure. */
    @SuppressWarnings("deprecation")   // the List<Surface> overload works on every API level we support
    private CameraCaptureSession newSession(CameraDevice dev, Surface enc, Surface preview,
                                            Range<Integer> fps) throws InterruptedException {
        List<Surface> targets = new ArrayList<>();
        targets.add(enc);
        if (preview != null && preview.isValid()) targets.add(preview);
        final CameraCaptureSession[] out = new CameraCaptureSession[1];
        final CountDownLatch done = new CountDownLatch(1);
        try {
            dev.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    out[0] = s;
                    done.countDown();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    done.countDown();
                }
            }, camHandler);
            if (!done.await(4, TimeUnit.SECONDS) || out[0] == null) return null;
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            for (Surface t : targets) b.addTarget(t);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            // electronic stabilization holds frames back: latency
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            out[0].setRepeatingRequest(b.build(), null, camHandler);
            return out[0];
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            if (out[0] != null) out[0].close();
            return null;
        }
    }

    private static void header(byte[] b, int type, int len) {
        b[0] = (byte) type;
        b[1] = (byte) (len >>> 24);
        b[2] = (byte) (len >>> 16);
        b[3] = (byte) (len >>> 8);
        b[4] = (byte) len;
    }

    private void writeConfig(OutputStream out, int w, int h, int fps, int rotation) throws IOException {
        byte[] b = new byte[5 + 9];
        header(b, PKT_CONFIG, 9);
        b[5] = (byte) (w >>> 8);  b[6] = (byte) w;
        b[7] = (byte) (h >>> 8);  b[8] = (byte) h;
        b[9] = (byte) (fps >>> 8); b[10] = (byte) fps;
        b[11] = (byte) (rotation >>> 8); b[12] = (byte) rotation;
        b[13] = (byte) (mirror ? 1 : 0);
        out.write(b);
    }
}
