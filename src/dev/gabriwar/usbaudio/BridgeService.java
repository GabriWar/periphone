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
import android.os.Build;
import android.os.IBinder;
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
    private static final String CHANNEL = "bridge";
    private static final int NOTIF_ID = 1;

    private SpeakerPipe spk;
    private MicPipe mic;
    private CameraPipe cam;
    private String spkSig = "", micSig = "", camSig = "";
    private PowerManager.WakeLock wl;

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
        applyPrefs();
        goForeground();
        // NOT_STICKY on purpose: a restart in the background isn't allowed the mic or camera.
        return START_NOT_STICKY;
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

        // --- output
        boolean spkOn = p.getBoolean(Cfg.K_SPK, true);
        int buf = p.getInt(Cfg.K_BUF, Cfg.DEFAULT_BUF_MS);
        int spkPort = p.getInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT);
        String sig = spkOn + "/" + buf + "/" + spkPort;
        if (!sig.equals(spkSig) || (spkOn && spk == null)) {
            if (spk != null) { spk.shutdown(); spk = null; }
            Stats.spkBytes = 0;
            Stats.spkDroppedMs = 0;
            Stats.spkUnderruns = 0;
            Stats.spkError = "";
            if (spkOn) {
                spk = new SpeakerPipe(getApplicationContext(), buf, spkPort);
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
        sig = micOn + "/" + micOk + "/" + src + "/" + micBuf + "/" + micPort;
        if (!sig.equals(micSig) || (micOn && micOk && mic == null)) {
            if (mic != null) { mic.shutdown(); mic = null; }
            Stats.micBytes = 0;
            Stats.micError = "";
            if (micOn && micOk) {
                mic = new MicPipe(getApplicationContext(), src, micBuf, micPort);
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
        sig = camOn + "/" + camOk + "/" + camPort + "/" + size + "/" + fps + "/" + quality + "/" + rot + "/" + mirror;
        if (!sig.equals(camSig) || (camOn && camOk && cam == null)) {
            if (cam != null) { cam.shutdown(); cam = null; }
            Stats.camBytes = 0;
            Stats.camFrames = 0;
            Stats.camError = "";
            Stats.camInfo = "";
            if (camOn && camOk) {
                cam = new CameraPipe(getApplicationContext(), camPort, size, fps, quality, rot, mirror);
                cam.start();
            } else if (camOn) {
                Stats.camError = getString(R.string.err_cam_permission);
            }
            camSig = sig;
        }
        Stats.running = true;
    }

    private void stopPipes() {
        if (spk != null) { spk.shutdown(); spk = null; }
        if (mic != null) { mic.shutdown(); mic = null; }
        if (cam != null) { cam.shutdown(); cam = null; }
        spkSig = micSig = camSig = "";
    }

    private void stopAll() {
        stopPipes();
        Stats.running = false;
        Stats.spkConnected = false;
        Stats.micConnected = false;
        Stats.camConnected = false;
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
