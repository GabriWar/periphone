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

/** Dono dos dois pipes. Roda em primeiro plano pra sobreviver a tela apagada. */
public class BridgeService extends Service {
    static final String ACTION_START = "dev.gabriwar.usbaudio.START";
    static final String ACTION_STOP = "dev.gabriwar.usbaudio.STOP";
    private static final String CHANNEL = "bridge";
    private static final int NOTIF_ID = 1;

    private SpeakerPipe spk;
    private MicPipe mic;
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
        goForeground();
        applyPrefs();
        // NOT_STICKY de proposito: um restart em segundo plano nao tem direito ao microfone.
        return START_NOT_STICKY;
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Ponte de audio", NotificationManager.IMPORTANCE_LOW));

        Intent stop = new Intent(this, BridgeService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 0, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("USB Audio Bridge")
                .setContentText("Audio via cabo USB ativo")
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Parar", stopPi).build())
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (hasMicPermission()) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
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
        stopPipes();

        Stats.spkBytes = 0;
        Stats.micBytes = 0;
        Stats.spkDroppedMs = 0;
        Stats.spkUnderruns = 0;
        Stats.spkError = "";
        Stats.micError = "";

        Stats.spkGain = p.getInt(Cfg.K_SPK_VOL, 100) / 100f;
        Stats.micGain = p.getInt(Cfg.K_MIC_VOL, 100) / 100f;

        if (p.getBoolean(Cfg.K_SPK, true)) {
            spk = new SpeakerPipe(p.getInt(Cfg.K_BUF, Cfg.DEFAULT_BUF_MS),
                    p.getInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT));
            spk.start();
        }
        if (p.getBoolean(Cfg.K_MIC, true)) {
            if (hasMicPermission()) {
                mic = new MicPipe(p.getInt(Cfg.K_SRC, Cfg.DEFAULT_SRC),
                        p.getInt(Cfg.K_MIC_BUF, Cfg.DEFAULT_MIC_BUF_MS),
                        p.getInt(Cfg.K_MIC_PORT, Cfg.DEFAULT_MIC_PORT));
                mic.start();
            } else {
                Stats.micError = "permissao de microfone negada";
            }
        }
        Stats.running = true;
    }

    private void stopPipes() {
        if (spk != null) { spk.shutdown(); spk = null; }
        if (mic != null) { mic.shutdown(); mic = null; }
    }

    private void stopAll() {
        stopPipes();
        Stats.running = false;
        Stats.spkConnected = false;
        Stats.micConnected = false;
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
