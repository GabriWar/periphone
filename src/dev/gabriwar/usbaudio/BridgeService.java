package dev.gabriwar.usbaudio;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Owns the pipes. Runs in the foreground to survive the screen turning off.
 * ACTION_START (re)applies the settings, restarting only the pipes whose settings changed, so
 * e.g. changing the camera resolution doesn't cut the audio.
 */
public class BridgeService extends Service {
    static final String ACTION_START = "dev.gabriwar.usbaudio.START";
    static final String ACTION_STOP = "dev.gabriwar.usbaudio.STOP";
    static final String ACTION_MUTE_MIC = "dev.gabriwar.usbaudio.MUTE_MIC";
    // screen-capture consent (resultCode + data Intent) for the phone sound -> PC stream
    static final String EXTRA_PROJ_CODE = "projCode", EXTRA_PROJ_DATA = "projData";
    private static final String CHANNEL = "bridge";
    private static final int NOTIF_ID = 1;

    private SpeakerPipe spk;
    private MicPipe mic;
    private CameraPipe cam;
    private CapturePipe cap;
    private String spkSig = "", micSig = "", camSig = "", capSig = "";
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wifiLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAll();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_MUTE_MIC.equals(action)) {
            Stats.muteMic = !Stats.muteMic;
            if (Stats.running) goForeground();   // refresh the notification button
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_PROJ_DATA)) takeProjection(intent);
        applyPrefs();
        goForeground();
        // NOT_STICKY on purpose: a restart in the background isn't allowed the mic or camera.
        return START_NOT_STICKY;
    }

    /**
     * Android 14 wants the service in the foreground with the mediaProjection type *before*
     * getMediaProjection, and the consent is single use: keep the projection until stopped.
     */
    @SuppressWarnings("deprecation")   // getParcelableExtra(String): the typed one is API 33+
    private void takeProjection(Intent intent) {
        Intent data = intent.getParcelableExtra(EXTRA_PROJ_DATA);
        int code = intent.getIntExtra(EXTRA_PROJ_CODE, 0);
        if (data == null) return;
        stopProjection();
        projectionPending = true;
        goForeground();
        projectionPending = false;
        try {
            MediaProjection mp = getSystemService(MediaProjectionManager.class).getMediaProjection(code, data);
            if (mp == null) return;
            mp.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {             // ended from the status bar / lock screen
                    if (Stats.projection == mp) Stats.projection = null;
                }
            }, new Handler(Looper.getMainLooper()));
            Stats.projection = mp;
        } catch (RuntimeException e) {
            Stats.capError = String.valueOf(e.getMessage());
        }
    }

    private boolean projectionPending;

    private static void stopProjection() {
        MediaProjection mp = Stats.projection;
        Stats.projection = null;
        if (mp != null) mp.stop();
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        PendingIntent stopPi = PendingIntent.getService(this, 0,
                new Intent(this, BridgeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent mutePi = PendingIntent.getService(this, 2,
                new Intent(this, BridgeService.class).setAction(ACTION_MUTE_MIC),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        StringBuilder what = new StringBuilder();
        if (spk != null) what.append(getString(R.string.notif_audio));
        if (mic != null) {
            if (what.length() > 0) what.append(" · ");
            what.append(getString(Stats.muteMic ? R.string.notif_mic_muted : R.string.notif_mic));
        }
        if (cam != null) {
            if (what.length() > 0) what.append(" · ");
            what.append(getString(R.string.notif_camera));
        }
        if (cap != null) {
            if (what.length() > 0) what.append(" · ");
            what.append(getString(R.string.notif_phone_audio));
        }
        if (what.length() == 0) what.append(getString(R.string.notif_idle));

        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(what)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setColor(0xFF5B8CFF)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (mic != null) {
            nb.addAction(new Notification.Action.Builder(null,
                    getString(Stats.muteMic ? R.string.unmute_mic : R.string.mute_mic), mutePi).build());
        }
        nb.addAction(new Notification.Action.Builder(null, getString(R.string.stop), stopPi).build());
        Notification n = nb.build();

        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (mic != null) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (cam != null && Build.VERSION.SDK_INT >= 30) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (Stats.projection != null || projectionPending) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIF_ID, n, types);
        } else {
            startForeground(NOTIF_ID, n);
        }

        if (wl == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbaudio:bridge");
            wl.acquire();
        }
    }

    private void applyPrefs() {
        SharedPreferences p = Cfg.prefs(this);
        Stats.spkGain = p.getInt(Cfg.K_SPK_VOL, 100) / 100f;
        Stats.micGain = p.getInt(Cfg.K_MIC_VOL, 100) / 100f;
        Stats.camFront = p.getBoolean(Cfg.K_CAM_FRONT, false);
        Cfg.Link link = new Cfg.Link(p);
        String ls = "/" + link.sig();
        applyWifiLock(link.mode == Cfg.LINK_WIFI);

        // --- output
        boolean spkOn = p.getBoolean(Cfg.K_SPK, true);
        int buf = p.getInt(Cfg.K_BUF, Cfg.DEFAULT_BUF_MS);
        int spkPort = p.getInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT);
        String sig = spkOn + "/" + buf + "/" + spkPort + ls;
        if (!sig.equals(spkSig) || (spkOn && spk == null)) {
            if (spk != null) { spk.shutdown(); spk = null; }
            Stats.spkBytes = 0;
            Stats.spkDroppedMs = 0;
            Stats.spkUnderruns = 0;
            Stats.spkError = "";
            if (spkOn) {
                spk = new SpeakerPipe(getApplicationContext(), buf, spkPort, link);
                spk.start();
            }
            spkSig = sig;
        }

        // --- mic
        boolean micOn = p.getBoolean(Cfg.K_MIC, true);
        boolean micOk = has(Manifest.permission.RECORD_AUDIO);
        int src = p.getInt(Cfg.K_SRC, Cfg.DEFAULT_SRC);
        int micBuf = p.getInt(Cfg.K_MIC_BUF, Cfg.DEFAULT_MIC_BUF_MS);
        int micPort = p.getInt(Cfg.K_MIC_PORT, Cfg.DEFAULT_MIC_PORT);
        sig = micOn + "/" + micOk + "/" + src + "/" + micBuf + "/" + micPort + ls;
        if (!sig.equals(micSig) || (micOn && micOk && mic == null)) {
            if (mic != null) { mic.shutdown(); mic = null; }
            Stats.micBytes = 0;
            Stats.micError = "";
            if (micOn && micOk) {
                mic = new MicPipe(getApplicationContext(), src, micBuf, micPort, link);
                mic.start();
            } else if (micOn) {
                Stats.micError = getString(R.string.err_mic_permission);
            }
            micSig = sig;
        }

        // --- camera
        boolean camOn = p.getBoolean(Cfg.K_CAM, false);
        boolean camOk = has(Manifest.permission.CAMERA);
        int camPort = p.getInt(Cfg.K_CAM_PORT, Cfg.DEFAULT_CAM_PORT);
        String size = p.getString(Cfg.K_CAM_SIZE, Cfg.DEFAULT_CAM_SIZE);
        int fps = p.getInt(Cfg.K_CAM_FPS, Cfg.DEFAULT_CAM_FPS);
        int quality = p.getInt(Cfg.K_CAM_QUALITY, Cfg.DEFAULT_CAM_QUALITY);
        int rot = p.getInt(Cfg.K_CAM_ROT, -1);
        boolean mirror = p.getBoolean(Cfg.K_CAM_MIRROR, false);
        sig = camOn + "/" + camOk + "/" + camPort + "/" + size + "/" + fps + "/" + quality + "/" + rot + "/" + mirror + ls;
        if (!sig.equals(camSig) || (camOn && camOk && cam == null)) {
            if (cam != null) { cam.shutdown(); cam = null; }
            Stats.camBytes = 0;
            Stats.camFrames = 0;
            Stats.camError = "";
            Stats.camInfo = "";
            if (camOn && camOk) {
                cam = new CameraPipe(getApplicationContext(), camPort, size, fps, quality, rot, mirror, link);
                cam.start();
            } else if (camOn) {
                Stats.camError = getString(R.string.err_cam_permission);
            }
            camSig = sig;
        }

        // --- phone sound -> PC
        boolean capOn = p.getBoolean(Cfg.K_CAP, false);
        boolean capOk = has(Manifest.permission.RECORD_AUDIO);
        sig = capOn + "/" + capOk + ls;
        if (!sig.equals(capSig) || (capOn && capOk && cap == null)) {
            if (cap != null) { cap.shutdown(); cap = null; }
            Stats.capBytes = 0;
            Stats.capError = "";
            if (capOn && capOk) {
                cap = new CapturePipe(getApplicationContext(), Cfg.AUDIO_PORT, link);
                cap.start();
            } else if (capOn) {
                Stats.capError = getString(R.string.err_mic_permission);
            }
            capSig = sig;
        }
        if (!capOn) stopProjection();   // don't keep the "casting" indicator on for nothing
        Stats.running = true;
    }

    /** Wi-Fi power saving parks the radio between beacons: 100+ ms of jitter. Off while streaming. */
    @SuppressWarnings("deprecation")   // HIGH_PERF: what's left below Android 10
    private void applyWifiLock(boolean want) {
        if (want && wifiLock == null) {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            if (wm == null) return;
            wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "usbaudio:wifi");
            wifiLock.acquire();
        } else if (!want && wifiLock != null) {
            if (wifiLock.isHeld()) wifiLock.release();
            wifiLock = null;
        }
    }

    private void stopPipes() {
        if (spk != null) { spk.shutdown(); spk = null; }
        if (mic != null) { mic.shutdown(); mic = null; }
        if (cam != null) { cam.shutdown(); cam = null; }
        if (cap != null) { cap.shutdown(); cap = null; }
        spkSig = micSig = camSig = capSig = "";
    }

    private void stopAll() {
        stopPipes();
        Stats.running = false;
        Stats.spkConnected = false;
        Stats.micConnected = false;
        Stats.camConnected = false;
        Stats.capConnected = false;
        stopProjection();
        applyWifiLock(false);
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
