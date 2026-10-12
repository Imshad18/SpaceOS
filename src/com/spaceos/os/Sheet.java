package com.spaceos.os;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * A dialog drawn in the Space OS palette.
 *
 * AlertDialog uses the platform theme, so a notification peek arrived as a
 * grey Material card with blue capital-letter buttons in the middle of an
 * amber CRT — the one surface in the whole app that ignored the palette
 * entirely. This is the same shape, drawn like everything else: bracketed
 * panel, monospace, accent rule, buttons as outlined plates.
 */
public class Sheet {

    public interface Action { void run(); }

    private final Activity host;
    private final String title;
    private String body = "";
    private String badge;
    private final List<String> labels = new ArrayList<String>();
    private final List<Action> actions = new ArrayList<Action>();
    private final List<Integer> colours = new ArrayList<Integer>();

    private Dialog dialog;

    public Sheet(Activity a, String title) {
        this.host = a;
        this.title = title == null ? "" : title;
    }

    public Sheet body(String s) { body = s == null ? "" : s; return this; }

    /** Small label above the title — an app name, a category. */
    public Sheet badge(String s) { badge = s; return this; }

    public Sheet action(String label, int colour, Action a) {
        labels.add(label);
        colours.add(colour);
        actions.add(a);
        return this;
    }

    public void show() {
        try {
            dialog = new Dialog(host);
            Window w = dialog.getWindow();
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (w != null) {
                // Dim the app behind it rather than the platform's flat grey.
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT);
                w.setGravity(Gravity.CENTER);
                w.setDimAmount(0.72f);
            }
            dialog.setContentView(new Face(host));
            dialog.setCanceledOnTouchOutside(true);
            dialog.show();
        } catch (Throwable t) {
            Journal.warn("sheet", "could not show: " + t.getMessage());
        }
    }

    private void dismiss() {
        try { if (dialog != null) dialog.dismiss(); } catch (Throwable ignored) {}
    }

    // =====================================================================

    private class Face extends View {

        private final List<RectF> buttons = new ArrayList<RectF>();
        private int pressed = -1;
        private List<String> wrapped;

        Face(Activity a) { super(a); setClickable(true); }

        private static final float PAD = 18f;
        private static final float BTN_H = 38f;

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int inner = (int) (w - Skin.dp(PAD) * 2 - Skin.dp(24));
            wrapped = wrap(body, bodyPaint(), inner);

            float h = Skin.dp(PAD) * 2;
            if (badge != null) h += Skin.dp(14);
            h += Skin.dp(22);                                   // title
            h += Skin.dp(10);                                   // rule
            h += wrapped.size() * Skin.dp(17);
            h += Skin.dp(14);
            int rows = (int) Math.ceil(labels.size() / 2f);
            h += rows * Skin.dp(BTN_H + 8);
            setMeasuredDimension(w, (int) h);
        }

        private Paint bodyPaint() {
            Paint p = Skin.text(11, Skin.TEXT, false);
            p.setLetterSpacing(0.01f);
            return p;
        }

        @Override protected void onDraw(Canvas c) {
            float m = Skin.dp(12);
            RectF panel = new RectF(m, 0, getWidth() - m, getHeight());

            Skin.panel(c, panel, Skin.PLASMA, Skin.alpha(Skin.VOID_2, 0.98f), 8);
            Skin.brackets(c, new RectF(panel.left - Skin.dp(2), panel.top + Skin.dp(2),
                    panel.right + Skin.dp(2), panel.bottom - Skin.dp(2)),
                    Skin.PLASMA, 12, 1.6f);

            float x = panel.left + Skin.dp(PAD);
            float y = Skin.dp(PAD);

            if (badge != null) {
                Paint bp = Skin.text(7, Skin.TEXT_DIM, true);
                bp.setLetterSpacing(0.3f);
                c.drawText(badge.toUpperCase(java.util.Locale.US), x, y + Skin.dp(6), bp);
                y += Skin.dp(14);
            }

            Paint tp = Skin.text(14, Skin.PLASMA, true);
            tp.setLetterSpacing(0.12f);
            c.drawText(title, x, y + Skin.dp(14), tp);
            y += Skin.dp(22);

            c.drawLine(x, y, panel.right - Skin.dp(PAD), y,
                    Skin.stroke(1f, Skin.alpha(Skin.EDGE, 1f)));
            y += Skin.dp(10);

            Paint bp = bodyPaint();
            for (String line : wrapped) {
                c.drawText(line, x, y + Skin.dp(12), bp);
                y += Skin.dp(17);
            }
            y += Skin.dp(14);

            // Two per row, filling the width — a plate, not a text link.
            buttons.clear();
            float avail = panel.width() - Skin.dp(PAD) * 2;
            float gap = Skin.dp(8);
            for (int i = 0; i < labels.size(); i++) {
                int col = i % 2, row = i / 2;
                boolean lonely = (i == labels.size() - 1) && (labels.size() % 2 == 1);
                float bw = lonely ? avail : (avail - gap) / 2f;
                float bx = x + (lonely ? 0 : col * (bw + gap));
                float by = y + row * (Skin.dp(BTN_H) + gap);
                RectF r = new RectF(bx, by, bx + bw, by + Skin.dp(BTN_H));
                buttons.add(r);

                int accent = colours.get(i);
                boolean down = i == pressed;
                Skin.panel(c, r, accent,
                        Skin.alpha(accent, down ? 0.34f : 0.12f), 6);
                Paint lp = Skin.text(9.5f, accent, true);
                lp.setLetterSpacing(0.16f);
                Skin.textCenter(c, labels.get(i), r.centerX(),
                        r.centerY() + Skin.dp(3.5f), lp);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float px = e.getX(), py = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = hit(px, py);
                    if (pressed >= 0) performHapticFeedback(1);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pressed >= 0 && hit(px, py) != pressed) { pressed = -1; invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP: {
                    int i = pressed;
                    pressed = -1;
                    invalidate();
                    if (i >= 0 && i < actions.size()) {
                        Action a = actions.get(i);
                        dismiss();
                        if (a != null) a.run();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    pressed = -1; invalidate(); return true;
            }
            return super.onTouchEvent(e);
        }

        private int hit(float x, float y) {
            for (int i = 0; i < buttons.size(); i++) {
                if (buttons.get(i).contains(x, y)) return i;
            }
            return -1;
        }
    }

    /** Greedy word wrap, honouring newlines the caller put in. */
    private static List<String> wrap(String text, Paint p, int width) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        for (String para : text.split("\n", -1)) {
            if (para.length() == 0) { out.add(""); continue; }
            StringBuilder line = new StringBuilder();
            for (String word : para.split("\\s+")) {
                String probe = line.length() == 0 ? word : line + " " + word;
                if (p.measureText(probe) <= width || line.length() == 0) {
                    line.setLength(0);
                    line.append(probe);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (line.length() > 0) out.add(line.toString());
            // A very long body is a dialog nobody reads; keep it bounded.
            if (out.size() > 24) { out.add("…"); return out; }
        }
        return out;
    }
}
