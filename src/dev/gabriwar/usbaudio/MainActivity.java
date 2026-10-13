package dev.gabriwar.usbaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.widget.AdapterView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SeekBar.OnSeekBarChangeListener, AdapterView.OnItemSelectedListener {
    // MediaRecorder.AudioSource: MIC, VOICE_COMMUNICATION, UNPROCESSED, VOICE_RECOGNITION, CAMCORDER
    private static final int[] SRC_IDS = {1, 7, 9, 6, 5};
    private static final String[] SRC_NAMES = {
            "Microfone (padrao)",
            "Voz (cancela eco e ruido)",
            "Sem processamento",
            "Reconhecimento de voz",
            "Camcorder"};

    private SharedPreferences prefs;
    private Switch swSpk, swMic, swAwake;
    private EditText etSpkPort, etMicPort;
    private Spinner spSrc, spOut, spIn;
    private AudioManager am;
    private DeviceWatcher watcher;
    private boolean scoOn;
    private final List<AudioDeviceInfo> outDevs = new ArrayList<>();
    private final List<AudioDeviceInfo> inDevs = new ArrayList<>();
    private SeekBar sbBuf, sbMicBuf, sbSpkVol, sbMicVol;
    private TextView tvBuf, tvMicBuf, tvSpkVol, tvMicVol, tvSpk, tvMic, tvBanner;
    private View dotSpk, dotMic;
    private SpectrogramView svSpk, svMic;
    private Button btn, btnMuteSpk, btnMuteMic;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastSpkBytes, lastMicBytes, lastT;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    /** Tela nao apaga enquanto o app estiver aberto (o audio em si segue com a tela apagada). */
    private void applyAwake() {
        if (swAwake.isChecked()) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------- dispositivos do celular ----------

    private static String devKey(AudioDeviceInfo d) {
        return d.getType() + ":" + d.getProductName() + ":" + d.getAddress();
    }

    /** Nome amigavel; null = tipo que nao faz sentido oferecer. */
    private static String devName(AudioDeviceInfo d, boolean input) {
        String n = String.valueOf(d.getProductName());
        String addr = d.getAddress() == null ? "" : d.getAddress();
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Alto-falante do celular";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Microfone do celular" + (addr.isEmpty() ? "" : " (" + addr + ")");
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth: " + n;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth modo ligacao: " + n + (input ? " (qualidade cai)" : "");
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return input ? "Mic do fone com fio" : "Fone com fio";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Fone com fio";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB: " + n;
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "Bluetooth LE: " + n;
            default: return null;
        }
    }

    private void refreshDevices() {
        fillDevices(spOut, outDevs, am.getDevices(AudioManager.GET_DEVICES_OUTPUTS), false,
                prefs.getString(Cfg.K_OUT_DEV, ""));
        fillDevices(spIn, inDevs, am.getDevices(AudioManager.GET_DEVICES_INPUTS), true,
                prefs.getString(Cfg.K_IN_DEV, ""));
        Stats.outDevice = outDevs.get(Math.max(0, spOut.getSelectedItemPosition()));
        Stats.inDevice = inDevs.get(Math.max(0, spIn.getSelectedItemPosition()));
    }

    private void fillDevices(Spinner sp, List<AudioDeviceInfo> list, AudioDeviceInfo[] devs,
                             boolean input, String savedKey) {
        list.clear();
        List<String> names = new ArrayList<>();
        list.add(null);
        names.add("Automatico (padrao do Android)");
        int sel = 0;
        for (AudioDeviceInfo d : devs) {
            String nm = devName(d, input);
            if (nm == null || names.contains(nm)) continue;
            list.add(d);
            names.add(nm);
            if (devKey(d).equals(savedKey)) sel = list.size() - 1;
        }
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(sel, false);
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
        if (parent == spOut) {
            AudioDeviceInfo d = outDevs.get(pos);
            Stats.outDevice = d;
            prefs.edit().putString(Cfg.K_OUT_DEV, d == null ? "" : devKey(d)).apply();
        } else if (parent == spIn) {
            AudioDeviceInfo d = inDevs.get(pos);
            Stats.inDevice = d;
            prefs.edit().putString(Cfg.K_IN_DEV, d == null ? "" : devKey(d)).apply();
            applySco(d);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {}

    /** Mic do fone BT so funciona com o fone em modo ligacao (SCO). */
    private void applySco(AudioDeviceInfo in) {
        if (Build.VERSION.SDK_INT < 31) return;
        boolean want = in != null && in.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        if (want) {
            for (AudioDeviceInfo c : am.getAvailableCommunicationDevices()) {
                if (c.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    scoOn = am.setCommunicationDevice(c);
                    break;
                }
            }
        } else if (scoOn) {
            am.clearCommunicationDevice();
            scoOn = false;
        }
    }

    private LinearLayout statusRow(View dot, TextView text, Button mute) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(dot, new LinearLayout.LayoutParams(dp(16), dp(16)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        tp.rightMargin = dp(8);
        text.setTextSize(14);
        row.addView(text, tp);
        row.addView(mute, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
        return row;
    }

    private static void paint(View dot, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        dot.setBackground(g);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Cfg.prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));

        root.addView(label("USB Audio Bridge", 24, true));
        root.addView(label("Audio do PC e microfone pelo cabo USB. Formato fixo: 48 kHz, 16 bit.", 13, false));

        swSpk = new Switch(this);
        swSpk.setText("Saida: PC → celular");
        swSpk.setChecked(prefs.getBoolean(Cfg.K_SPK, true));
        root.addView(swSpk);

        swMic = new Switch(this);
        swMic.setText("Microfone: celular → PC");
        swMic.setChecked(prefs.getBoolean(Cfg.K_MIC, true));
        root.addView(swMic);

        swAwake = new Switch(this);
        swAwake.setText("Manter tela ligada");
        swAwake.setChecked(prefs.getBoolean(Cfg.K_AWAKE, true));
        swAwake.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(Cfg.K_AWAKE, on).apply();
            applyAwake();
        });
        root.addView(swAwake);
        applyAwake();

        am = getSystemService(AudioManager.class);
        root.addView(label("Saida do celular", 14, true));
        spOut = new Spinner(this);
        root.addView(spOut);
        root.addView(label("Entrada do celular", 14, true));
        spIn = new Spinner(this);
        root.addView(spIn);
        refreshDevices();
        spOut.setOnItemSelectedListener(this);
        spIn.setOnItemSelectedListener(this);

        root.addView(label("Fonte do microfone", 14, true));
        spSrc = new Spinner(this);
        spSrc.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, SRC_NAMES));
        int savedSrc = prefs.getInt(Cfg.K_SRC, Cfg.DEFAULT_SRC);
        for (int i = 0; i < SRC_IDS.length; i++) if (SRC_IDS[i] == savedSrc) spSrc.setSelection(i);
        root.addView(spSrc);

        tvBuf = label("", 14, true);
        root.addView(tvBuf);
        sbBuf = new SeekBar(this);
        sbBuf.setMax(190);                           // 10..200 ms
        sbBuf.setProgress(prefs.getInt(Cfg.K_BUF, Cfg.DEFAULT_BUF_MS) - 10);
        sbBuf.setOnSeekBarChangeListener(this);
        root.addView(sbBuf);
        tvMicBuf = label("", 14, true);
        root.addView(tvMicBuf);
        sbMicBuf = new SeekBar(this);
        sbMicBuf.setMax(190);                        // 10..200 ms
        sbMicBuf.setProgress(prefs.getInt(Cfg.K_MIC_BUF, Cfg.DEFAULT_MIC_BUF_MS) - 10);
        sbMicBuf.setOnSeekBarChangeListener(this);
        root.addView(sbMicBuf);
        showBuf();

        tvSpkVol = label("", 14, true);
        root.addView(tvSpkVol);
        sbSpkVol = new SeekBar(this);
        sbSpkVol.setMax(200);
        sbSpkVol.setProgress(prefs.getInt(Cfg.K_SPK_VOL, 100));
        sbSpkVol.setOnSeekBarChangeListener(this);
        root.addView(sbSpkVol);

        tvMicVol = label("", 14, true);
        root.addView(tvMicVol);
        sbMicVol = new SeekBar(this);
        sbMicVol.setMax(200);
        sbMicVol.setProgress(prefs.getInt(Cfg.K_MIC_VOL, 100));
        sbMicVol.setOnSeekBarChangeListener(this);
        root.addView(sbMicVol);
        applyVolumes(false);

        root.addView(label("Portas (mude se estiverem em uso; o PC tem que usar as mesmas)", 14, true));
        LinearLayout ports = new LinearLayout(this);
        ports.setOrientation(LinearLayout.HORIZONTAL);
        etSpkPort = portField(prefs.getInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT), "saida");
        etMicPort = portField(prefs.getInt(Cfg.K_MIC_PORT, Cfg.DEFAULT_MIC_PORT), "mic");
        ports.addView(etSpkPort, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ports.addView(etMicPort, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(ports);

        btn = new Button(this);
        btn.setOnClickListener(v -> onToggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(20);
        root.addView(btn, lp);

        root.addView(label("Estado", 14, true));
        tvBanner = new TextView(this);
        tvBanner.setTextSize(20);
        tvBanner.setTypeface(Typeface.DEFAULT_BOLD);
        tvBanner.setPadding(0, dp(4), 0, dp(10));
        root.addView(tvBanner);

        dotSpk = new View(this);
        tvSpk = new TextView(this);
        btnMuteSpk = new Button(this);
        btnMuteSpk.setOnClickListener(v -> Stats.muteSpk = !Stats.muteSpk);
        root.addView(statusRow(dotSpk, tvSpk, btnMuteSpk));

        dotMic = new View(this);
        tvMic = new TextView(this);
        btnMuteMic = new Button(this);
        btnMuteMic.setOnClickListener(v -> Stats.muteMic = !Stats.muteMic);
        root.addView(statusRow(dotMic, tvMic, btnMuteMic));

        root.addView(label("Espectrograma \u00b7 entrada (microfone)", 13, true));
        svMic = new SpectrogramView(this, Stats.micTap);
        root.addView(svMic, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(130)));

        root.addView(label("Espectrograma \u00b7 saida (PC \u2192 celular)", 13, true));
        svSpk = new SpectrogramView(this, Stats.spkTap);
        root.addView(svSpk, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(130)));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    /** Permite ao PC disparar tudo por: am start -n ... --ez autostart true --ez spk true ... */
    private void handleIntent(Intent i) {
        if (i == null || !i.getBooleanExtra("autostart", false)) return;
        SharedPreferences.Editor e = prefs.edit();
        if (i.hasExtra("spk")) e.putBoolean(Cfg.K_SPK, i.getBooleanExtra("spk", true));
        if (i.hasExtra("mic")) e.putBoolean(Cfg.K_MIC, i.getBooleanExtra("mic", true));
        if (i.hasExtra("buf")) e.putInt(Cfg.K_BUF, clamp(i.getIntExtra("buf", Cfg.DEFAULT_BUF_MS)));
        if (i.hasExtra("src")) e.putInt(Cfg.K_SRC, i.getIntExtra("src", Cfg.DEFAULT_SRC));
        if (i.hasExtra("spkport")) e.putInt(Cfg.K_SPK_PORT, i.getIntExtra("spkport", Cfg.DEFAULT_SPK_PORT));
        if (i.hasExtra("micport")) e.putInt(Cfg.K_MIC_PORT, i.getIntExtra("micport", Cfg.DEFAULT_MIC_PORT));
        if (i.hasExtra("micbuf")) e.putInt(Cfg.K_MIC_BUF, clamp(i.getIntExtra("micbuf", Cfg.DEFAULT_MIC_BUF_MS)));
        e.apply();
        // reflete na tela
        swSpk.setChecked(prefs.getBoolean(Cfg.K_SPK, true));
        swMic.setChecked(prefs.getBoolean(Cfg.K_MIC, true));
        sbBuf.setProgress(prefs.getInt(Cfg.K_BUF, Cfg.DEFAULT_BUF_MS) - 10);
        sbMicBuf.setProgress(prefs.getInt(Cfg.K_MIC_BUF, Cfg.DEFAULT_MIC_BUF_MS) - 10);
        etSpkPort.setText(String.valueOf(prefs.getInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT)));
        etMicPort.setText(String.valueOf(prefs.getInt(Cfg.K_MIC_PORT, Cfg.DEFAULT_MIC_PORT)));
        int src = prefs.getInt(Cfg.K_SRC, Cfg.DEFAULT_SRC);
        for (int k = 0; k < SRC_IDS.length; k++) if (SRC_IDS[k] == src) spSrc.setSelection(k);
        startBridge(false);
    }

    @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
        if (sb == sbBuf || sb == sbMicBuf) showBuf(); else applyVolumes(user);
    }

    /** Volume vale na hora (o pipe le o ganho a cada 5 ms); salva quando veio do usuario. */
    private void applyVolumes(boolean save) {
        int sv = sbSpkVol.getProgress(), mv = sbMicVol.getProgress();
        Stats.spkGain = sv / 100f;
        Stats.micGain = mv / 100f;
        tvSpkVol.setText("Volume da saida: " + sv + "%");
        tvMicVol.setText("Volume do microfone: " + mv + "%");
        if (save) prefs.edit().putInt(Cfg.K_SPK_VOL, sv).putInt(Cfg.K_MIC_VOL, mv).apply();
    }
    @Override public void onStartTrackingTouch(SeekBar sb) {}
    /** Buffer so muda recriando os pipes: aplica ao soltar o slider, se estiver rodando. */
    @Override public void onStopTrackingTouch(SeekBar sb) {
        if (sb != sbBuf && sb != sbMicBuf) return;
        prefs.edit()
                .putInt(Cfg.K_BUF, sbBuf.getProgress() + 10)
                .putInt(Cfg.K_MIC_BUF, sbMicBuf.getProgress() + 10)
                .apply();
        if (Stats.running) startBridge(true);
    }

    private EditText portField(int value, String hint) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("porta " + hint);
        e.setText(String.valueOf(value));
        return e;
    }

    /** Porta valida (1024..65535) ou o valor antigo. */
    private static int port(EditText e, int fallback) {
        try {
            int p = Integer.parseInt(e.getText().toString().trim());
            return (p >= 1024 && p <= 65535) ? p : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int clamp(int ms) {
        return Math.max(10, Math.min(200, ms));
    }

    private void showBuf() {
        tvBuf.setText("Buffer da saida: " + (sbBuf.getProgress() + 10) + " ms  (menor = menos delay)");
        tvMicBuf.setText("Buffer do microfone: " + (sbMicBuf.getProgress() + 10) + " ms");
    }

    private void onToggle() {
        if (Stats.running) {
            startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP));
        } else {
            startBridge(true);
        }
    }

    private void startBridge(boolean saveFromUi) {
        if (saveFromUi) {
            prefs.edit()
                    .putBoolean(Cfg.K_SPK, swSpk.isChecked())
                    .putBoolean(Cfg.K_MIC, swMic.isChecked())
                    .putInt(Cfg.K_BUF, sbBuf.getProgress() + 10)
                    .putInt(Cfg.K_MIC_BUF, sbMicBuf.getProgress() + 10)
                    .putInt(Cfg.K_SPK_PORT, port(etSpkPort, prefs.getInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT)))
                    .putInt(Cfg.K_MIC_PORT, port(etMicPort, prefs.getInt(Cfg.K_MIC_PORT, Cfg.DEFAULT_MIC_PORT)))
                    .putInt(Cfg.K_SRC, SRC_IDS[spSrc.getSelectedItemPosition()])
                    .apply();
        }
        startForegroundService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_START));
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastT = System.currentTimeMillis();
        lastSpkBytes = Stats.spkBytes;
        lastMicBytes = Stats.micBytes;
        ui.post(tick);
        ui.post(specTick);
        if (watcher == null) watcher = new DeviceWatcher(this::refreshDevices);
        am.registerAudioDeviceCallback(watcher, ui);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(specTick);
        if (watcher != null) am.unregisterAudioDeviceCallback(watcher);
        super.onPause();
    }

    private final Runnable tick = this::onTick;
    private final Runnable specTick = this::onSpecTick;

    private void onSpecTick() {
        svMic.update();
        svSpk.update();
        ui.postDelayed(specTick, 33);
    }

    private void onTick() {
        {
            long now = System.currentTimeMillis();
            double dt = Math.max(1, now - lastT) / 1000.0;
            double spkRate = (Stats.spkBytes - lastSpkBytes) / dt / 1024.0;
            double micRate = (Stats.micBytes - lastMicBytes) / dt / 1024.0;
            lastT = now;
            lastSpkBytes = Stats.spkBytes;
            lastMicBytes = Stats.micBytes;

            final int GREEN = 0xFF43A047, AMBER = 0xFFFFB300, RED = 0xFFE53935, GRAY = 0xFF757575;
            boolean run = Stats.running;
            btn.setText(run ? "Parar" : "Iniciar");

            boolean spkOn = swSpk.isChecked(), micOn = swMic.isChecked();

            // --- saida
            if (!run || !spkOn) {
                paint(dotSpk, GRAY);
                tvSpk.setText(spkOn ? "Saida: parada" : "Saida: desligada");
            } else if (Stats.spkConnected) {
                paint(dotSpk, Stats.muteSpk ? RED : GREEN);
                tvSpk.setText(String.format(Locale.US,
                        "Saida: CONECTADA%s\nfila %d ms \u00b7 %.0f KB/s \u00b7 underruns %d \u00b7 cortou %d ms",
                        Stats.muteSpk ? " (MUDA)" : "", Stats.spkQueuedMs, spkRate, Stats.spkUnderruns, Stats.spkDroppedMs));
            } else {
                paint(dotSpk, AMBER);
                tvSpk.setText("Saida: esperando o PC"
                        + (Stats.spkError.isEmpty() ? "" : "\n" + Stats.spkError));
            }
            btnMuteSpk.setText(Stats.muteSpk ? "Ativar som" : "Mutar");

            // --- microfone
            if (!run || !micOn) {
                paint(dotMic, GRAY);
                tvMic.setText(micOn ? "Microfone: parado" : "Microfone: desligado");
            } else if (Stats.micConnected) {
                double db = Stats.micPeak <= 0 ? -99 : 20 * Math.log10(Stats.micPeak / 32768.0);
                paint(dotMic, Stats.muteMic ? RED : GREEN);
                tvMic.setText(String.format(Locale.US,
                        "Microfone: CONECTADO%s\n%.0f KB/s \u00b7 pico %.0f dB",
                        Stats.muteMic ? " (MUDO)" : "", micRate, db));
            } else {
                paint(dotMic, AMBER);
                tvMic.setText("Microfone: esperando o PC"
                        + (Stats.micError.isEmpty() ? "" : "\n" + Stats.micError));
            }
            btnMuteMic.setText(Stats.muteMic ? "Ativar mic" : "Mutar");

            // --- banner geral
            boolean spkOk = !spkOn || Stats.spkConnected;
            boolean micOk = !micOn || Stats.micConnected;
            boolean any = (spkOn && Stats.spkConnected) || (micOn && Stats.micConnected);
            if (!run) {
                tvBanner.setText("\u25CF PARADO");
                tvBanner.setTextColor(GRAY);
            } else if (spkOk && micOk && any) {
                tvBanner.setText("\u25CF CONECTADO AO PC");
                tvBanner.setTextColor(GREEN);
            } else if (any) {
                tvBanner.setText("\u25CF PARCIAL");
                tvBanner.setTextColor(AMBER);
            } else {
                tvBanner.setText("\u25CF SEM CONEXAO COM O PC");
                tvBanner.setTextColor(RED);
            }

            ui.postDelayed(tick, 250);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
    }
}
