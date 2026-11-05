package dev.gabriwar.periphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Horizontal level meter, -60..0 dBFS; green, then amber near the top, red when clipping. */
final class LevelView extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float level;   // 0..1

    LevelView(Context c) {
        super(c);
        bg.setColor(0xFF2A2F37);
    }

    /** peak: 0..32767 */
    void setPeak(int peak) {
        float db = peak <= 0 ? -60f : (float) (20 * Math.log10(peak / 32768.0));
        float l = Math.max(0f, Math.min(1f, (db + 60f) / 60f));
        if (Math.abs(l - level) > 0.005f) {
            level = l;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), rad = h / 2f;
        r.set(0, 0, getWidth(), h);
        c.drawRoundRect(r, rad, rad, bg);
        if (level <= 0f) return;
        fg.setColor(level > 0.97f ? 0xFFFF453A : level > 0.8f ? 0xFFFFB020 : 0xFF34C759);
        r.set(0, 0, Math.max(h, getWidth() * level), h);
        c.drawRoundRect(r, rad, rad, fg);
    }
}
