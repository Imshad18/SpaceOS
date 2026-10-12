package com.spaceos.os;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * One cell of the app grid: icon over label, in the same cut-corner panel the
 * bridge lattice uses, so switching layout does not switch visual language.
 */
public class AppTileView extends View {

    private Row row;
    private float press;
    private boolean down;
    private long lastNs;
    private boolean animating;

    public AppTileView(Context c) {
        super(c);
        setClickable(true);
    }

    public void bind(Row r) {
        this.row = r;
        press = 0f;
        down = false;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (w * 1.06f));
    }

    @Override protected void onDraw(Canvas c) {
        if (row == null) return;
        int w = getWidth(), h = getHeight();

        float inset = Skin.dp(3);
        RectF r = new RectF(inset, inset, w - inset, h - inset);

        c.save();
        float scale = 1f - 0.06f * press;
        c.translate(w / 2f, h / 2f);
        c.scale(scale, scale);
        c.translate(-w / 2f, -h / 2f);

        Skin.panel(c, r,
                blend(Skin.EDGE, Skin.PLASMA, press),
                blend(Skin.alpha(Skin.VOID_2, 0.75f), Skin.alpha(Skin.PLASMA, 0.18f), press),
                6);

        if (press > 0.01f) {
            RectF halo = new RectF(r.left - Skin.dp(3 * press), r.top - Skin.dp(3 * press),
                    r.right + Skin.dp(3 * press), r.bottom + Skin.dp(3 * press));
            Skin.brackets(c, halo, Skin.alpha(Skin.PLASMA, press * 0.9f), 10, 1.3f);
        }

        // icon
        int s = (int) Math.min(Skin.dp(46), w * 0.46f);
        float top = r.top + (r.height() - s) * 0.36f;
        if (row.icon != null) {
            row.icon.setBounds((int) (w / 2f - s / 2f), (int) top,
                    (int) (w / 2f + s / 2f), (int) (top + s));
            row.icon.draw(c);
        }

        // label, clipped to the cell
        Paint lp = Skin.text(8, blend(Skin.TEXT, Skin.PLASMA, press), true);
        String label = row.title == null ? "" : row.title;
        float max = r.width() - Skin.dp(8);
        if (lp.measureText(label) > max) {
            int n = lp.breakText(label, true, max - lp.measureText("…"), null);
            label = label.substring(0, Math.max(0, n)).trim() + "…";
        }
        Skin.textCenter(c, label, w / 2f, r.bottom - Skin.dp(10), lp);

        c.restore();

        if (animating) step();
    }

    // ---- press animation ---------------------------------------------------

    private void step() {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1f / 60f : (now - lastNs) / 1_000_000_000f;
        lastNs = now;
        if (dt > 0.1f) dt = 0.1f;

        float target = down ? 1f : 0f;
        press += (target - press) * (1f - (float) Math.exp(-16f * dt));
        if (Math.abs(target - press) < 0.004f) {
            press = target;
            animating = false;
            lastNs = 0;
        } else {
            postOnAnimation(invalidator);
        }
    }

    private final Runnable invalidator = new Runnable() {
        @Override public void run() { invalidate(); }
    };

    private void animateTo(boolean pressed) {
        down = pressed;
        if (!animating) { animating = true; lastNs = 0; }
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animateTo(true);
                performHapticFeedback(1);
                return true;
            case MotionEvent.ACTION_MOVE:
                boolean inside = e.getX() >= 0 && e.getX() < getWidth()
                        && e.getY() >= 0 && e.getY() < getHeight();
                if (down != inside) animateTo(inside);
                return true;
            case MotionEvent.ACTION_UP:
                boolean fire = down;
                animateTo(false);
                if (fire && row != null && row.action != null) row.action.run();
                return true;
            case MotionEvent.ACTION_CANCEL:
                animateTo(false);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24), ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24), br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (((int) (aa + (ba - aa) * t)) << 24)
                | (((int) (ar + (br - ar) * t)) << 16)
                | (((int) (ag + (bg - ag) * t)) << 8)
                | ((int) (ab + (bb - ab) * t));
    }
}
