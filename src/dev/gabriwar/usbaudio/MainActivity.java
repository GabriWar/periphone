package dev.gabriwar.usbaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    // palette
    static final int BG = 0xFF0F1115, CARD = 0xFF1A1D23, CARD_HI = 0xFF242830, STROKE = 0xFF2C313A;
    static final int TEXT = 0xFFECEFF4, TEXT2 = 0xFF9AA3AE, ACCENT = 0xFF5B8CFF;
    static final int GREEN = 0xFF34C759, AMBER = 0xFFFFB020, RED = 0xFFFF453A, GRAY = 0xFF6B7280;

    // MediaRecorder.AudioSource: MIC, VOICE_COMMUNICATION, UNPROCESSED, VOICE_RECOGNITION, CAMCORDER
    private static final int[] SRC_IDS = {1, 7, 9, 6, 5};
    private static final int[] SRC_NAMES = {R.string.src_mic, R.string.src_voice, R.string.src_raw,
            R.string.src_recognition, R.string.src_camcorder};
    private static final String[] CAM_SIZES = {"1920x1080", "1280x720", "960x540", "640x480"};
    private static final int[] CAM_FPS = {15, 24, 30, 60};
    private static final int[] CAM_QUALITY = {R.string.quality_low, R.string.quality_medium, R.string.quality_high};
    private static final int[] CAM_ROTS = {-1, 0, 90, 180, 270};
    private static final int[] PRESET_NAMES = {R.string.preset_lowest, R.string.preset_balanced, R.string.preset_stable};
    private static final int[] PRESET_DESC = {R.string.preset_lowest_desc, R.string.preset_balanced_desc,
            R.string.preset_stable_desc};
    private static final int REQ_MIC = 1, REQ_NOTIF = 2, REQ_CAM = 3;

    private SharedPreferences prefs;
    private AudioManager am;
    private DeviceWatcher watcher;
    private boolean scoOn;
    private final List<AudioDeviceInfo> outDevs = new ArrayList<>();
    private final List<AudioDeviceInfo> inDevs = new ArrayList<>();
    private final List<String> outNames = new ArrayList<>(), inNames = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastSpkBytes, lastMicBytes, lastCamBytes, lastCamFrames, lastT;
    private boolean resumed, reflecting;

    // hero
    private View heroDot;
    private TextView heroTitle, heroHint;
    private Button btnStart;
    // output
    private Switch swSpk;
    private View spkDot;
    private TextView spkStatus, spkDetail, spkVolText, spkBtTip;
    private Button btnMuteSpk;
    private SeekBar sbSpkVol;
    private Spinner spOut;
    private SpectrogramView svSpk;
    // mic
    private Switch swMic;
    private View micDot;
    private TextView micStatus, micDetail, micVolText;
    private Button btnMuteMic;
    private SeekBar sbMicVol;
    private LevelView micLevel;
    private Spinner spIn, spSrc;
    private SpectrogramView svMic;
    // camera
    private Switch swCam, swMirror;
    private View camDot;
    private TextView camStatus, camDetail;
    private LinearLayout camBody;
    private TextView[] facingSeg;
    private TextureView preview;
    private FrameLayout previewBox;
    private Surface previewSurface;
    private SurfaceTexture previewTexture;
    private int previewW = 640, previewH = 360;
    private Spinner spCamSize, spCamFps, spCamQuality, spCamRot;
    // latency
    private TextView[] presetSeg;
    private TextView presetDesc, tvBuf, tvMicBuf;
    private SeekBar sbBuf, sbMicBuf;
    private LinearLayout fineBody;
    // advanced
    private Switch swAwake;
    private EditText etSpkPort, etMicPort, etCamPort;
    private LinearLayout advBody;
    private TextView advArrow;

    // ------------------------------------------------------------------ helpers

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static void paintDot(View dot, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        dot.setBackground(g);
    }

    private TextView text(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView fieldLabel(int res) {
        TextView t = text(getString(res), 13, TEXT2, false);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, dp(18)));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        parent.addView(c, p);
        return c;
    }

    /** Card title row: icon, title, optional switch on the right. */
    private LinearLayout cardHeader(LinearLayout card, int icon, int title, Switch sw) {
        LinearLayout r = row();
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(ACCENT);
        r.addView(iv, lp(dp(22), dp(22)));
        TextView t = text(getString(title), 17, TEXT, true);
        LinearLayout.LayoutParams tp = weight(1);
        tp.leftMargin = dp(10);
        r.addView(t, tp);
        if (sw != null) r.addView(sw);
        card.addView(r, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return r;
    }

    /** Status line: colored dot + bold text; detail line below in grey. */
    private void statusLines(LinearLayout card, View dot, TextView status, TextView detail) {
        LinearLayout r = row();
        r.setPadding(0, dp(6), 0, 0);
        r.addView(dot, lp(dp(10), dp(10)));
        LinearLayout.LayoutParams tp = weight(1);
        tp.leftMargin = dp(10);
        r.addView(status, tp);
        card.addView(r);
        detail.setPadding(dp(20), dp(2), 0, 0);
        card.addView(detail);
    }

    private Button pillButton() {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        b.setMinWidth(dp(96));
        b.setMinimumWidth(dp(96));
        b.setStateListAnimator(null);
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    private void stylePill(Button b, int fill) {
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(fill, dp(22)), null));
    }

    /** Segmented control; returns the segments so the caller can highlight the selection. */
    private TextView[] segmented(LinearLayout parent, int[] labels, View.OnClickListener l) {
        LinearLayout r = row();
        r.setBackground(round(CARD_HI, dp(12)));
        r.setPadding(dp(3), dp(3), dp(3), dp(3));
        TextView[] segs = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView s = text(getString(labels[i]), 14, TEXT, true);
            s.setGravity(Gravity.CENTER);
            s.setTag(i);
            s.setOnClickListener(l);
            s.setClickable(true);
            r.addView(s, new LinearLayout.LayoutParams(0, dp(38), 1f));
            segs[i] = s;
        }
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        parent.addView(r, p);
        return segs;
    }

    private void selectSeg(TextView[] segs, int sel) {
        for (int i = 0; i < segs.length; i++) {
            boolean on = i == sel;
            segs[i].setBackground(on ? round(ACCENT, dp(10)) : null);
            segs[i].setTextColor(on ? 0xFFFFFFFF : TEXT2);
        }
    }

    private Spinner spinner(LinearLayout parent, int label, List<String> items, int sel,
                            final IntCallback onPick) {
        parent.addView(fieldLabel(label));
        final Spinner sp = new Spinner(this);
        sp.setAdapter(adapter(items));
        select(sp, Math.max(0, sel));
        sp.setBackground(round(CARD_HI, dp(10)));
        sp.setPadding(dp(6), 0, dp(6), 0);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Object set = sp.getTag();
                if (set instanceof Integer && (Integer) set == pos) return;   // set by code, not the user
                sp.setTag(pos);
                onPick.on(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        parent.addView(sp, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return sp;
    }

    /** Selection made by code: remembered so the listener doesn't treat it as a user pick. */
    private static void select(Spinner sp, int pos) {
        sp.setTag(pos);
        sp.setSelection(pos, false);
    }

    private ArrayAdapter<String> adapter(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(TEXT);
                t.setTextSize(15);
                return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    interface IntCallback {
        void on(int v);
    }

    private SeekBar slider(int max, int value, SeekBar.OnSeekBarChangeListener l) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(value);
        sb.setOnSeekBarChangeListener(l);
        return sb;
    }

    /** Listener that only cares about some of the callbacks. */
    private abstract static class Slide implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {}
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }

    private List<String> strings(int[] res) {
        List<String> l = new ArrayList<>();
        for (int r : res) l.add(getString(r));
        return l;
    }

    private static int indexOf(int[] arr, int v, int fallback) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return fallback;
    }

    // ------------------------------------------------------------------ build the screen

    @Override
    @SuppressWarnings("deprecation")   // the WindowInsets getters that work back to API 26
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Cfg.prefs(this);
        am = getSystemService(AudioManager.class);
        Stats.camFront = prefs.getBoolean(Cfg.K_CAM_FRONT, false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));

        buildHeader(root);
        buildHero(root);
        buildOutput(root);
        buildMic(root);
        applyVolumes(false);
        buildCamera(root);
        buildLatency(root);
        buildAdvanced(root);
        buildFooter(root);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.addView(root);
        // status / navigation bar insets (edge-to-edge on new Android versions)
        final int pl = root.getPaddingLeft(), pt = root.getPaddingTop(), pr = root.getPaddingRight(), pb = root.getPaddingBottom();
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(pl + insets.getSystemWindowInsetLeft(), pt + insets.getSystemWindowInsetTop(),
                    pr + insets.getSystemWindowInsetRight(), pb + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(sv);
        applyAwake();

        // not when recreated or reopened from Recents: that would replay phone-bridge's autostart
        if (b == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handleIntent(getIntent());
        }
        requestMissingPermissions();
    }

    /** One request for everything the enabled streams need (overlapping requests get dropped). */
    private void requestMissingPermissions() {
        List<String> perms = new ArrayList<>();
        if (prefs.getBoolean(Cfg.K_MIC, true)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }
        if (prefs.getBoolean(Cfg.K_CAM, false)
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), REQ_MIC);
    }

    private void buildHeader(LinearLayout root) {
        TextView t = text(getString(R.string.app_name), 26, TEXT, true);
        root.addView(t);
        TextView s = text(getString(R.string.tagline), 14, TEXT2, false);
        s.setPadding(0, dp(2), 0, dp(16));
        root.addView(s);
    }

    private void buildHero(LinearLayout root) {
        LinearLayout c = card(root);
        LinearLayout r = row();
        heroDot = new View(this);
        r.addView(heroDot, lp(dp(14), dp(14)));
        heroTitle = text("", 20, TEXT, true);
        LinearLayout.LayoutParams tp = weight(1);
        tp.leftMargin = dp(12);
        r.addView(heroTitle, tp);
        c.addView(r);
        heroHint = text("", 14, TEXT2, false);
        heroHint.setPadding(0, dp(6), 0, dp(14));
        c.addView(heroHint);

        btnStart = pillButton();
        btnStart.setTextSize(17);
        btnStart.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnStart.setOnClickListener(v -> onToggle());
        c.addView(btnStart, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
    }

    private void buildOutput(LinearLayout root) {
        LinearLayout c = card(root);
        swSpk = new Switch(this);
        swSpk.setChecked(prefs.getBoolean(Cfg.K_SPK, true));
        swSpk.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(Cfg.K_SPK, on).apply();
            applyIfRunning();
        });
        cardHeader(c, R.drawable.ic_speaker, R.string.out_title, swSpk);

        spkDot = new View(this);
        spkStatus = text("", 15, TEXT, true);
        spkDetail = text("", 12, TEXT2, false);
        statusLines(c, spkDot, spkStatus, spkDetail);

        btnMuteSpk = pillButton();
        btnMuteSpk.setOnClickListener(v -> Stats.muteSpk = !Stats.muteSpk);
        sbSpkVol = slider(200, prefs.getInt(Cfg.K_SPK_VOL, 100), new Slide() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                applyVolumes(user);
            }
        });
        spkVolText = text("", 13, TEXT2, false);
        volumeRow(c, btnMuteSpk, sbSpkVol, spkVolText);

        spOut = spinner(c, R.string.play_on, outNames, 0, pos -> onDevicePicked(false, pos));
        spkBtTip = text(getString(R.string.bt_tip), 12, AMBER, false);
        spkBtTip.setPadding(0, dp(8), 0, 0);
        c.addView(spkBtTip);

        svSpk = new SpectrogramView(this, Stats.spkTap);
        spectrogram(c, svSpk);
    }

    private void buildMic(LinearLayout root) {
        LinearLayout c = card(root);
        swMic = new Switch(this);
        swMic.setChecked(prefs.getBoolean(Cfg.K_MIC, true));
        swMic.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(Cfg.K_MIC, on).apply();
            if (on && !reflecting
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
            applyIfRunning();
        });
        cardHeader(c, R.drawable.ic_mic, R.string.mic_title, swMic);

        micDot = new View(this);
        micStatus = text("", 15, TEXT, true);
        micDetail = text("", 12, TEXT2, false);
        statusLines(c, micDot, micStatus, micDetail);

        micLevel = new LevelView(this);
        LinearLayout.LayoutParams mp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        mp.topMargin = dp(10);
        c.addView(micLevel, mp);

        btnMuteMic = pillButton();
        btnMuteMic.setOnClickListener(v -> {
            if (Stats.running) {
                startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_MUTE_MIC));
            } else {
                Stats.muteMic = !Stats.muteMic;
            }
        });
        sbMicVol = slider(200, prefs.getInt(Cfg.K_MIC_VOL, 100), new Slide() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                applyVolumes(user);
            }
        });
        micVolText = text("", 13, TEXT2, false);
        volumeRow(c, btnMuteMic, sbMicVol, micVolText);

        spIn = spinner(c, R.string.record_from, inNames, 0, pos -> onDevicePicked(true, pos));
        spSrc = spinner(c, R.string.mic_mode, strings(SRC_NAMES),
                indexOf(SRC_IDS, prefs.getInt(Cfg.K_SRC, Cfg.DEFAULT_SRC), 0), pos -> {
                    if (prefs.getInt(Cfg.K_SRC, Cfg.DEFAULT_SRC) == SRC_IDS[pos]) return;
                    prefs.edit().putInt(Cfg.K_SRC, SRC_IDS[pos]).apply();
                    applyIfRunning();
                });

        svMic = new SpectrogramView(this, Stats.micTap);
        spectrogram(c, svMic);
        refreshDevices();
    }

    private void buildCamera(LinearLayout root) {
        LinearLayout c = card(root);
        swCam = new Switch(this);
        swCam.setChecked(prefs.getBoolean(Cfg.K_CAM, false));
        swCam.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(Cfg.K_CAM, on).apply();
            camBody.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on && !reflecting
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            }
            updatePreviewTarget();
            applyIfRunning();
        });
        cardHeader(c, R.drawable.ic_camera, R.string.cam_title, swCam);

        camDot = new View(this);
        camStatus = text("", 15, TEXT, true);
        camDetail = text("", 12, TEXT2, false);
        statusLines(c, camDot, camStatus, camDetail);

        camBody = new LinearLayout(this);
        camBody.setOrientation(LinearLayout.VERTICAL);
        c.addView(camBody);

        facingSeg = segmented(camBody, new int[]{R.string.cam_back, R.string.cam_front}, v -> {
            boolean front = (Integer) v.getTag() == 1;
            Stats.camFront = front;                        // the pipe switches live
            prefs.edit().putBoolean(Cfg.K_CAM_FRONT, front).apply();
            selectSeg(facingSeg, front ? 1 : 0);
        });
        selectSeg(facingSeg, Stats.camFront ? 1 : 0);

        previewBox = new FrameLayout(this);
        previewBox.setBackground(round(0xFF000000, dp(12)));
        previewBox.setClipToOutline(true);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(previewListener);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView ph = text(getString(R.string.cam_preview_hint), 12, TEXT2, false);
        ph.setGravity(Gravity.CENTER);
        previewBox.addView(ph, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams pp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(200));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(12);
        camBody.addView(previewBox, pp);

        List<String> sizes = new ArrayList<>();
        for (String s : CAM_SIZES) sizes.add(s.replace("x", " × "));
        spCamSize = spinner(camBody, R.string.cam_resolution, sizes,
                java.util.Arrays.asList(CAM_SIZES).indexOf(prefs.getString(Cfg.K_CAM_SIZE, Cfg.DEFAULT_CAM_SIZE)),
                pos -> {
                    if (CAM_SIZES[pos].equals(prefs.getString(Cfg.K_CAM_SIZE, Cfg.DEFAULT_CAM_SIZE))) return;
                    prefs.edit().putString(Cfg.K_CAM_SIZE, CAM_SIZES[pos]).apply();
                    sizePreview();
                    applyIfRunning();
                });
        List<String> fps = new ArrayList<>();
        for (int f : CAM_FPS) fps.add(getString(R.string.fps_n, f));
        spCamFps = spinner(camBody, R.string.cam_fps, fps,
                indexOf(CAM_FPS, prefs.getInt(Cfg.K_CAM_FPS, Cfg.DEFAULT_CAM_FPS), 2), pos -> {
                    if (prefs.getInt(Cfg.K_CAM_FPS, Cfg.DEFAULT_CAM_FPS) == CAM_FPS[pos]) return;
                    prefs.edit().putInt(Cfg.K_CAM_FPS, CAM_FPS[pos]).apply();
                    applyIfRunning();
                });
        spCamQuality = spinner(camBody, R.string.cam_quality, strings(CAM_QUALITY),
                prefs.getInt(Cfg.K_CAM_QUALITY, Cfg.DEFAULT_CAM_QUALITY), pos -> {
                    if (prefs.getInt(Cfg.K_CAM_QUALITY, Cfg.DEFAULT_CAM_QUALITY) == pos) return;
                    prefs.edit().putInt(Cfg.K_CAM_QUALITY, pos).apply();
                    applyIfRunning();
                });
        List<String> rots = new ArrayList<>();
        rots.add(getString(R.string.rot_auto));
        for (int i = 1; i < CAM_ROTS.length; i++) rots.add(getString(R.string.rot_n, CAM_ROTS[i]));
        spCamRot = spinner(camBody, R.string.cam_rotation, rots,
                indexOf(CAM_ROTS, prefs.getInt(Cfg.K_CAM_ROT, -1), 0), pos -> {
                    if (prefs.getInt(Cfg.K_CAM_ROT, -1) == CAM_ROTS[pos]) return;
                    prefs.edit().putInt(Cfg.K_CAM_ROT, CAM_ROTS[pos]).apply();
                    applyIfRunning();
                });

        swMirror = new Switch(this);
        swMirror.setText(R.string.cam_mirror);
        swMirror.setTextColor(TEXT);
        swMirror.setTextSize(15);
        swMirror.setChecked(prefs.getBoolean(Cfg.K_CAM_MIRROR, false));
        swMirror.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(Cfg.K_CAM_MIRROR, on).apply();
            applyIfRunning();
        });
        LinearLayout.LayoutParams mp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        mp.topMargin = dp(6);
        camBody.addView(swMirror, mp);

        TextView hint = text(getString(R.string.cam_hint), 12, TEXT2, false);
        hint.setPadding(0, dp(4), 0, 0);
        camBody.addView(hint);

        camBody.setVisibility(swCam.isChecked() ? View.VISIBLE : View.GONE);
        sizePreview();
    }

    private void buildLatency(LinearLayout root) {
        LinearLayout c = card(root);
        cardHeader(c, R.drawable.ic_speed, R.string.latency_title, null);
        presetSeg = segmented(c, PRESET_NAMES, v -> {
            int i = (Integer) v.getTag();
            sbBuf.setProgress(Cfg.PRESETS[i][0] - Cfg.MIN_BUF_MS);
            sbMicBuf.setProgress(Cfg.PRESETS[i][1] - Cfg.MIN_BUF_MS);
            saveBuffers();
        });
        presetDesc = text("", 13, TEXT2, false);
        presetDesc.setPadding(0, dp(10), 0, 0);
        c.addView(presetDesc);

        final TextView fine = text(getString(R.string.fine_tune) + "  ▸", 14, ACCENT, true);
        fine.setPadding(0, dp(14), 0, dp(4));
        c.addView(fine);
        fineBody = new LinearLayout(this);
        fineBody.setOrientation(LinearLayout.VERTICAL);
        fineBody.setVisibility(View.GONE);
        c.addView(fineBody);
        fine.setOnClickListener(v -> {
            boolean show = fineBody.getVisibility() != View.VISIBLE;
            fineBody.setVisibility(show ? View.VISIBLE : View.GONE);
            fine.setText(getString(R.string.fine_tune) + (show ? "  ▾" : "  ▸"));
        });

        Slide bufListener = new Slide() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                showBuf();
            }
            /** Buffers only change by recreating the pipes: apply on release. */
            @Override public void onStopTrackingTouch(SeekBar sb) {
                saveBuffers();
            }
        };
        tvBuf = fieldLabel(R.string.out_buffer);
        fineBody.addView(tvBuf);
        sbBuf = slider(Cfg.MAX_BUF_MS - Cfg.MIN_BUF_MS,
                prefs.getInt(Cfg.K_BUF, Cfg.DEFAULT_BUF_MS) - Cfg.MIN_BUF_MS, bufListener);
        fineBody.addView(sbBuf);
        tvMicBuf = fieldLabel(R.string.mic_buffer);
        fineBody.addView(tvMicBuf);
        sbMicBuf = slider(Cfg.MAX_BUF_MS - Cfg.MIN_BUF_MS,
                prefs.getInt(Cfg.K_MIC_BUF, Cfg.DEFAULT_MIC_BUF_MS) - Cfg.MIN_BUF_MS, bufListener);
        fineBody.addView(sbMicBuf);
        showBuf();
    }

    private void buildAdvanced(LinearLayout root) {
        LinearLayout c = card(root);
        LinearLayout h = cardHeader(c, R.drawable.ic_tune, R.string.advanced, null);
        advArrow = text("▸", 16, TEXT2, false);
        h.addView(advArrow);
        h.setOnClickListener(v -> setAdvanced(advBody.getVisibility() != View.VISIBLE));

        advBody = new LinearLayout(this);
        advBody.setOrientation(LinearLayout.VERTICAL);
        c.addView(advBody);

        swAwake = new Switch(this);
        swAwake.setText(R.string.keep_awake);
        swAwake.setTextColor(TEXT);
        swAwake.setTextSize(15);
        swAwake.setChecked(prefs.getBoolean(Cfg.K_AWAKE, true));
        swAwake.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(Cfg.K_AWAKE, on).apply();
            applyAwake();
        });
        advBody.addView(swAwake, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        advBody.addView(fieldLabel(R.string.ports));
        LinearLayout ports = row();
        etSpkPort = portField(prefs.getInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT), R.string.port_out);
        etMicPort = portField(prefs.getInt(Cfg.K_MIC_PORT, Cfg.DEFAULT_MIC_PORT), R.string.port_mic);
        etCamPort = portField(prefs.getInt(Cfg.K_CAM_PORT, Cfg.DEFAULT_CAM_PORT), R.string.port_cam);
        for (EditText e : new EditText[]{etSpkPort, etMicPort, etCamPort}) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(e.getHint(), 11, TEXT2, false));
            col.addView(e);
            LinearLayout.LayoutParams p = weight(1);
            p.rightMargin = dp(8);
            ports.addView(col, p);
        }
        advBody.addView(ports);
        TextView portHint = text(getString(R.string.ports_hint), 12, TEXT2, false);
        portHint.setPadding(0, dp(4), 0, 0);
        advBody.addView(portHint);

        LinearLayout btns = row();
        btns.setPadding(0, dp(12), 0, 0);
        Button apply = pillButton();
        apply.setText(R.string.apply_ports);
        stylePill(apply, CARD_HI);
        apply.setOnClickListener(v -> savePorts());
        btns.addView(apply, new LinearLayout.LayoutParams(0, dp(44), 1f));
        View gap = new View(this);
        btns.addView(gap, lp(dp(10), 1));
        Button reset = pillButton();
        reset.setText(R.string.reset_defaults);
        stylePill(reset, CARD_HI);
        reset.setOnClickListener(v -> resetDefaults());
        btns.addView(reset, new LinearLayout.LayoutParams(0, dp(44), 1f));
        advBody.addView(btns);

        setAdvanced(prefs.getBoolean(Cfg.K_ADVANCED, false));
    }

    private void buildFooter(LinearLayout root) {
        String ver = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        TextView f = text(getString(R.string.footer, ver), 12, GRAY, false);
        f.setGravity(Gravity.CENTER);
        f.setPadding(0, dp(4), 0, 0);
        root.addView(f);
    }

    private void volumeRow(LinearLayout c, Button mute, SeekBar sb, TextView value) {
        LinearLayout r = row();
        r.setPadding(0, dp(12), 0, 0);
        r.addView(mute, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        r.addView(sb, weight(1));
        r.addView(value, lp(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        value.setGravity(Gravity.END);
        c.addView(r);
    }

    private void spectrogram(LinearLayout c, SpectrogramView v) {
        v.setBackground(round(0xFF000000, dp(10)));
        v.setClipToOutline(true);
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        p.topMargin = dp(14);
        c.addView(v, p);
    }

    private EditText portField(int value, int hint) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setHint(hint);
        e.setText(String.valueOf(value));
        e.setTextColor(TEXT);
        e.setHintTextColor(GRAY);
        e.setSingleLine(true);
        return e;
    }

    // ------------------------------------------------------------------ actions

    private void setAdvanced(boolean show) {
        advBody.setVisibility(show ? View.VISIBLE : View.GONE);
        advArrow.setText(show ? "▾" : "▸");
        prefs.edit().putBoolean(Cfg.K_ADVANCED, show).apply();
    }

    /** Screen stays on while the app is open (the audio itself keeps going with the screen off). */
    private void applyAwake() {
        if (swAwake.isChecked()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Volume applies right away (the pipes read the gain every 5 ms); saved when it came from the user. */
    private void applyVolumes(boolean save) {
        int sv = sbSpkVol.getProgress(), mv = sbMicVol.getProgress();
        Stats.spkGain = sv / 100f;
        Stats.micGain = mv / 100f;
        spkVolText.setText(getString(R.string.percent, sv));
        micVolText.setText(getString(R.string.percent, mv));
        if (save) prefs.edit().putInt(Cfg.K_SPK_VOL, sv).putInt(Cfg.K_MIC_VOL, mv).apply();
    }

    private void showBuf() {
        int out = sbBuf.getProgress() + Cfg.MIN_BUF_MS, mic = sbMicBuf.getProgress() + Cfg.MIN_BUF_MS;
        tvBuf.setText(getString(R.string.out_buffer_ms, out));
        tvMicBuf.setText(getString(R.string.mic_buffer_ms, mic));
        int sel = -1;
        for (int i = 0; i < Cfg.PRESETS.length; i++) {
            if (Cfg.PRESETS[i][0] == out && Cfg.PRESETS[i][1] == mic) sel = i;
        }
        selectSeg(presetSeg, sel);
        presetDesc.setText(sel >= 0 ? getString(PRESET_DESC[sel]) : getString(R.string.preset_custom_desc, out, mic));
    }

    private void saveBuffers() {
        prefs.edit()
                .putInt(Cfg.K_BUF, sbBuf.getProgress() + Cfg.MIN_BUF_MS)
                .putInt(Cfg.K_MIC_BUF, sbMicBuf.getProgress() + Cfg.MIN_BUF_MS)
                .apply();
        showBuf();
        applyIfRunning();
    }

    /** Valid port (1024..65535) or the old value. */
    private static int port(EditText e, int fallback) {
        try {
            int p = Integer.parseInt(e.getText().toString().trim());
            return Cfg.validPort(p) ? p : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void savePorts() {
        int s = port(etSpkPort, -1), m = port(etMicPort, -1), c = port(etCamPort, -1);
        if (s < 0 || m < 0 || c < 0 || s == m || s == c || m == c) {
            Toast.makeText(this, R.string.ports_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putInt(Cfg.K_SPK_PORT, s).putInt(Cfg.K_MIC_PORT, m).putInt(Cfg.K_CAM_PORT, c).apply();
        Toast.makeText(this, R.string.ports_saved, Toast.LENGTH_SHORT).show();
        applyIfRunning();
    }

    private void resetDefaults() {
        boolean awake = true;
        prefs.edit()
                .putInt(Cfg.K_BUF, Cfg.DEFAULT_BUF_MS).putInt(Cfg.K_MIC_BUF, Cfg.DEFAULT_MIC_BUF_MS)
                .putInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT).putInt(Cfg.K_MIC_PORT, Cfg.DEFAULT_MIC_PORT)
                .putInt(Cfg.K_CAM_PORT, Cfg.DEFAULT_CAM_PORT)
                .putInt(Cfg.K_SPK_VOL, 100).putInt(Cfg.K_MIC_VOL, 100)
                .putInt(Cfg.K_SRC, Cfg.DEFAULT_SRC)
                .putString(Cfg.K_CAM_SIZE, Cfg.DEFAULT_CAM_SIZE).putInt(Cfg.K_CAM_FPS, Cfg.DEFAULT_CAM_FPS)
                .putInt(Cfg.K_CAM_QUALITY, Cfg.DEFAULT_CAM_QUALITY).putInt(Cfg.K_CAM_ROT, -1)
                .putBoolean(Cfg.K_CAM_MIRROR, false).putBoolean(Cfg.K_AWAKE, awake)
                .putString(Cfg.K_OUT_DEV, "").putString(Cfg.K_IN_DEV, "")
                .apply();
        reflectPrefs();
        refreshDevices();
        Toast.makeText(this, R.string.defaults_restored, Toast.LENGTH_SHORT).show();
        applyIfRunning();
    }

    /** Puts the saved settings back on the screen (after autostart extras or a reset). */
    private void reflectPrefs() {
        reflecting = true;
        swSpk.setChecked(prefs.getBoolean(Cfg.K_SPK, true));
        swMic.setChecked(prefs.getBoolean(Cfg.K_MIC, true));
        swCam.setChecked(prefs.getBoolean(Cfg.K_CAM, false));
        swAwake.setChecked(prefs.getBoolean(Cfg.K_AWAKE, true));
        swMirror.setChecked(prefs.getBoolean(Cfg.K_CAM_MIRROR, false));
        sbBuf.setProgress(prefs.getInt(Cfg.K_BUF, Cfg.DEFAULT_BUF_MS) - Cfg.MIN_BUF_MS);
        sbMicBuf.setProgress(prefs.getInt(Cfg.K_MIC_BUF, Cfg.DEFAULT_MIC_BUF_MS) - Cfg.MIN_BUF_MS);
        sbSpkVol.setProgress(prefs.getInt(Cfg.K_SPK_VOL, 100));
        sbMicVol.setProgress(prefs.getInt(Cfg.K_MIC_VOL, 100));
        applyVolumes(false);
        etSpkPort.setText(String.valueOf(prefs.getInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT)));
        etMicPort.setText(String.valueOf(prefs.getInt(Cfg.K_MIC_PORT, Cfg.DEFAULT_MIC_PORT)));
        etCamPort.setText(String.valueOf(prefs.getInt(Cfg.K_CAM_PORT, Cfg.DEFAULT_CAM_PORT)));
        select(spSrc, indexOf(SRC_IDS, prefs.getInt(Cfg.K_SRC, Cfg.DEFAULT_SRC), 0));
        select(spCamSize, Math.max(0, java.util.Arrays.asList(CAM_SIZES)
                .indexOf(prefs.getString(Cfg.K_CAM_SIZE, Cfg.DEFAULT_CAM_SIZE))));
        select(spCamFps, indexOf(CAM_FPS, prefs.getInt(Cfg.K_CAM_FPS, Cfg.DEFAULT_CAM_FPS), 2));
        select(spCamQuality, prefs.getInt(Cfg.K_CAM_QUALITY, Cfg.DEFAULT_CAM_QUALITY));
        select(spCamRot, indexOf(CAM_ROTS, prefs.getInt(Cfg.K_CAM_ROT, -1), 0));
        Stats.camFront = prefs.getBoolean(Cfg.K_CAM_FRONT, false);
        selectSeg(facingSeg, Stats.camFront ? 1 : 0);
        sizePreview();
        showBuf();
        reflecting = false;
    }

    private void onToggle() {
        if (Stats.running) {
            clearSco();
            startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP));
        } else {
            startBridge();
        }
    }

    private void startBridge() {
        startForegroundService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_START));
    }

    /** Settings changed: the service restarts only the pipes affected. */
    private void applyIfRunning() {
        if (Stats.running) startBridge();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
        requestMissingPermissions();
    }

    /** Lets the PC start everything: am start -n ... --ez autostart true --ez spk true ... */
    private void handleIntent(Intent i) {
        if (i == null || !i.getBooleanExtra("autostart", false)) return;
        // only through the permission-protected alias (adb shell), never from other apps
        if (i.getComponent() == null || !i.getComponent().getClassName().endsWith(".Autostart")) return;
        i.removeExtra("autostart");          // don't start again on a configuration change
        SharedPreferences.Editor e = prefs.edit();
        if (i.hasExtra("spk")) e.putBoolean(Cfg.K_SPK, i.getBooleanExtra("spk", true));
        if (i.hasExtra("mic")) e.putBoolean(Cfg.K_MIC, i.getBooleanExtra("mic", true));
        if (i.hasExtra("cam")) e.putBoolean(Cfg.K_CAM, i.getBooleanExtra("cam", false));
        if (i.hasExtra("camfront")) e.putBoolean(Cfg.K_CAM_FRONT, i.getBooleanExtra("camfront", false));
        if (i.hasExtra("camsize")) {
            String s = i.getStringExtra("camsize");
            if (s != null && s.matches("\\d{3,4}x\\d{3,4}")) e.putString(Cfg.K_CAM_SIZE, s);
        }
        if (i.hasExtra("camfps")) e.putInt(Cfg.K_CAM_FPS, Math.max(5, Math.min(60, i.getIntExtra("camfps", 30))));
        if (i.hasExtra("buf")) e.putInt(Cfg.K_BUF, Cfg.clampBuf(i.getIntExtra("buf", Cfg.DEFAULT_BUF_MS)));
        if (i.hasExtra("micbuf")) e.putInt(Cfg.K_MIC_BUF, Cfg.clampBuf(i.getIntExtra("micbuf", Cfg.DEFAULT_MIC_BUF_MS)));
        if (i.hasExtra("src")) e.putInt(Cfg.K_SRC, i.getIntExtra("src", Cfg.DEFAULT_SRC));
        int p;
        if (Cfg.validPort(p = i.getIntExtra("spkport", -1))) e.putInt(Cfg.K_SPK_PORT, p);
        if (Cfg.validPort(p = i.getIntExtra("micport", -1))) e.putInt(Cfg.K_MIC_PORT, p);
        if (Cfg.validPort(p = i.getIntExtra("camport", -1))) e.putInt(Cfg.K_CAM_PORT, p);
        e.apply();
        reflectPrefs();
        camBody.setVisibility(swCam.isChecked() ? View.VISIBLE : View.GONE);
        startBridge();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        for (int k = 0; k < perms.length; k++) {
            if (res[k] == PackageManager.PERMISSION_GRANTED) continue;
            if (Manifest.permission.RECORD_AUDIO.equals(perms[k])) {
                Toast.makeText(this, R.string.err_mic_permission, Toast.LENGTH_LONG).show();
            } else if (Manifest.permission.CAMERA.equals(perms[k])) {
                Toast.makeText(this, R.string.err_cam_permission, Toast.LENGTH_LONG).show();
            }
        }
        applyIfRunning();   // pick up the newly granted mic/camera
    }

    // ------------------------------------------------------------------ phone audio devices

    private static String devKey(AudioDeviceInfo d) {
        return d.getType() + ":" + d.getProductName() + ":" + d.getAddress();
    }

    /** Friendly name; null = a type that makes no sense to offer. */
    private String devName(AudioDeviceInfo d, boolean input) {
        String n = String.valueOf(d.getProductName());
        String addr = d.getAddress() == null ? "" : d.getAddress();
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return getString(R.string.dev_speaker);
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return input ? null : getString(R.string.dev_earpiece);
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return getString(R.string.dev_builtin_mic) + (addr.isEmpty() ? "" : " (" + addr + ")");
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return getString(R.string.dev_bt, n);
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return getString(input ? R.string.dev_bt_call_in : R.string.dev_bt_call, n);
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return getString(input ? R.string.dev_wired_mic : R.string.dev_wired);
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return getString(R.string.dev_wired);
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE: return getString(R.string.dev_usb, n);
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return getString(R.string.dev_ble, n);
            default: return null;
        }
    }

    private void refreshDevices() {
        fillDevices(spOut, outDevs, outNames, am.getDevices(AudioManager.GET_DEVICES_OUTPUTS), false,
                prefs.getString(Cfg.K_OUT_DEV, ""));
        fillDevices(spIn, inDevs, inNames, am.getDevices(AudioManager.GET_DEVICES_INPUTS), true,
                prefs.getString(Cfg.K_IN_DEV, ""));
        Stats.outDevice = outDevs.get(Math.max(0, spOut.getSelectedItemPosition()));
        Stats.inDevice = inDevs.get(Math.max(0, spIn.getSelectedItemPosition()));
        updateBtTip();
    }

    private void fillDevices(Spinner sp, List<AudioDeviceInfo> list, List<String> names,
                             AudioDeviceInfo[] devs, boolean input, String savedKey) {
        List<AudioDeviceInfo> nl = new ArrayList<>();
        List<String> nn = new ArrayList<>();
        nl.add(null);
        nn.add(getString(R.string.dev_auto));
        int sel = 0;
        for (AudioDeviceInfo d : devs) {
            String nm = devName(d, input);
            if (nm == null || nn.contains(nm)) continue;
            nl.add(d);
            nn.add(nm);
            if (devKey(d).equals(savedKey)) sel = nl.size() - 1;
        }
        list.clear();
        list.addAll(nl);
        if (!nn.equals(names) || sp.getAdapter() == null) {      // don't reset an open dropdown for nothing
            names.clear();
            names.addAll(nn);
            sp.setAdapter(adapter(new ArrayList<>(names)));
        }
        select(sp, sel);
    }

    private void onDevicePicked(boolean input, int pos) {
        if (input) {
            if (pos >= inDevs.size()) return;
            AudioDeviceInfo d = inDevs.get(pos);
            Stats.inDevice = d;
            prefs.edit().putString(Cfg.K_IN_DEV, d == null ? "" : devKey(d)).apply();
            applySco(d);
        } else {
            if (pos >= outDevs.size()) return;
            AudioDeviceInfo d = outDevs.get(pos);
            Stats.outDevice = d;
            prefs.edit().putString(Cfg.K_OUT_DEV, d == null ? "" : devKey(d)).apply();
            updateBtTip();
        }
    }

    /** Bluetooth adds most of the delay; say so when it's (probably) the output. */
    private void updateBtTip() {
        boolean bt = false;
        AudioDeviceInfo d = Stats.outDevice;
        if (d != null) {
            bt = d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET;
        } else {
            boolean wired = false;
            for (AudioDeviceInfo o : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = o.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET) bt = true;
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) wired = true;
            }
            bt &= !wired;   // Android prefers the wired one
        }
        spkBtTip.setVisibility(bt ? View.VISIBLE : View.GONE);
    }

    /** The BT headset mic only works with the headset in call mode (SCO). */
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
        } else {
            clearSco();
        }
    }

    private void clearSco() {
        if (scoOn && Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
        scoOn = false;
    }

    @Override
    protected void onDestroy() {
        if (!Stats.running) clearSco();   // while running the mic may still need it
        super.onDestroy();
    }

    // ------------------------------------------------------------------ camera preview

    private final TextureView.SurfaceTextureListener previewListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            previewTexture = st;
            st.setDefaultBufferSize(previewW, previewH);
            previewSurface = new Surface(st);
            fitPreview();
            updatePreviewTarget();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            fitPreview();
        }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            // detach from the camera first, release once the pipe has moved off it
            final Surface s = previewSurface;
            previewSurface = null;
            previewTexture = null;
            Stats.camPreview = null;
            ui.postDelayed(() -> {
                if (s != null) s.release();
                st.release();
            }, 800);
            return false;
        }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    };

    /** The camera renders into the preview only while it is visible. */
    private void updatePreviewTarget() {
        Surface want = resumed && swCam != null && swCam.isChecked() ? previewSurface : null;
        if (Stats.camPreview != want) Stats.camPreview = want;
    }

    /** Preview box with the shape of the stream: landscape box in landscape, portrait in portrait. */
    private void sizePreview() {
        int[] wh = CameraPipe.parseSize(prefs.getString(Cfg.K_CAM_SIZE, Cfg.DEFAULT_CAM_SIZE));
        previewW = 640;
        previewH = 640 * wh[1] / wh[0];
        if (previewTexture != null) previewTexture.setDefaultBufferSize(previewW, previewH);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        ViewGroup.LayoutParams p = previewBox.getLayoutParams();
        if (landscape) {
            p.width = dp(360);
            p.height = dp(360) * wh[1] / wh[0];
        } else {
            p.height = dp(300);
            p.width = dp(300) * wh[1] / wh[0];
        }
        previewBox.setLayoutParams(p);
        fitPreview();
    }

    /** Camera buffers come upright for the phone's natural (portrait) orientation; undo the screen rotation. */
    @SuppressWarnings("deprecation")   // Activity.getDisplay() is API 30+
    private void fitPreview() {
        int vw = preview.getWidth(), vh = preview.getHeight();
        if (vw == 0 || vh == 0) return;
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        Matrix m = new Matrix();
        float cx = vw / 2f, cy = vh / 2f;
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            RectF view = new RectF(0, 0, vw, vh);
            RectF buf = new RectF(0, 0, previewH, previewW);
            buf.offset(cx - buf.centerX(), cy - buf.centerY());
            m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL);
            float s = Math.max((float) vh / previewH, (float) vw / previewW);
            m.postScale(s, s, cx, cy);
            m.postRotate(90 * (rot - 2), cx, cy);
        } else if (rot == Surface.ROTATION_180) {
            m.postRotate(180, cx, cy);
        }
        preview.setTransform(m);
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            fitPreview();
        }
    };

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        sizePreview();
        preview.post(this::fitPreview);
    }

    // ------------------------------------------------------------------ lifecycle + live status

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        lastT = System.currentTimeMillis();
        lastSpkBytes = Stats.spkBytes;
        lastMicBytes = Stats.micBytes;
        lastCamBytes = Stats.camBytes;
        lastCamFrames = Stats.camFrames;
        ui.post(tick);
        ui.post(specTick);
        if (watcher == null) watcher = new DeviceWatcher(this::refreshDevices);
        am.registerAudioDeviceCallback(watcher, ui);
        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, ui);
        updatePreviewTarget();
    }

    @Override
    protected void onPause() {
        resumed = false;
        updatePreviewTarget();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(specTick);
        if (watcher != null) am.unregisterAudioDeviceCallback(watcher);
        getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        super.onPause();
    }

    private final Runnable tick = this::onTick;
    private final Runnable specTick = this::onSpecTick;

    private void onSpecTick() {
        if (swMic.isChecked()) svMic.update();
        if (swSpk.isChecked()) svSpk.update();
        micLevel.setPeak(Stats.micConnected ? Stats.micPeak : 0);
        ui.postDelayed(specTick, 33);
    }

    private void setStatus(View dot, TextView status, TextView detail, int color, String s, String d) {
        paintDot(dot, color);
        status.setText(s);
        detail.setText(d);
        detail.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void onTick() {
        long now = System.currentTimeMillis();
        double dt = Math.max(1, now - lastT) / 1000.0;
        double spkRate = (Stats.spkBytes - lastSpkBytes) / dt / 1024.0;
        double micRate = (Stats.micBytes - lastMicBytes) / dt / 1024.0;
        double camRate = (Stats.camBytes - lastCamBytes) / dt / 1024.0;
        double camFps = (Stats.camFrames - lastCamFrames) / dt;
        lastT = now;
        lastSpkBytes = Stats.spkBytes;
        lastMicBytes = Stats.micBytes;
        lastCamBytes = Stats.camBytes;
        lastCamFrames = Stats.camFrames;

        boolean run = Stats.running;
        boolean spkOn = swSpk.isChecked(), micOn = swMic.isChecked(), camOn = swCam.isChecked();

        // --- output
        if (!spkOn) {
            setStatus(spkDot, spkStatus, spkDetail, GRAY, getString(R.string.st_off), "");
        } else if (!run) {
            setStatus(spkDot, spkStatus, spkDetail, GRAY, getString(R.string.st_not_running), "");
        } else if (Stats.spkConnected) {
            int lat = Stats.spkLatencyMs >= 0 ? Stats.spkLatencyMs : Stats.spkQueuedMs;
            setStatus(spkDot, spkStatus, spkDetail, Stats.muteSpk ? RED : GREEN,
                    getString(Stats.muteSpk ? R.string.st_spk_muted : R.string.st_spk_on, lat),
                    getString(R.string.st_spk_detail, Stats.spkQueuedMs, Math.round(spkRate),
                            Stats.spkUnderruns, Stats.spkDroppedMs));
        } else {
            setStatus(spkDot, spkStatus, spkDetail, AMBER, getString(R.string.st_waiting), Stats.spkError);
        }
        btnMuteSpk.setText(Stats.muteSpk ? R.string.unmute : R.string.mute);
        stylePill(btnMuteSpk, Stats.muteSpk ? RED : CARD_HI);

        // --- mic
        if (!micOn) {
            setStatus(micDot, micStatus, micDetail, GRAY, getString(R.string.st_off), "");
        } else if (!run) {
            setStatus(micDot, micStatus, micDetail, GRAY, getString(R.string.st_not_running),
                    Stats.micError);
        } else if (Stats.micConnected) {
            setStatus(micDot, micStatus, micDetail, Stats.muteMic ? RED : GREEN,
                    getString(Stats.muteMic ? R.string.st_mic_muted : R.string.st_mic_on),
                    getString(R.string.st_mic_detail, Math.round(micRate)));
        } else {
            setStatus(micDot, micStatus, micDetail, AMBER, getString(R.string.st_waiting), Stats.micError);
        }
        btnMuteMic.setText(Stats.muteMic ? R.string.unmute : R.string.mute);
        stylePill(btnMuteMic, Stats.muteMic ? RED : CARD_HI);

        // --- camera
        if (!camOn) {
            setStatus(camDot, camStatus, camDetail, GRAY, getString(R.string.st_off), "");
        } else if (!run) {
            setStatus(camDot, camStatus, camDetail, GRAY, getString(R.string.st_not_running), Stats.camError);
        } else if (Stats.camConnected) {
            setStatus(camDot, camStatus, camDetail, GREEN,
                    getString(Stats.camFront ? R.string.st_cam_front : R.string.st_cam_back),
                    getString(R.string.st_cam_detail, Stats.camInfo, camFps, Math.round(camRate)));
        } else {
            setStatus(camDot, camStatus, camDetail, AMBER, getString(R.string.st_waiting), Stats.camError);
        }

        // --- overall
        int waiting = 0, live = 0;
        if (spkOn) { if (Stats.spkConnected) live++; else waiting++; }
        if (micOn) { if (Stats.micConnected) live++; else waiting++; }
        if (camOn) { if (Stats.camConnected) live++; else waiting++; }
        if (!run) {
            paintDot(heroDot, GRAY);
            heroTitle.setText(R.string.hero_stopped);
            heroHint.setText(R.string.hero_stopped_hint);
        } else if (live > 0 && waiting == 0) {
            paintDot(heroDot, GREEN);
            heroTitle.setText(R.string.hero_connected);
            heroHint.setText(R.string.hero_connected_hint);
        } else if (live > 0) {
            paintDot(heroDot, AMBER);
            heroTitle.setText(R.string.hero_partial);
            heroHint.setText(R.string.hero_partial_hint);
        } else if (waiting == 0) {
            paintDot(heroDot, AMBER);
            heroTitle.setText(R.string.hero_nothing);
            heroHint.setText(R.string.hero_nothing_hint);
        } else {
            paintDot(heroDot, AMBER);
            heroTitle.setText(R.string.hero_waiting);
            heroHint.setText(R.string.hero_waiting_hint);
        }
        btnStart.setText(run ? R.string.stop : R.string.start);
        stylePill(btnStart, run ? CARD_HI : ACCENT);
        btnStart.setTextColor(run ? RED : 0xFFFFFFFF);

        ui.postDelayed(tick, 250);
    }
}
