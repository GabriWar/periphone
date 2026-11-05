package dev.gabriwar.periphone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import java.util.function.Supplier;

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
    private static final int REQ_MIC = 1, REQ_NOTIF = 2, REQ_CAM = 3, REQ_BT = 4, REQ_PROJ = 5;
    private static final int[] LINK_NAMES = {R.string.link_usb, R.string.link_wifi, R.string.link_pan, R.string.link_bt};
    private static final int[] LINK_DESC = {R.string.link_usb_desc, R.string.link_wifi_desc, R.string.link_pan_desc,
            R.string.link_bt_desc};
    private static final int[] CODEC_NAMES = {R.string.codec_auto, R.string.codec_pcm, R.string.codec_opus};

    private SharedPreferences prefs;
    private AudioManager am;
    private DeviceWatcher watcher;
    private boolean scoOn;
    private final List<AudioDeviceInfo> outDevs = new ArrayList<>();
    private final List<AudioDeviceInfo> inDevs = new ArrayList<>();
    private final List<String> outNames = new ArrayList<>(), inNames = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastSpkBytes, lastMicBytes, lastCamBytes, lastCamFrames, lastCapBytes, lastT;
    private boolean resumed, reflecting;

    // tabs
    private static final int TAB_HOME = 0, TAB_LINK = 1, TAB_AUDIO = 2, TAB_CAM = 3, TAB_SETTINGS = 4;
    private static final int[] TAB_NAMES = {R.string.tab_home, R.string.tab_link, R.string.tab_audio,
            R.string.tab_camera, R.string.tab_settings};
    private static final int[] TAB_ICONS = {R.drawable.ic_home, R.drawable.ic_link, R.drawable.ic_speaker,
            R.drawable.ic_camera, R.drawable.ic_tune};
    private static final String STATE_TAB = "tab";
    private int tab = -1;
    private LinearLayout root;
    private final ScrollView[] pages = new ScrollView[TAB_NAMES.length];
    private final ImageView[] navIcons = new ImageView[TAB_NAMES.length];
    private final TextView[] navLabels = new TextView[TAB_NAMES.length];
    private LinearLayout navBar;
    private TextView noticeBar;
    // status strip (every tab but Home): overall state + Start/Stop
    private LinearLayout strip;
    private View stripDot;
    private TextView stripTitle;
    private Button stripBtn;
    private int overallColor;

    // hero
    private View heroDot;
    private TextView heroTitle, heroHint;
    private Button btnStart;
    // live status of each stream, shown in its card and in the summary on Home
    private Status spkSt, micSt, capSt, camSt, linkSt;
    private Switch homeSwSpk, homeSwMic, homeSwCap, homeSwCam;
    private LevelView homeMicLevel;
    // output
    private Switch swSpk;
    private TextView spkVolText, spkBtTip;
    private Button btnMuteSpk;
    private SeekBar sbSpkVol;
    private Spinner spOut;
    private SpectrogramView svSpk;
    // mic
    private Switch swMic;
    private TextView micVolText;
    private Button btnMuteMic;
    private SeekBar sbMicVol;
    private LevelView micLevel;
    private Spinner spIn, spSrc;
    private SpectrogramView svMic, svCap;
    // camera
    private Switch swCam, swMirror;
    private LinearLayout camBody;
    private TextView[] facingSeg;
    private TextureView preview;
    private FrameLayout previewBox;
    private Surface previewSurface;
    private SurfaceTexture previewTexture;
    private int previewW = 640, previewH = 360;
    private Spinner spCamSize, spCamFps, spCamQuality, spCamRot;
    // connection
    private TextView[] linkSeg;
    private TextView linkDesc, wifiWarn, linkWhere, linkProblem, pinWarn;
    private LinearLayout netBody, hostBox, btBox;
    private EditText etHost, etPin;
    private Button btnSaveNet;
    private Switch swEncrypt;
    private Spinner spBt, spCodec;
    private final List<String> btAddrs = new ArrayList<>(), btNames = new ArrayList<>();
    // phone sound -> PC
    private Switch swCap;
    // latency
    private TextView[] presetSeg;
    private TextView presetDesc, tvBuf, tvMicBuf, fineToggle;
    private SeekBar sbBuf, sbMicBuf;
    private LinearLayout fineBody;
    // advanced
    private Switch swAwake;
    private EditText etSpkPort, etMicPort, etCamPort;
    private TextView portsError;
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

    /** Explanatory text: same size everywhere, a bit of line spacing so it reads on the dark cards. */
    private TextView hint(CharSequence s, int color) {
        TextView t = text(s, 13, color, false);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** setText that skips the relayout when nothing changed (the status refreshes 4 times a second). */
    private static void setIfChanged(TextView t, CharSequence s) {
        if (!TextUtils.equals(t.getText(), s)) t.setText(s);
    }

    /** Touch feedback on a rounded area; fill 0 = transparent. */
    private RippleDrawable pressable(int fill, float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), fill == 0 ? null : round(fill, radiusPx),
                round(0xFFFFFFFF, radiusPx));
    }

    private static TextWatcher onEdit(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                r.run();
            }
        };
    }

    /** A stream's live status: dot + line + detail, in its own card and in the summary on Home. */
    private final class Status {
        final View dot = new View(MainActivity.this), homeDot = new View(MainActivity.this);
        final TextView line = text("", 15, TEXT, true), detail = text("", 12, TEXT2, false);
        final TextView homeLine = text("", 14, TEXT, false), homeDetail = text("", 12, TEXT2, false);
        private int color;

        Status() {
            homeDetail.setMaxLines(2);
            homeDetail.setEllipsize(TextUtils.TruncateAt.END);
        }

        /** problem: the detail is an error message, shown in amber so it stands out. */
        void set(int c, String s, String d, boolean problem) {
            if (c != color) {
                color = c;
                paintDot(dot, c);
                paintDot(homeDot, c);
            }
            setIfChanged(line, s);
            setIfChanged(homeLine, s);
            setIfChanged(detail, d);
            setIfChanged(homeDetail, d);
            int dc = problem ? AMBER : TEXT2;
            if (detail.getCurrentTextColor() != dc) {
                detail.setTextColor(dc);
                homeDetail.setTextColor(dc);
            }
            int vis = d.isEmpty() ? View.GONE : View.VISIBLE;
            detail.setVisibility(vis);
            homeDetail.setVisibility(vis);
        }
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

    /** Status line: colored dot + bold text; detail line below in grey (amber for errors). */
    private void statusLines(LinearLayout card, Status st) {
        LinearLayout r = row();
        r.setPadding(0, dp(6), 0, 0);
        r.addView(st.dot, lp(dp(10), dp(10)));
        LinearLayout.LayoutParams tp = weight(1);
        tp.leftMargin = dp(10);
        r.addView(st.line, tp);
        card.addView(r);
        st.detail.setPadding(dp(20), dp(2), 0, 0);
        card.addView(st.detail);
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
            s.setForeground(pressable(0, dp(10)));
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
        // rounded box + a drop-down arrow (a plain background hides the platform's arrow)
        Drawable arrow = getDrawable(R.drawable.ic_drop).mutate();
        arrow.setTint(TEXT2);
        LayerDrawable bg = new LayerDrawable(new Drawable[]{round(CARD_HI, dp(10)), arrow});
        bg.setLayerGravity(1, Gravity.END | Gravity.CENTER_VERTICAL);
        bg.setLayerSize(1, dp(24), dp(24));
        bg.setLayerInsetEnd(1, dp(8));
        sp.setBackground(bg);
        sp.setPadding(dp(6), 0, dp(36), 0);
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

        // status strip on top, one scrolling page per tab, navigation bar at the bottom
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        // takes the focus off a text field when switching tabs (that saves it), without a highlight
        root.setFocusableInTouchMode(true);
        root.setDefaultFocusHighlightEnabled(false);
        buildStrip(root);
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buildNav(root);

        spkSt = new Status();
        micSt = new Status();
        capSt = new Status();
        camSt = new Status();
        linkSt = new Status();

        LinearLayout home = page(content, TAB_HOME);
        home.setPadding(dp(16), dp(20), dp(16), dp(24));
        buildHeader(home);
        buildHero(home);
        buildSummary(home);
        buildFooter(home);

        buildConnection(page(content, TAB_LINK));

        LinearLayout audio = page(content, TAB_AUDIO);
        buildOutput(audio);
        buildMic(audio);
        applyVolumes(false);
        buildCapture(audio);

        buildCamera(page(content, TAB_CAM));

        LinearLayout settings = page(content, TAB_SETTINGS);
        buildLatency(settings);
        buildAdvanced(settings);

        // short confirmation that a setting was saved / applied, above the navigation bar
        noticeBar = text("", 14, TEXT, false);
        noticeBar.setBackground(round(CARD_HI, dp(12)));
        noticeBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        noticeBar.setElevation(dp(6));
        noticeBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        np.setMargins(dp(24), 0, dp(24), dp(12));
        content.addView(noticeBar, np);

        // status / navigation bar insets (edge-to-edge on new Android versions)
        final int nl = navBar.getPaddingLeft(), nt = navBar.getPaddingTop(), nr = navBar.getPaddingRight(),
                nb = navBar.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), 0);
            navBar.setPadding(nl, nt, nr, nb + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        applyAwake();
        showTab(b == null ? TAB_HOME : b.getInt(STATE_TAB, TAB_HOME), false);

        // not when recreated or reopened from Recents: that would replay periphone's autostart
        if (b == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handleIntent(getIntent());
        }
        requestMissingPermissions();
    }

    /** One request for everything the enabled streams need (overlapping requests get dropped). */
    private void requestMissingPermissions() {
        List<String> perms = new ArrayList<>();
        if ((prefs.getBoolean(Cfg.K_MIC, true) || prefs.getBoolean(Cfg.K_CAP, false))
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 31 && prefs.getInt(Cfg.K_LINK, Cfg.LINK_USB) == Cfg.LINK_BT
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
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

    // ------------------------------------------------------------------ tabs

    /** A tab's scrolling page; returns the column to put its cards in. */
    private LinearLayout page(FrameLayout content, int t) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(24));
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);
        sv.setVisibility(View.GONE);
        content.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages[t] = sv;
        return col;
    }

    /** Overall state + Start/Stop, on every tab but Home (Home has the big version). Tap: go Home. */
    private void buildStrip(LinearLayout root) {
        strip = row();
        strip.setBackground(pressable(CARD, dp(16)));
        strip.setPadding(dp(14), dp(6), dp(6), dp(6));
        strip.setOnClickListener(v -> showTab(TAB_HOME, true));
        stripDot = new View(this);
        strip.addView(stripDot, lp(dp(10), dp(10)));
        stripTitle = text("", 15, TEXT, true);
        stripTitle.setSingleLine(true);
        stripTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = weight(1);
        tp.leftMargin = dp(10);
        strip.addView(stripTitle, tp);
        stripBtn = pillButton();
        stripBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stripBtn.setOnClickListener(v -> onToggle());
        strip.addView(stripBtn, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(16), dp(12), dp(16), dp(4));
        root.addView(strip, p);
    }

    private void buildNav(LinearLayout root) {
        navBar = row();
        navBar.setBackgroundColor(CARD);
        navBar.setPadding(dp(4), dp(8), dp(4), dp(8));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int t = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setBackground(pressable(0, dp(16)));
            item.setPadding(0, dp(2), 0, dp(2));
            item.setOnClickListener(v -> showTab(t, true));
            ImageView iv = new ImageView(this);
            iv.setImageResource(TAB_ICONS[i]);
            iv.setPadding(dp(17), dp(4), dp(17), dp(4));
            item.addView(iv, lp(dp(56), dp(30)));
            TextView label = text(getString(TAB_NAMES[i]), 12, TEXT2, false);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setPadding(dp(2), dp(3), dp(2), 0);
            // "Connection" has to fit five across on a narrow phone
            label.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP);
            item.addView(label, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
            navIcons[i] = iv;
            navLabels[i] = label;
            navBar.addView(item, weight(1));
        }
        root.addView(navBar, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showTab(int t, boolean animate) {
        if (t == tab) {
            pages[t].smoothScrollTo(0, 0);              // tapping the current tab again: back to its top
            return;
        }
        // leaving a text field saves it (focus listener) and closes the keyboard
        View focus = getCurrentFocus();
        if (focus instanceof EditText) {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            root.requestFocus();
        }
        tab = t;
        for (int i = 0; i < pages.length; i++) {
            boolean on = i == t;
            pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            navIcons[i].setColorFilter(on ? ACCENT : TEXT2);
            navIcons[i].setBackground(on ? round(0x335B8CFF, dp(15)) : null);
            navLabels[i].setTextColor(on ? TEXT : TEXT2);
            navLabels[i].setTypeface(on ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        }
        strip.setVisibility(t == TAB_HOME ? View.GONE : View.VISIBLE);
        if (animate) {
            pages[t].setAlpha(0f);
            pages[t].animate().alpha(1f).setDuration(120).start();
        }
        if (t == TAB_LINK) showLink();                 // fresh paired-device list and Wi-Fi band
        updatePreviewTarget();                          // the camera draws into the preview only on its tab
        ui.removeCallbacks(specTick);                   // spectrograms / meters only run on their tab
        if (resumed) ui.post(specTick);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_TAB, tab);
    }

    /** Back from another tab goes Home first. */
    @Override
    @SuppressWarnings("deprecation")   // onBackPressed: the app doesn't opt in to predictive back
    public void onBackPressed() {
        if (tab != TAB_HOME) showTab(TAB_HOME, true);
        else super.onBackPressed();
    }

    /** Brief confirmation above the navigation bar. */
    private void notice(CharSequence s) {
        noticeBar.animate().cancel();
        noticeBar.setText(s);
        noticeBar.setAlpha(1f);
        noticeBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideNotice);
        ui.postDelayed(hideNotice, 2200);
    }

    private final Runnable hideNotice = () ->
            noticeBar.animate().alpha(0f).setDuration(200).withEndAction(() -> noticeBar.setVisibility(View.GONE)).start();

    /** A setting changed by the user: apply it, and say whether it's live or waits for Start. */
    private void settingChanged() {
        applyIfRunning();
        if (!reflecting) notice(getString(Stats.running ? R.string.applied_live : R.string.applied_next));
    }

    // ------------------------------------------------------------------ cards

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

    /** Every stream at a glance, with a quick on/off switch; tap a row to open its tab. */
    private void buildSummary(LinearLayout root) {
        LinearLayout c = card(root);
        c.setPadding(dp(8), dp(6), dp(8), dp(6));
        summaryRow(c, R.drawable.ic_link, R.string.link_title, linkSt, null, TAB_LINK);
        homeSwSpk = quickSwitch(R.string.out_title, Cfg.K_SPK, true, () -> swSpk);
        summaryRow(c, R.drawable.ic_speaker, R.string.out_title, spkSt, homeSwSpk, TAB_AUDIO);
        homeSwMic = quickSwitch(R.string.mic_title, Cfg.K_MIC, true, () -> swMic);
        LinearLayout mic = summaryRow(c, R.drawable.ic_mic, R.string.mic_title, micSt, homeSwMic, TAB_AUDIO);
        homeMicLevel = new LevelView(this);
        LinearLayout.LayoutParams mp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        mp.setMargins(dp(16), dp(6), 0, dp(2));
        mic.addView(homeMicLevel, mp);
        homeSwCap = quickSwitch(R.string.cap_title, Cfg.K_CAP, false, () -> swCap);
        summaryRow(c, R.drawable.ic_phone_audio, R.string.cap_title, capSt, homeSwCap, TAB_AUDIO);
        homeSwCam = quickSwitch(R.string.cam_title, Cfg.K_CAM, false, () -> swCam);
        summaryRow(c, R.drawable.ic_camera, R.string.cam_title, camSt, homeSwCam, TAB_CAM);
    }

    /** Home copy of a stream's switch: flips the real one (which does the permission + apply work). */
    private Switch quickSwitch(int title, String key, boolean def, Supplier<Switch> target) {
        Switch s = new Switch(this);
        s.setChecked(prefs.getBoolean(key, def));
        s.setContentDescription(getString(title));
        s.setOnCheckedChangeListener((v, on) -> target.get().setChecked(on));
        return s;
    }

    /** Returns the text column so the caller can add to it. */
    private LinearLayout summaryRow(LinearLayout c, int icon, int title, Status st, Switch sw, int target) {
        if (c.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(STROKE);
            LinearLayout.LayoutParams dp1 = lp(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dp1.setMargins(dp(42), 0, dp(8), 0);
            c.addView(div, dp1);
        }
        LinearLayout r = row();
        r.setBackground(pressable(0, dp(12)));
        r.setPadding(dp(8), dp(10), dp(8), dp(10));
        r.setOnClickListener(v -> showTab(target, true));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(ACCENT);
        r.addView(iv, lp(dp(22), dp(22)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(getString(title), 15, TEXT, true));
        LinearLayout sr = row();
        sr.setPadding(0, dp(3), 0, 0);
        sr.addView(st.homeDot, lp(dp(8), dp(8)));
        LinearLayout.LayoutParams tp = weight(1);
        tp.leftMargin = dp(8);
        sr.addView(st.homeLine, tp);
        col.addView(sr);
        st.homeDetail.setPadding(dp(16), dp(1), 0, 0);
        col.addView(st.homeDetail);
        LinearLayout.LayoutParams cp = weight(1);
        cp.setMargins(dp(12), 0, dp(8), 0);
        r.addView(col, cp);

        if (sw != null) {
            r.addView(sw);
        } else {
            ImageView more = new ImageView(this);
            more.setImageResource(R.drawable.ic_chevron);
            more.setColorFilter(TEXT2);
            r.addView(more, lp(dp(24), dp(24)));
        }
        c.addView(r, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    private void buildConnection(LinearLayout root) {
        LinearLayout c = card(root);
        cardHeader(c, R.drawable.ic_link, R.string.link_title, null);
        linkSeg = segmented(c, LINK_NAMES, v -> setLink((Integer) v.getTag()));
        linkDesc = hint("", TEXT2);
        linkDesc.setPadding(0, dp(10), 0, 0);
        c.addView(linkDesc);
        wifiWarn = hint(getString(R.string.wifi_24_warning), AMBER);
        wifiWarn.setPadding(0, dp(8), 0, 0);
        c.addView(wifiWarn);
        // live: where the streams went, or what's stopping them (right under the mode, not at the bottom)
        linkWhere = text("", 13, GREEN, false);
        linkWhere.setPadding(0, dp(8), 0, 0);
        c.addView(linkWhere);
        linkProblem = hint("", AMBER);
        linkProblem.setPadding(0, dp(8), 0, 0);
        c.addView(linkProblem);

        netBody = new LinearLayout(this);
        netBody.setOrientation(LinearLayout.VERTICAL);
        c.addView(netBody);

        hostBox = new LinearLayout(this);
        hostBox.setOrientation(LinearLayout.VERTICAL);
        netBody.addView(hostBox);
        hostBox.addView(fieldLabel(R.string.pc_address));
        etHost = textField(prefs.getString(Cfg.K_HOST, ""), R.string.pc_address_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostBox.addView(etHost, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        btBox = new LinearLayout(this);
        btBox.setOrientation(LinearLayout.VERTICAL);
        netBody.addView(btBox);
        spBt = spinner(btBox, R.string.bt_pc, btNames, 0, pos -> {
            if (pos >= btAddrs.size()) return;
            prefs.edit().putString(Cfg.K_BT_ADDR, btAddrs.get(pos)).apply();
            settingChanged();
        });

        netBody.addView(fieldLabel(R.string.pin));
        etPin = textField(prefs.getString(Cfg.K_PIN, ""), R.string.pin_hint, InputType.TYPE_CLASS_NUMBER);
        netBody.addView(etPin, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        pinWarn = hint(getString(R.string.err_no_pin), AMBER);
        pinWarn.setPadding(0, dp(6), 0, 0);
        netBody.addView(pinWarn);

        swEncrypt = new Switch(this);
        swEncrypt.setText(R.string.encrypt);
        swEncrypt.setTextColor(TEXT);
        swEncrypt.setTextSize(15);
        swEncrypt.setChecked(prefs.getBoolean(Cfg.K_ENCRYPT, true));
        swEncrypt.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(Cfg.K_ENCRYPT, on).apply();
            settingChanged();
        });
        LinearLayout.LayoutParams ep = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        ep.topMargin = dp(6);
        netBody.addView(swEncrypt, ep);

        spCodec = spinner(netBody, R.string.codec, strings(CODEC_NAMES),
                prefs.getInt(Cfg.K_CODEC, Cfg.CODEC_AUTO), pos -> {
                    if (prefs.getInt(Cfg.K_CODEC, Cfg.CODEC_AUTO) == pos) return;
                    prefs.edit().putInt(Cfg.K_CODEC, pos).apply();
                    settingChanged();
                });

        // lit up while the address / PIN differ from what's saved
        btnSaveNet = pillButton();
        btnSaveNet.setOnClickListener(v -> saveNet());
        LinearLayout.LayoutParams sp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        sp.topMargin = dp(14);
        netBody.addView(btnSaveNet, sp);
        TextView auto = hint(getString(R.string.net_autosave_hint), TEXT2);
        auto.setPadding(0, dp(6), 0, 0);
        netBody.addView(auto);

        showLink();
        onNetEdited();
    }

    private EditText textField(String value, int hint, int type) {
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT2);
        e.setSingleLine(true);
        e.setBackground(round(CARD_HI, dp(10)));
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setOnEditorActionListener((v, action, ev) -> {
            saveNet();
            return false;
        });
        e.addTextChangedListener(onEdit(this::onNetEdited));
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) saveNet();                        // leaving the field saves it
        });
        return e;
    }

    private void buildCapture(LinearLayout root) {
        LinearLayout c = card(root);
        swCap = new Switch(this);
        swCap.setChecked(prefs.getBoolean(Cfg.K_CAP, false));
        swCap.setOnCheckedChangeListener((v, on) -> {
            homeSwCap.setChecked(on);
            prefs.edit().putBoolean(Cfg.K_CAP, on).apply();
            if (on && !reflecting) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
                if (Stats.running) requestProjection();
            }
            settingChanged();
        });
        cardHeader(c, R.drawable.ic_phone_audio, R.string.cap_title, swCap);
        statusLines(c, capSt);
        TextView hint = hint(getString(R.string.cap_hint), TEXT2);
        hint.setPadding(0, dp(8), 0, 0);
        c.addView(hint);
        svCap = new SpectrogramView(this, Stats.capTap);
        spectrogram(c, svCap, R.string.spec_out_cap);
    }

    private void buildOutput(LinearLayout root) {
        LinearLayout c = card(root);
        swSpk = new Switch(this);
        swSpk.setChecked(prefs.getBoolean(Cfg.K_SPK, true));
        swSpk.setOnCheckedChangeListener((v, on) -> {
            homeSwSpk.setChecked(on);
            prefs.edit().putBoolean(Cfg.K_SPK, on).apply();
            settingChanged();
        });
        cardHeader(c, R.drawable.ic_speaker, R.string.out_title, swSpk);

        statusLines(c, spkSt);

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
        spkBtTip = hint(getString(R.string.bt_tip), AMBER);
        spkBtTip.setPadding(0, dp(8), 0, 0);
        c.addView(spkBtTip);

        svSpk = new SpectrogramView(this, Stats.spkTap);
        spectrogram(c, svSpk, R.string.spec_in_spk);
    }

    private void buildMic(LinearLayout root) {
        LinearLayout c = card(root);
        swMic = new Switch(this);
        swMic.setChecked(prefs.getBoolean(Cfg.K_MIC, true));
        swMic.setOnCheckedChangeListener((v, on) -> {
            homeSwMic.setChecked(on);
            prefs.edit().putBoolean(Cfg.K_MIC, on).apply();
            if (on && !reflecting
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
            settingChanged();
        });
        cardHeader(c, R.drawable.ic_mic, R.string.mic_title, swMic);

        statusLines(c, micSt);

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
                    settingChanged();
                });

        svMic = new SpectrogramView(this, Stats.micTap);
        spectrogram(c, svMic, R.string.spec_out_mic);
        refreshDevices();
    }

    private void buildCamera(LinearLayout root) {
        LinearLayout c = card(root);
        swCam = new Switch(this);
        swCam.setChecked(prefs.getBoolean(Cfg.K_CAM, false));
        swCam.setOnCheckedChangeListener((v, on) -> {
            homeSwCam.setChecked(on);
            prefs.edit().putBoolean(Cfg.K_CAM, on).apply();
            camBody.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on && !reflecting
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            }
            updatePreviewTarget();
            settingChanged();
        });
        cardHeader(c, R.drawable.ic_camera, R.string.cam_title, swCam);

        statusLines(c, camSt);

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
                    settingChanged();
                });
        List<String> fps = new ArrayList<>();
        for (int f : CAM_FPS) fps.add(getString(R.string.fps_n, f));
        spCamFps = spinner(camBody, R.string.cam_fps, fps,
                indexOf(CAM_FPS, prefs.getInt(Cfg.K_CAM_FPS, Cfg.DEFAULT_CAM_FPS), 2), pos -> {
                    if (prefs.getInt(Cfg.K_CAM_FPS, Cfg.DEFAULT_CAM_FPS) == CAM_FPS[pos]) return;
                    prefs.edit().putInt(Cfg.K_CAM_FPS, CAM_FPS[pos]).apply();
                    settingChanged();
                });
        spCamQuality = spinner(camBody, R.string.cam_quality, strings(CAM_QUALITY),
                prefs.getInt(Cfg.K_CAM_QUALITY, Cfg.DEFAULT_CAM_QUALITY), pos -> {
                    if (prefs.getInt(Cfg.K_CAM_QUALITY, Cfg.DEFAULT_CAM_QUALITY) == pos) return;
                    prefs.edit().putInt(Cfg.K_CAM_QUALITY, pos).apply();
                    settingChanged();
                });
        List<String> rots = new ArrayList<>();
        rots.add(getString(R.string.rot_auto));
        for (int i = 1; i < CAM_ROTS.length; i++) rots.add(getString(R.string.rot_n, CAM_ROTS[i]));
        spCamRot = spinner(camBody, R.string.cam_rotation, rots,
                indexOf(CAM_ROTS, prefs.getInt(Cfg.K_CAM_ROT, -1), 0), pos -> {
                    if (prefs.getInt(Cfg.K_CAM_ROT, -1) == CAM_ROTS[pos]) return;
                    prefs.edit().putInt(Cfg.K_CAM_ROT, CAM_ROTS[pos]).apply();
                    settingChanged();
                });

        swMirror = new Switch(this);
        swMirror.setText(R.string.cam_mirror);
        swMirror.setTextColor(TEXT);
        swMirror.setTextSize(15);
        swMirror.setChecked(prefs.getBoolean(Cfg.K_CAM_MIRROR, false));
        swMirror.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(Cfg.K_CAM_MIRROR, on).apply();
            settingChanged();
        });
        LinearLayout.LayoutParams mp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        mp.topMargin = dp(6);
        camBody.addView(swMirror, mp);

        TextView hint = hint(getString(R.string.cam_hint), TEXT2);
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
        presetDesc = hint("", TEXT2);
        presetDesc.setPadding(0, dp(10), 0, 0);
        c.addView(presetDesc);

        fineToggle = text("", 14, ACCENT, true);
        fineToggle.setPadding(0, dp(10), 0, dp(10));
        fineToggle.setBackground(pressable(0, dp(8)));
        LinearLayout.LayoutParams fp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(4);
        c.addView(fineToggle, fp);
        fineBody = new LinearLayout(this);
        fineBody.setOrientation(LinearLayout.VERTICAL);
        c.addView(fineBody);
        fineToggle.setOnClickListener(v -> setFine(fineBody.getVisibility() != View.VISIBLE));

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
        setFine(showBuf() < 0);                         // custom values: show the sliders right away
    }

    private void setFine(boolean show) {
        fineBody.setVisibility(show ? View.VISIBLE : View.GONE);
        fineToggle.setText(getString(R.string.fine_tune) + (show ? "  ▾" : "  ▸"));
    }

    private void buildAdvanced(LinearLayout root) {
        LinearLayout c = card(root);
        LinearLayout h = cardHeader(c, R.drawable.ic_tune, R.string.advanced, null);
        advArrow = text("▸", 16, TEXT2, false);
        h.addView(advArrow);
        h.setBackground(pressable(0, dp(10)));
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

        TextView bg = hint(getString(R.string.bg_hint), TEXT2);
        bg.setPadding(0, dp(6), 0, dp(6));
        advBody.addView(bg);
        Button bgBtn = pillButton();
        bgBtn.setText(R.string.bg_settings);
        stylePill(bgBtn, CARD_HI);
        bgBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) {
            }
        });
        advBody.addView(bgBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        advBody.addView(fieldLabel(R.string.ports));
        LinearLayout ports = row();
        etSpkPort = portField(prefs.getInt(Cfg.K_SPK_PORT, Cfg.DEFAULT_SPK_PORT), R.string.port_out);
        etMicPort = portField(prefs.getInt(Cfg.K_MIC_PORT, Cfg.DEFAULT_MIC_PORT), R.string.port_mic);
        etCamPort = portField(prefs.getInt(Cfg.K_CAM_PORT, Cfg.DEFAULT_CAM_PORT), R.string.port_cam);
        for (EditText e : new EditText[]{etSpkPort, etMicPort, etCamPort}) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(e.getHint(), 12, TEXT2, false));
            col.addView(e, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            LinearLayout.LayoutParams p = weight(1);
            p.rightMargin = dp(8);
            ports.addView(col, p);
        }
        advBody.addView(ports);
        portsError = hint(getString(R.string.ports_invalid), RED);   // live, while typing
        portsError.setPadding(0, dp(6), 0, 0);
        portsError.setVisibility(View.GONE);
        advBody.addView(portsError);
        TextView portHint = hint(getString(R.string.ports_hint), TEXT2);
        portHint.setPadding(0, dp(6), 0, 0);
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
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.reset_confirm)
                .setPositiveButton(R.string.reset_defaults, (d, w) -> resetDefaults())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
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

    /** Spectrogram with a caption saying which way the sound goes (into or out of the phone). */
    private void spectrogram(LinearLayout c, SpectrogramView v, int caption) {
        TextView t = text(getString(caption), 12, TEXT2, true);
        t.setPadding(0, dp(14), 0, dp(4));
        c.addView(t);
        v.setBackground(round(0xFF000000, dp(10)));
        v.setClipToOutline(true);
        c.addView(v, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));
    }

    private EditText portField(int value, int hint) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setHint(hint);
        e.setText(String.valueOf(value));
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT2);
        e.setSingleLine(true);
        e.setBackground(round(CARD_HI, dp(10)));
        e.setPadding(dp(12), 0, dp(12), 0);
        e.addTextChangedListener(onEdit(this::onPortsEdited));
        e.setOnEditorActionListener((v, action, ev) -> {
            savePorts();                                // Done on the keyboard = Save ports
            return false;
        });
        return e;
    }

    // ------------------------------------------------------------------ actions

    private void setLink(int mode) {
        boolean changed = prefs.getInt(Cfg.K_LINK, Cfg.LINK_USB) != mode;
        prefs.edit().putInt(Cfg.K_LINK, mode).apply();
        showLink();
        if (mode == Cfg.LINK_BT && Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
        if (changed) settingChanged();
        else applyIfRunning();
    }

    private void showLink() {
        int mode = prefs.getInt(Cfg.K_LINK, Cfg.LINK_USB);
        selectSeg(linkSeg, mode);
        linkDesc.setText(LINK_DESC[mode]);
        wifiWarn.setVisibility(mode == Cfg.LINK_WIFI && on24GHz() ? View.VISIBLE : View.GONE);
        netBody.setVisibility(mode == Cfg.LINK_USB ? View.GONE : View.VISIBLE);
        hostBox.setVisibility(mode == Cfg.LINK_WIFI || mode == Cfg.LINK_PAN ? View.VISIBLE : View.GONE);
        btBox.setVisibility(mode == Cfg.LINK_BT ? View.VISIBLE : View.GONE);
        if (mode == Cfg.LINK_BT) refreshBt();
    }

    /** 2.4 GHz Wi-Fi: crowded and slow, measured 100+ ms spikes and 3 % loss vs ~0 on 5 GHz. */
    @SuppressWarnings("deprecation")   // getConnectionInfo: still the simple way for the band
    private boolean on24GHz() {
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
        int f = wi == null ? -1 : wi.getFrequency();
        return f > 0 && f < 3000;
    }

    /** Paired devices to pick the PC from (needs BLUETOOTH_CONNECT on Android 12+). */
    @SuppressWarnings("MissingPermission")
    private void refreshBt() {
        btAddrs.clear();
        List<String> names = new ArrayList<>();
        String saved = prefs.getString(Cfg.K_BT_ADDR, "");
        int sel = 0;
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        boolean allowed = Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (ad != null && allowed) {
            try {
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    btAddrs.add(d.getAddress());
                    names.add((d.getName() == null ? "?" : d.getName()) + "  (" + d.getAddress() + ")");
                    if (d.getAddress().equals(saved)) sel = btAddrs.size() - 1;
                }
            } catch (SecurityException ignored) {
            }
        }
        if (btAddrs.isEmpty()) {
            btAddrs.add("");
            names.add(getString(ad == null ? R.string.err_bt_none : !allowed ? R.string.err_bt_permission
                    : R.string.bt_none_paired));
        }
        btNames.clear();
        btNames.addAll(names);
        spBt.setAdapter(adapter(new ArrayList<>(btNames)));
        select(spBt, sel);
        if (!btAddrs.get(sel).equals(saved)) prefs.edit().putString(Cfg.K_BT_ADDR, btAddrs.get(sel)).apply();
    }

    private void saveNet() {
        String host = etHost.getText().toString().trim(), pin = etPin.getText().toString().trim();
        if (host.equals(prefs.getString(Cfg.K_HOST, "")) && pin.equals(prefs.getString(Cfg.K_PIN, ""))) return;
        prefs.edit().putString(Cfg.K_HOST, host).putString(Cfg.K_PIN, pin).apply();
        Stats.pcAddr = null;
        Stats.pinFails = 0;
        Stats.pinRetryAt = 0;
        Stats.pinMismatches = 0;
        notice(getString(R.string.link_saved));
        onNetEdited();
        applyIfRunning();
    }

    /** Typing in the address / PIN: light up Save while it differs from what's saved; warn on an empty PIN. */
    private void onNetEdited() {
        String host = etHost.getText().toString().trim(), pin = etPin.getText().toString().trim();
        boolean dirty = !host.equals(prefs.getString(Cfg.K_HOST, "")) || !pin.equals(prefs.getString(Cfg.K_PIN, ""));
        btnSaveNet.setEnabled(dirty);
        btnSaveNet.setText(dirty ? R.string.save_link : R.string.link_saved_all);
        btnSaveNet.setTextColor(dirty ? 0xFFFFFFFF : TEXT2);
        stylePill(btnSaveNet, dirty ? ACCENT : CARD_HI);
        pinWarn.setVisibility(pin.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Ports: say right away when they're out of range or repeated (Save would refuse them). */
    private void onPortsEdited() {
        int s = port(etSpkPort, -1), m = port(etMicPort, -1), c = port(etCamPort, -1);
        boolean bad = s < 0 || m < 0 || c < 0 || s == m || s == c || m == c;
        portsError.setVisibility(bad ? View.VISIBLE : View.GONE);
    }

    /**
     * Battery optimization lets the system (and Xiaomi-style ROMs much more) kill the bridge in the
     * background. Ask once for the exemption; periphone grants it over adb anyway.
     */
    @SuppressWarnings("BatteryLife")   // exactly what this app needs: a long-running audio link
    private void askBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        if (prefs.getBoolean(Cfg.K_BATT_ASKED, false)) return;
        prefs.edit().putBoolean(Cfg.K_BATT_ASKED, true).apply();
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Playback capture needs the screen-capture consent; asked for each session (Android rule). */
    private void requestProjection() {
        if (Build.VERSION.SDK_INT < 29 || Stats.projection != null) return;
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent i = Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        try {
            startActivityForResult(i, REQ_PROJ);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.err_cap_android, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")   // startActivityForResult: no AndroidX here
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_PROJ) return;
        if (result == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_START)
                    .putExtra(BridgeService.EXTRA_PROJ_CODE, result)
                    .putExtra(BridgeService.EXTRA_PROJ_DATA, data));
        } else {
            Toast.makeText(this, R.string.err_cap_consent, Toast.LENGTH_LONG).show();
        }
    }

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

    /** Returns the matching preset, -1 = custom. */
    private int showBuf() {
        int out = sbBuf.getProgress() + Cfg.MIN_BUF_MS, mic = sbMicBuf.getProgress() + Cfg.MIN_BUF_MS;
        tvBuf.setText(getString(R.string.out_buffer_ms, out));
        tvMicBuf.setText(getString(R.string.mic_buffer_ms, mic));
        int sel = -1;
        for (int i = 0; i < Cfg.PRESETS.length; i++) {
            if (Cfg.PRESETS[i][0] == out && Cfg.PRESETS[i][1] == mic) sel = i;
        }
        selectSeg(presetSeg, sel);
        presetDesc.setText(sel >= 0 ? getString(PRESET_DESC[sel]) : getString(R.string.preset_custom_desc, out, mic));
        return sel;
    }

    private void saveBuffers() {
        prefs.edit()
                .putInt(Cfg.K_BUF, sbBuf.getProgress() + Cfg.MIN_BUF_MS)
                .putInt(Cfg.K_MIC_BUF, sbMicBuf.getProgress() + Cfg.MIN_BUF_MS)
                .apply();
        showBuf();
        settingChanged();
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
        notice(getString(R.string.ports_saved));
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
        notice(getString(R.string.defaults_restored));
        applyIfRunning();
    }

    /** Puts the saved settings back on the screen (after autostart extras or a reset). */
    private void reflectPrefs() {
        reflecting = true;
        swSpk.setChecked(prefs.getBoolean(Cfg.K_SPK, true));
        swMic.setChecked(prefs.getBoolean(Cfg.K_MIC, true));
        swCam.setChecked(prefs.getBoolean(Cfg.K_CAM, false));
        swCap.setChecked(prefs.getBoolean(Cfg.K_CAP, false));
        swEncrypt.setChecked(prefs.getBoolean(Cfg.K_ENCRYPT, true));
        etHost.setText(prefs.getString(Cfg.K_HOST, ""));
        etPin.setText(prefs.getString(Cfg.K_PIN, ""));
        select(spCodec, prefs.getInt(Cfg.K_CODEC, Cfg.CODEC_AUTO));
        showLink();
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
            saveNet();                       // an address / PIN still being typed counts
            askBatteryExemption();
            Stats.pinFails = 0;              // the user asked: try again right away
            Stats.pinRetryAt = 0;
            Stats.pinMismatches = 0;
            startBridge();
            if (prefs.getBoolean(Cfg.K_CAP, false)) requestProjection();
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
        if (i.getBooleanExtra("stopbridge", false)) {   // the PC stopped: stop too, stay out of the way
            i.removeExtra("stopbridge");
            if (Stats.running) {
                startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP));
            }
            moveTaskToBack(true);
            return;
        }
        SharedPreferences.Editor e = prefs.edit();
        Cfg.applyExtras(e, i);
        e.apply();
        Stats.pcAddr = null;
        Stats.pinFails = 0;
        Stats.pinRetryAt = 0;
        Stats.pinMismatches = 0;
        reflectPrefs();
        camBody.setVisibility(swCam.isChecked() ? View.VISIBLE : View.GONE);
        askBatteryExemption();
        startBridge();
        if (prefs.getBoolean(Cfg.K_CAP, false)) requestProjection();
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
        if (code == REQ_BT || prefs.getInt(Cfg.K_LINK, Cfg.LINK_USB) == Cfg.LINK_BT) refreshBt();
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

    /** The camera renders into the preview only while it is visible (app in front, Camera tab shown). */
    private void updatePreviewTarget() {
        Surface want = resumed && tab == TAB_CAM && swCam != null && swCam.isChecked() ? previewSurface : null;
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
        lastCapBytes = Stats.capBytes;
        ui.post(tick);
        ui.post(specTick);
        if (watcher == null) watcher = new DeviceWatcher(this::refreshDevices);
        am.registerAudioDeviceCallback(watcher, ui);
        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, ui);
        updatePreviewTarget();
        // back from the system settings: paired devices / Wi-Fi band may have changed
        if (tab == TAB_LINK) showLink();
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

    /** Spectrograms + mic meters, ~30 fps, only while a tab showing them is in front. */
    private void onSpecTick() {
        int peak = Stats.micConnected ? Stats.micPeak : 0;
        if (tab == TAB_AUDIO) {
            if (swMic.isChecked()) svMic.update();
            if (swSpk.isChecked()) svSpk.update();
            if (swCap.isChecked()) svCap.update();
            micLevel.setPeak(peak);
        } else if (tab == TAB_HOME) {
            homeMicLevel.setPeak(peak);
        } else {
            return;                                     // nothing live here; showTab restarts it
        }
        ui.postDelayed(specTick, 33);
    }

    /** Mute / Start buttons: restyled only when their state flips, not every tick. */
    private void mutePill(Button b, boolean muted) {
        if (Boolean.valueOf(muted).equals(b.getTag())) return;
        b.setTag(muted);
        b.setText(muted ? R.string.unmute : R.string.mute);
        stylePill(b, muted ? RED : CARD_HI);
    }

    private void startPill(Button b, boolean run) {
        if (Boolean.valueOf(run).equals(b.getTag())) return;
        b.setTag(run);
        b.setText(run ? R.string.stop : R.string.start);
        stylePill(b, run ? CARD_HI : ACCENT);
        b.setTextColor(run ? RED : 0xFFFFFFFF);
    }

    private int seenPrefsPushed = Stats.prefsPushed;

    private void onTick() {
        if (seenPrefsPushed != Stats.prefsPushed) {   // the PC changed settings in the background
            seenPrefsPushed = Stats.prefsPushed;
            reflectPrefs();
        }
        long now = System.currentTimeMillis();
        double dt = Math.max(1, now - lastT) / 1000.0;
        double spkRate = (Stats.spkBytes - lastSpkBytes) / dt / 1024.0;
        double micRate = (Stats.micBytes - lastMicBytes) / dt / 1024.0;
        double camRate = (Stats.camBytes - lastCamBytes) / dt / 1024.0;
        double camFps = (Stats.camFrames - lastCamFrames) / dt;
        double capRate = (Stats.capBytes - lastCapBytes) / dt / 1024.0;
        lastCapBytes = Stats.capBytes;
        lastT = now;
        lastSpkBytes = Stats.spkBytes;
        lastMicBytes = Stats.micBytes;
        lastCamBytes = Stats.camBytes;
        lastCamFrames = Stats.camFrames;

        boolean run = Stats.running;
        boolean spkOn = swSpk.isChecked(), micOn = swMic.isChecked(), camOn = swCam.isChecked();
        boolean capOn = swCap.isChecked();
        int mode = prefs.getInt(Cfg.K_LINK, Cfg.LINK_USB);
        boolean usb = mode == Cfg.LINK_USB;

        // --- output
        if (!spkOn) {
            spkSt.set(GRAY, getString(R.string.st_off), "", false);
        } else if (!run) {
            spkSt.set(GRAY, getString(R.string.st_not_running), "", false);
        } else if (Stats.spkConnected) {
            int lat = Stats.spkLatencyMs >= 0 ? Stats.spkLatencyMs : Stats.spkQueuedMs;
            spkSt.set(Stats.muteSpk ? RED : GREEN,
                    getString(Stats.muteSpk ? R.string.st_spk_muted : R.string.st_spk_on, lat),
                    getString(R.string.st_spk_detail, Stats.spkQueuedMs, Math.round(spkRate),
                            Stats.spkUnderruns, Stats.spkDroppedMs), false);
        } else {
            spkSt.set(AMBER, getString(R.string.st_waiting), Stats.spkError, true);
        }
        mutePill(btnMuteSpk, Stats.muteSpk);

        // --- mic
        if (!micOn) {
            micSt.set(GRAY, getString(R.string.st_off), "", false);
        } else if (!run) {
            micSt.set(GRAY, getString(R.string.st_not_running), Stats.micError, true);
        } else if (Stats.micConnected) {
            micSt.set(Stats.muteMic ? RED : GREEN,
                    getString(Stats.muteMic ? R.string.st_mic_muted : R.string.st_mic_on),
                    getString(R.string.st_mic_detail, Math.round(micRate)), false);
        } else {
            micSt.set(AMBER, getString(R.string.st_waiting), Stats.micError, true);
        }
        mutePill(btnMuteMic, Stats.muteMic);

        // --- camera
        if (!camOn) {
            camSt.set(GRAY, getString(R.string.st_off), "", false);
        } else if (!run) {
            camSt.set(GRAY, getString(R.string.st_not_running), Stats.camError, true);
        } else if (Stats.camConnected) {
            camSt.set(GREEN, getString(Stats.camFront ? R.string.st_cam_front : R.string.st_cam_back),
                    getString(R.string.st_cam_detail, Stats.camInfo, camFps, Math.round(camRate)), false);
        } else {
            camSt.set(AMBER, getString(R.string.st_waiting), Stats.camError, true);
        }

        // --- phone sound -> PC
        if (!capOn) {
            capSt.set(GRAY, getString(R.string.st_off), "", false);
        } else if (!run) {
            capSt.set(GRAY, getString(R.string.st_not_running), Stats.capError, true);
        } else if (Stats.capConnected) {
            capSt.set(GREEN, getString(R.string.st_cap_on),
                    getString(R.string.st_mic_detail, Math.round(capRate)), false);
        } else {
            capSt.set(AMBER, getString(R.string.st_waiting), Stats.capError, true);
        }

        // --- overall
        int waiting = 0, live = 0;
        if (capOn) { if (Stats.capConnected) live++; else waiting++; }
        if (spkOn) { if (Stats.spkConnected) live++; else waiting++; }
        if (micOn) { if (Stats.micConnected) live++; else waiting++; }
        if (camOn) { if (Stats.camConnected) live++; else waiting++; }
        int color, title, hint;
        if (!run) {
            color = GRAY;
            title = R.string.hero_stopped;
            hint = R.string.hero_stopped_hint;
        } else if (live > 0 && waiting == 0) {
            color = GREEN;
            title = R.string.hero_connected;
            hint = R.string.hero_connected_hint;
        } else if (live > 0) {
            color = AMBER;
            title = R.string.hero_partial;
            hint = R.string.hero_partial_hint;
        } else if (waiting == 0) {
            color = AMBER;
            title = R.string.hero_nothing;
            hint = R.string.hero_nothing_hint;
        } else {
            color = AMBER;
            title = R.string.hero_waiting;
            hint = usb ? R.string.hero_waiting_hint : R.string.hero_waiting_net_hint;
        }
        if (color != overallColor) {
            overallColor = color;
            paintDot(heroDot, color);
            paintDot(stripDot, color);
        }
        setIfChanged(heroTitle, getText(title));
        setIfChanged(stripTitle, getText(title));
        setIfChanged(heroHint, getText(hint));
        startPill(btnStart, run);
        startPill(stripBtn, run);

        // --- connection: where the streams went, or the first thing stopping them
        String where = !usb && run && live > 0 ? getString(R.string.link_where, Stats.linkWhere) : "";
        setIfChanged(linkWhere, where);
        linkWhere.setVisibility(where.isEmpty() ? View.GONE : View.VISIBLE);
        String problem = "";
        if (run && live == 0) {
            for (String e : new String[]{spkOn ? Stats.spkError : "", micOn ? Stats.micError : "",
                    capOn ? Stats.capError : "", camOn ? Stats.camError : ""}) {
                if (e != null && !e.isEmpty()) {
                    problem = e;
                    break;
                }
            }
        }
        setIfChanged(linkProblem, problem);
        linkProblem.setVisibility(problem.isEmpty() ? View.GONE : View.VISIBLE);
        String linkName = getString(LINK_NAMES[mode]);
        if (!usb && prefs.getString(Cfg.K_PIN, "").trim().isEmpty()) {
            linkSt.set(AMBER, linkName, getString(R.string.err_no_pin), true);
        } else if (run && live > 0) {
            linkSt.set(GREEN, linkName, where, false);
        } else if (run) {
            linkSt.set(AMBER, linkName, problem, true);
        } else {
            linkSt.set(GRAY, linkName, "", false);
        }

        ui.postDelayed(tick, 250);
    }
}
