package com.spaceos.os;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Canvas-drawn console row so module lists match the bridge exactly. */
public class RowView extends View {

    private Row row;
    private int index;
    private boolean down;

    public RowView(Context c) {
        super(c);
        setMinimumHeight((int) Skin.dp(58));
    }

    public void bind(Row r, int idx) {
        this.row = r;
        this.index = idx;
        invalidate();
    }

    public void setDown(boolean d) { down = d; invalidate(); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        boolean tall = row != null && (row.gauge >= 0 || (row.sub != null && row.sub.length() > 0));
        setMeasuredDimension(w, (int) Skin.dp(tall ? 60 : 46));
    }

    @Override protected void onDraw(Canvas c) {
        if (row == null) return;
        int w = getWidth(), h = getHeight();
        float padL = Skin.dp(14), padR = Skin.dp(14);

        if (down) c.drawColor(Skin.alpha(Skin.PLASMA, 0.10f));

        // left rail marker
        Paint rail = Skin.fill(Skin.alpha(row.accent, down ? 1f : 0.55f));
        c.drawRect(0, Skin.dp(10), Skin.dp(2.5f), h - Skin.dp(10), rail);

        float x = padL;

        // icon slot
        if (row.icon != null) {
            int s = (int) Skin.dp(30);
            int top = (h - s) / 2;
            row.icon.setBounds((int) x, top, (int) x + s, top + s);
            row.icon.draw(c);
            // framing bracket around the icon
            Skin.brackets(c, new RectF(x - Skin.dp(3), top - Skin.dp(3),
                    x + s + Skin.dp(3), top + s + Skin.dp(3)),
                    Skin.alpha(Skin.EDGE, 1f), 5, 1f);
            x += s + Skin.dp(12);
        } else if (index >= 0) {
            Paint idx = Skin.text(7.5f, Skin.alpha(Skin.PLASMA_D, 1f), true);
            c.drawText(String.format("%03d", index), x, h / 2f + Skin.dp(3), idx);
            x += Skin.dp(30);
        }
        // index < 0 means "no numbering" — settings rows are not a list of
        // records, and stamping 000 on every one of them was pure noise.

        // value on the right, measured first so the title can be clipped to fit
        Paint vp = Skin.text(10, row.accent, true);
        float valW = 0;
        if (row.value != null && row.value.length() > 0) {
            valW = vp.measureText(row.value) + Skin.dp(10);
            Skin.textRight(c, row.value, w - padR,
                    row.sub.length() > 0 ? h / 2f - Skin.dp(1) : h / 2f + Skin.dp(4), vp);
        }

        float avail = w - padR - x - valW;

        Paint tp = Skin.text(11.5f, Skin.TEXT, true);
        String title = ellipsize(row.title, tp, avail);
        c.drawText(title, x, row.sub.length() > 0 ? h / 2f - Skin.dp(1) : h / 2f + Skin.dp(4), tp);

        if (row.sub.length() > 0) {
            Paint sp = Skin.text(8, Skin.TEXT_DIM, false);
            c.drawText(ellipsize(row.sub, sp, avail), x, h / 2f + Skin.dp(13), sp);
        }

        if (row.gauge >= 0) {
            RectF g = new RectF(x, h - Skin.dp(11), w - padR, h - Skin.dp(7));
            Skin.gauge(c, g, row.gauge, Skin.level(row.gauge));
        }

        // hairline
        Paint hair = Skin.stroke(0.7f, Skin.alpha(Skin.EDGE, 0.7f));
        c.drawLine(padL, h - 0.5f, w - padR, h - 0.5f, hair);
    }

    private String ellipsize(String s, Paint p, float max) {
        if (s == null) return "";
        if (max <= 0) return s;
        if (p.measureText(s) <= max) return s;
        int n = p.breakText(s, true, max - p.measureText("..."), null);
        if (n <= 0) return "";
        return s.substring(0, n) + "...";
    }

    private static final Rect TMP = new Rect();
}
