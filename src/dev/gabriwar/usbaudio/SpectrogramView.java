package dev.gabriwar.usbaudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/** Scrolling spectrogram: time to the right, frequency (log, 50 Hz..24 kHz) up. */
final class SpectrogramView extends View {
    private static final int COLS = 320;
    private static final int ROWS = 128;
    private static final int WIN = 1024;
    private static final int HOP = 768;          // ~16 ms per column -> ~5 s on screen
    private static final double F_MIN = 50.0, F_MAX = 24000.0;
    private static final double DB_FLOOR = -90.0, DB_RANGE = 70.0;

    private final SpectrumTap tap;
    private final int[] pix = new int[COLS * ROWS];
    private final Bitmap bmp = Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888);
    private final float[] re = new float[WIN];
    private final float[] im = new float[WIN];
    private final float[] hann = new float[WIN];
    private final float[] cos = new float[WIN / 2];
    private final float[] sin = new float[WIN / 2];
    private final int[] lo = new int[ROWS];
    private final int[] hi = new int[ROWS];
    private final short[] tmp = new short[WIN];
    private final Rect src = new Rect(0, 0, COLS, ROWS);
    private final Rect dst = new Rect();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint();
    private long readPos;

    SpectrogramView(Context c, SpectrumTap tap) {
        super(c);
        this.tap = tap;
        java.util.Arrays.fill(pix, 0xFF000000);
        for (int i = 0; i < WIN; i++) hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WIN - 1)));
        for (int i = 0; i < WIN / 2; i++) {
            cos[i] = (float) Math.cos(-2 * Math.PI * i / WIN);
            sin[i] = (float) Math.sin(-2 * Math.PI * i / WIN);
        }
        double binHz = 48000.0 / WIN;
        for (int r = 0; r < ROWS; r++) {
            double f0 = F_MIN * Math.pow(F_MAX / F_MIN, r / (double) ROWS);
            double f1 = F_MIN * Math.pow(F_MAX / F_MIN, (r + 1) / (double) ROWS);
            int b0 = clampBin((int) Math.round(f0 / binHz));
            int b1 = clampBin((int) Math.round(f1 / binHz));
            lo[r] = b0;
            hi[r] = Math.max(b0 + 1, b1);        // exclusive
        }
        float d = c.getResources().getDisplayMetrics().density;
        textPaint.setColor(0xCCFFFFFF);
        textPaint.setTextSize(10 * d);
        textPaint.setShadowLayer(2 * d, 0, 0, Color.BLACK);
        linePaint.setColor(0x55FFFFFF);
        linePaint.setStrokeWidth(1);
        readPos = Math.max(0, tap.writePos() - WIN);
        bmp.setPixels(pix, 0, COLS, 0, 0, COLS, ROWS);
    }

    private static int clampBin(int b) {
        return Math.max(1, Math.min(WIN / 2 - 1, b));
    }

    /** Consumes the new samples and draws the matching columns. */
    void update() {
        long w = tap.writePos();
        if (w - readPos > SpectrumTap.SIZE - 2 * WIN) readPos = w - 2 * WIN;   // fell behind
        boolean changed = false;
        while (w - readPos >= WIN) {
            tap.copy(readPos, tmp, WIN);
            column();
            readPos += HOP;
            changed = true;
        }
        if (changed) {
            bmp.setPixels(pix, 0, COLS, 0, 0, COLS, ROWS);
            invalidate();
        }
    }

    private void column() {
        for (int y = 0; y < ROWS; y++) {
            System.arraycopy(pix, y * COLS + 1, pix, y * COLS, COLS - 1);
        }
        for (int i = 0; i < WIN; i++) {
            re[i] = tmp[i] / 32768f * hann[i];
            im[i] = 0f;
        }
        fft();
        final float norm = 4f / WIN;             // full-scale sine peak with Hann = N/4
        for (int r = 0; r < ROWS; r++) {
            float m = 0f;
            for (int k = lo[r]; k < hi[r]; k++) {
                float a = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                if (a > m) m = a;
            }
            double db = 20.0 * Math.log10(m * norm + 1e-9);
            float t = (float) Math.max(0.0, Math.min(1.0, (db - DB_FLOOR) / DB_RANGE));
            pix[(ROWS - 1 - r) * COLS + COLS - 1] = color(t);
        }
    }

    /** FFT radix-2 in-place, N = WIN. */
    private void fft() {
        int n = WIN;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step], wi = sin[k * step];
                    int a = i + k, b = a + half;
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr;        im[a] += xi;
                }
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Black -> purple -> orange -> yellow. */
    private static int color(float t) {
        float r = clamp01(1.6f * t);
        float g = clamp01(2.4f * (t - 0.4f));
        float b = clamp01(1.9f * t) * (t < 0.6f ? 1f : clamp01(1f - 4f * (t - 0.6f)));
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bmp, src, dst, bmpPaint);

        // frequency marks
        int[] marks = {100, 1000, 10000};
        String[] names = {"100 Hz", "1 kHz", "10 kHz"};
        for (int i = 0; i < marks.length; i++) {
            double pos = Math.log(marks[i] / F_MIN) / Math.log(F_MAX / F_MIN);
            float y = (float) (getHeight() * (1.0 - pos));
            canvas.drawLine(0, y, getWidth(), y, linePaint);
            canvas.drawText(names[i], 6, y - 3, textPaint);
        }
    }
}
