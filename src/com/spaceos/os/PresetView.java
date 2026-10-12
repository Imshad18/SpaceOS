package com.spaceos.os;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Preset picker. Every card is a live miniature of the layout it selects,
 * drawn in that palette — you see the actual thing before committing, rather
 * than picking a name off a list.
 */
public class PresetView extends View {

    public interface OnPick {
        void onPick(int layout, int palette, int backdrop);
        /** A custom-palette knob moved. which is 0..4, see Theme.setCustom. */
        void onCustomKnob(int which, int value);
        void onClose();
    }

    private static final String[] KNOB_NAMES = {
            "ACCENT HUE", "SATURATION", "BASE HUE", "LIGHTNESS", "CONTRAST"
    };
    private static final String[] KNOB_HINTS = {
            "colour of the instrument glow",
            "how vivid the accents run",
            "tint of the background stock",
            "past halfway inverts to a light theme",
            "how far the text separates from the ground"
    };
    private static final int[] KNOB_MAX = {359, 100, 359, 100, 100};

    private final int[] knobs = {190, 85, 205, 3, 70};
    private final RectF[] knobTracks = new RectF[5];
    private int activeKnob = -1;

    private OnPick listener;

    private int layout, palette, backdrop;
    private float phase;
    private boolean running;
    private long lastNs;

    private final RectF[] layoutCards = new RectF[Theme.LAYOUT_NAMES.length];
    private final RectF[] paletteCards = new RectF[Theme.PALETTE_NAMES.length];
    private final RectF[] backdropCards = new RectF[Theme.BACKDROP_NAMES.length];
    private final RectF closeRect = new RectF();

    private int scrollY = 0;
    private float dragStartY, scrollStart;
    private boolean dragging;
    private int contentHeight = 0;

    public PresetView(Context c) {
        super(c);
        setClickable(true);
        for (int i = 0; i < layoutCards.length; i++) layoutCards[i] = new RectF();
        for (int i = 0; i < paletteCards.length; i++) paletteCards[i] = new RectF();
        for (int i = 0; i < backdropCards.length; i++) backdropCards[i] = new RectF();
        for (int i = 0; i < knobTracks.length; i++) knobTracks[i] = new RectF();
    }

    public void setOnPick(OnPick l) { listener = l; }

    public void setCustomKnobs(int[] values) {
        for (int i = 0; i < knobs.length && i < values.length; i++) knobs[i] = values[i];
        invalidate();
    }

    public void setSelection(int layout, int palette, int backdrop) {
        this.layout = layout;
        this.palette = palette;
        this.backdrop = backdrop;
        invalidate();
    }

    public void start() {
        if (running) return;
        running = true; lastNs = 0; postOnAnimation(tick);
    }

    public void stop() { running = false; removeCallbacks(tick); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 1f / 60f : (now - lastNs) / 1_000_000_000f;
            lastNs = now;
            phase += dt * 20f;
            invalidate();
            postOnAnimation(this);
        }
    };

    // =====================================================================

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Skin.VOID);
        Skin.stars(c, w, h, phase, 40);

        float pad = Skin.dp(14);
        c.save();
        c.translate(0, -scrollY);

        float y = Skin.dp(46);   // clear of the status bar

        // ---- title ----
        Paint title = Skin.text(20, Skin.PLASMA, true);
        title.setLetterSpacing(0.24f);
        c.drawText("PRESETS", pad, y + Skin.dp(18), title);

        Paint sub = Skin.text(8, Skin.TEXT_DIM, false);
        sub.setLetterSpacing(0.16f);
        c.drawText(Theme.LAYOUT_NAMES.length + " layouts · "
                        + Theme.PALETTE_NAMES.length + " palettes · "
                        + Theme.BACKDROP_NAMES.length + " backdrops",
                pad, y + Skin.dp(32), sub);

        y += Skin.dp(52);

        // ---- layouts ----
        y = section(c, "LAYOUT", pad, y, w);
        int cols = 2;
        float gap = Skin.dp(9);
        float cardW = (w - pad * 2 - gap * (cols - 1)) / cols;
        float cardH = Skin.dp(112);

        for (int i = 0; i < Theme.LAYOUT_NAMES.length; i++) {
            int col = i % cols, row = i / cols;
            float cx = pad + col * (cardW + gap);
            float cy = y + row * (cardH + gap);
            // Store CONTENT coordinates. The touch handler converts the screen
            // point with y + scrollY, so adding scrollY here too counted it
            // twice and every hit drifted by exactly the scroll distance.
            layoutCards[i].set(cx, cy, cx + cardW, cy + cardH);
            drawLayoutCard(c, new RectF(cx, cy, cx + cardW, cy + cardH), i);
        }
        y += ((Theme.LAYOUT_NAMES.length + cols - 1) / cols) * (cardH + gap) + Skin.dp(10);

        // ---- palettes ----
        y = section(c, "PALETTE", pad, y, w);
        float pw = (w - pad * 2 - gap * 2) / 3f;
        float ph = Skin.dp(64);
        for (int i = 0; i < Theme.PALETTE_NAMES.length; i++) {
            int col = i % 3, row = i / 3;
            float cx = pad + col * (pw + gap);
            float cy = y + row * (ph + gap);
            paletteCards[i].set(cx, cy, cx + pw, cy + ph);
            drawPaletteCard(c, new RectF(cx, cy, cx + pw, cy + ph), i);
        }
        y += ((Theme.PALETTE_NAMES.length + 2) / 3) * (ph + gap) + Skin.dp(10);

        // ---- custom colour knobs, only while CUSTOM is the live palette ----
        if (palette == Theme.CUSTOM) {
            y = section(c, "MIX YOUR OWN", pad, y, w);
            for (int i = 0; i < 5; i++) {
                y = drawKnob(c, i, pad, y, w);
            }
            y += Skin.dp(6);
        } else {
            for (int i = 0; i < knobTracks.length; i++) knobTracks[i].setEmpty();
        }

        // ---- backdrops ----
        y = section(c, "BACKDROP", pad, y, w);
        float bw = (w - pad * 2 - gap * 2) / 3f;
        float bh = Skin.dp(44);
        for (int i = 0; i < Theme.BACKDROP_NAMES.length; i++) {
            int col = i % 3, row = i / 3;
            float cx = pad + col * (bw + gap);
            float cy = y + row * (bh + gap);
            backdropCards[i].set(cx, cy, cx + bw, cy + bh);

            RectF r = new RectF(cx, cy, cx + bw, cy + bh);
            boolean on = i == backdrop;
            Skin.panel(c, r, on ? Skin.PLASMA : Skin.EDGE,
                    on ? Skin.alpha(Skin.PLASMA, 0.16f) : Skin.alpha(Skin.VOID_2, 0.8f), 5);
            Paint t = Skin.text(8, on ? Skin.PLASMA : Skin.TEXT_DIM, on);
            Skin.textCenter(c, Theme.BACKDROP_NAMES[i], r.centerX(),
                    r.centerY() + Skin.dp(3), t);
        }
        y += ((Theme.BACKDROP_NAMES.length + 2) / 3) * (bh + gap) + Skin.dp(30);

        contentHeight = (int) y;
        c.restore();

        // DONE is drawn AFTER the restore, so it is pinned to the screen and
        // does not scroll away with the content. Its rect is therefore in
        // screen space, and the touch handler must not scroll-correct it.
        Paint close = Skin.text(10, Skin.MAGENTA, true);
        close.setLetterSpacing(0.18f);
        String done = "DONE";
        float cw = close.measureText(done);
        float dy = Skin.dp(34);
        closeRect.set(w - pad - cw - Skin.dp(14), dy,
                w - pad + Skin.dp(6), dy + Skin.dp(30));
        Skin.panel(c, closeRect, Skin.MAGENTA, Skin.alpha(Skin.MAGENTA, 0.14f), 5);
        Skin.textRight(c, done, w - pad, dy + Skin.dp(20), close);

        Skin.scanlines(c, w, h, phase);
    }

    /**
     * One knob: label, live gradient track, current reading. The track is
     * painted in the colours it actually produces, so choosing is a matter of
     * seeing rather than guessing what "hue 212" looks like.
     */
    private float drawKnob(Canvas c, int i, float pad, float y, int w) {
        float rowH = Skin.dp(40);
        float trackY = y + Skin.dp(24);
        float trackL = pad, trackR = w - pad - Skin.dp(44);
        float trackH = Skin.dp(7);

        Paint n = Skin.text(7.5f, Skin.TEXT, true);
        n.setLetterSpacing(0.18f);
        c.drawText(KNOB_NAMES[i], pad, y + Skin.dp(9), n);

        Paint hint = Skin.text(5.5f, Skin.TEXT_DIM, false);
        c.drawText(KNOB_HINTS[i], pad + n.measureText(KNOB_NAMES[i]) + Skin.dp(8),
                y + Skin.dp(9), hint);

        // sample the track in 48 columns of the colour that stop produces
        int segs = 48;
        float segW = (trackR - trackL) / segs;
        for (int s = 0; s < segs; s++) {
            float t = s / (float) (segs - 1);
            c.drawRect(trackL + s * segW, trackY - trackH / 2,
                    trackL + (s + 1) * segW + 1, trackY + trackH / 2,
                    Skin.fill(trackColour(i, t)));
        }
        c.drawRect(new RectF(trackL, trackY - trackH / 2, trackR, trackY + trackH / 2),
                Skin.stroke(0.8f, Skin.alpha(Skin.EDGE, 0.9f)));

        // handle
        float f = knobs[i] / (float) KNOB_MAX[i];
        float hx = trackL + f * (trackR - trackL);
        c.drawCircle(hx, trackY, Skin.dp(7.5f), Skin.fill(Skin.VOID));
        c.drawCircle(hx, trackY, Skin.dp(7.5f), Skin.stroke(1.8f, Skin.PLASMA));
        c.drawCircle(hx, trackY, Skin.dp(3.2f), Skin.fill(trackColour(i, f)));

        Paint v = Skin.text(8, Skin.PLASMA, true);
        Skin.textRight(c, i == 0 || i == 2 ? knobs[i] + "°" : knobs[i] + "%",
                w - pad, trackY + Skin.dp(3), v);

        // Generous vertical grab area — a 7dp track is unhittable with a thumb.
        knobTracks[i].set(trackL - Skin.dp(10), trackY - Skin.dp(16),
                trackR + Skin.dp(10), trackY + Skin.dp(16));
        return y + rowH;
    }

    /** The colour this knob yields at position t, for painting its own track. */
    private int trackColour(int which, float t) {
        switch (which) {
            case 0: return Theme.hsl(t * 359f, 30 + knobs[1] * 0.65f, 55);
            case 1: return Theme.hsl(knobs[0], 30 + t * 65f, 55);
            case 2: return Theme.hsl(t * 359f, 22, knobs[3] < 50 ? 30 : 74);
            case 3: return Theme.hsl(knobs[2], 12, 2f + t * 92f);
            default: {
                boolean dark = knobs[3] < 50;
                return Theme.hsl(knobs[2], 10, dark ? 44f + t * 52f : 56f - t * 52f);
            }
        }
    }

    private float section(Canvas c, String label, float pad, float y, int w) {
        Paint s = Skin.text(8, Skin.TEXT_DIM, true);
        s.setLetterSpacing(0.3f);
        c.drawText(label, pad, y, s);
        c.drawLine(pad + s.measureText(label) + Skin.dp(8), y - Skin.dp(3),
                w - pad, y - Skin.dp(3), Skin.stroke(0.8f, Skin.EDGE));
        return y + Skin.dp(12);
    }

    // =====================================================================
    // CARDS
    // =====================================================================

    /** A live thumbnail of the layout, drawn with that layout's own geometry. */
    private void drawLayoutCard(Canvas c, RectF r, int index) {
        boolean on = index == layout;
        Skin.panel(c, r, on ? Skin.PLASMA : Skin.EDGE,
                on ? Skin.alpha(Skin.PLASMA, 0.12f) : Skin.alpha(Skin.VOID_2, 0.85f), 6);

        float cx = r.centerX(), cy = r.top + r.height() * 0.40f;
        float s = Math.min(r.width(), r.height()) * 0.30f;
        int ink = on ? Skin.PLASMA : Skin.alpha(Skin.PLASMA, 0.55f);
        Paint p = Skin.stroke(1.1f, ink);
        Paint f = Skin.fill(ink);

        switch (index) {
            case Theme.BRIDGE: {
                c.drawCircle(cx, cy - s * 0.15f, s * 0.42f, p);
                for (int i = 0; i < 3; i++) {
                    float by = cy + s * (0.42f + i * 0.20f);
                    c.drawLine(cx - s * 0.8f, by, cx + s * 0.8f * (1f - i * 0.2f), by, p);
                }
                break;
            }
            case Theme.ORBITAL: {
                c.drawCircle(cx, cy, s * 0.26f, f);
                c.drawCircle(cx, cy, s * 0.62f, p);
                c.drawCircle(cx, cy, s * 0.92f, p);
                c.drawCircle(cx + s * 0.62f, cy, s * 0.10f, f);
                c.drawCircle(cx - s * 0.65f, cy + s * 0.65f, s * 0.09f, f);
                break;
            }
            case Theme.TACTICAL: {
                for (int i = 1; i <= 3; i++) c.drawCircle(cx, cy, s * 0.30f * i, p);
                double a = Math.toRadians(phase * 2.5f % 360);
                c.drawLine(cx, cy, cx + (float) Math.cos(a) * s * 0.9f,
                        cy + (float) Math.sin(a) * s * 0.9f, Skin.stroke(1.4f, ink));
                break;
            }
            case Theme.MINIMAL: {
                Paint t = Skin.text(19, ink, false);
                Skin.textCenter(c, "9:41", cx, cy + s * 0.2f, t);
                c.drawLine(cx - s * 0.7f, cy + s * 0.7f, cx + s * 0.3f, cy + s * 0.7f, p);
                break;
            }
            case Theme.MAINFRAME: {
                Paint t = Skin.text(6, ink, false);
                for (int i = 0; i < 6; i++) {
                    c.drawText(i % 2 == 0 ? "> [####....]" : "  0x2f loaded",
                            cx - s * 0.95f, cy - s * 0.6f + i * Skin.dp(7), t);
                }
                break;
            }
            case Theme.REACTOR: {
                c.drawCircle(cx, cy, s * 0.75f, p);
                c.drawCircle(cx, cy, s * 0.52f, Skin.fill(Skin.alpha(ink, 0.30f)));
                c.drawCircle(cx, cy, s * 0.30f, f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45 + phase);
                    c.drawCircle(cx + (float) Math.cos(a) * s * 1.0f,
                            cy + (float) Math.sin(a) * s * 1.0f, s * 0.07f, f);
                }
                break;
            }
            case Theme.HEXCELL: {
                float hs = s * 0.34f;                 // hex radius
                float hw = hs * 1.7320508f;
                for (int hr = 0; hr < 3; hr++) {
                    int count = (hr % 2 == 0) ? 3 : 2;
                    float rowX = cx - hw * 1.5f + ((hr % 2 == 0) ? hw / 2 : hw);
                    for (int i = 0; i < count; i++) {
                        float hx = rowX + i * hw, hy = cy - hs * 1.5f + hr * hs * 1.5f;
                        RectF box = new RectF(hx - hw / 2, hy - hs, hx + hw / 2, hy + hs);
                        boolean lit = (hr * 3 + i) % 3 == 0;
                        if (lit) c.drawPath(Layouts.hexPath(box),
                                Skin.fill(Skin.alpha(ink, 0.28f)));
                        c.drawPath(Layouts.hexPath(box), p);
                    }
                }
                break;
            }
            case Theme.CONSTELLATION: {
                float[] xs = {-0.85f, -0.15f, 0.55f, 0.85f, 0.05f, -0.60f};
                float[] ys = {-0.60f, -0.85f, -0.35f, 0.45f, 0.70f, 0.35f};
                for (int i = 1; i < xs.length; i++) {
                    c.drawLine(cx + xs[i - 1] * s, cy + ys[i - 1] * s,
                            cx + xs[i] * s, cy + ys[i] * s,
                            Skin.stroke(0.8f, Skin.alpha(ink, 0.5f)));
                }
                for (int i = 0; i < xs.length; i++) {
                    float tw = 0.7f + 0.3f * (float) Math.sin(phase * 0.05f + i);
                    c.drawCircle(cx + xs[i] * s, cy + ys[i] * s, s * 0.10f * tw, f);
                }
                break;
            }
            case Theme.HELIX: {
                Path a = new Path(), b = new Path();
                for (int i = 0; i <= 40; i++) {
                    float t = i / 40f;
                    float y = cy - s + t * s * 2f;
                    double ang = t * Math.PI * 3.0;
                    float xa = cx + (float) Math.sin(ang) * s * 0.75f;
                    float xb = cx + (float) Math.sin(ang + Math.PI) * s * 0.75f;
                    if (i == 0) { a.moveTo(xa, y); b.moveTo(xb, y); }
                    else { a.lineTo(xa, y); b.lineTo(xb, y); }
                    if (i % 8 == 0) c.drawLine(xa, y, xb, y,
                            Skin.stroke(0.7f, Skin.alpha(ink, 0.35f)));
                }
                c.drawPath(a, p);
                c.drawPath(b, Skin.stroke(1.1f, Skin.alpha(ink, 0.55f)));
                break;
            }
            case Theme.CASCADE: {
                Path wv = new Path();
                for (float t = 0; t <= 1f; t += 0.02f) {
                    float x = cx - s + t * s * 2f;
                    float y = cy - s * 0.85f
                            + (float) Math.sin(t * 12 - phase * 0.09f) * s * 0.16f;
                    if (t == 0) wv.moveTo(x, y); else wv.lineTo(x, y);
                }
                c.drawPath(wv, p);
                for (int sr = 0; sr < 3; sr++) {
                    for (int col = 0; col < 2; col++) {
                        float bx = cx - s + col * (s * 1.02f);
                        float by = cy - s * 0.42f + sr * (s * 0.48f);
                        RectF strip = new RectF(bx, by, bx + s * 0.94f, by + s * 0.34f);
                        c.drawRoundRect(strip, s * 0.08f, s * 0.08f, p);
                        c.drawLine(strip.left + 1, strip.top + 1,
                                strip.left + 1, strip.bottom - 1,
                                Skin.stroke(1.6f, ink));
                    }
                }
                break;
            }
        }

        Paint name = Skin.text(9, on ? Skin.PLASMA : Skin.TEXT, true);
        name.setLetterSpacing(0.12f);
        Skin.textCenter(c, Theme.LAYOUT_NAMES[index], cx, r.bottom - Skin.dp(17), name);

        Paint desc = Skin.text(6, Skin.TEXT_DIM, false);
        Skin.textCenter(c, Theme.LAYOUT_DESC[index], cx, r.bottom - Skin.dp(7), desc);
    }

    /** Swatch strip in the palette's real colours, regardless of active theme. */
    private void drawPaletteCard(Canvas c, RectF r, int index) {
        boolean on = index == palette;

        int bg = Theme.swatch(index, 1);
        int edge = Theme.swatch(index, 3);
        int primary = Theme.swatch(index, 4);
        int text = Theme.swatch(index, 10);

        Skin.panel(c, r, on ? primary : edge, bg, 5);

        float sw = Skin.dp(9);
        float x = r.left + Skin.dp(9);
        float y = r.top + Skin.dp(12);
        int[] slots = {4, 6, 7, 8, 9};
        for (int i = 0; i < slots.length; i++) {
            Paint p = Skin.fill(Theme.swatch(index, slots[i]));
            c.drawRect(x + i * (sw + Skin.dp(3)), y,
                    x + i * (sw + Skin.dp(3)) + sw, y + sw, p);
        }

        Paint n = Skin.text(8.5f, text, true);
        n.setLetterSpacing(0.14f);
        c.drawText(Theme.PALETTE_NAMES[index], r.left + Skin.dp(9),
                r.bottom - Skin.dp(18), n);

        Paint d = Skin.text(5.5f, Theme.swatch(index, 11), false);
        c.drawText(Theme.PALETTE_DESC[index], r.left + Skin.dp(9),
                r.bottom - Skin.dp(8), d);

        if (on) {
            Skin.brackets(c, new RectF(r.left - Skin.dp(3), r.top - Skin.dp(3),
                    r.right + Skin.dp(3), r.bottom + Skin.dp(3)), primary, 9, 1.4f);
        }
    }

    // =====================================================================
    // TOUCH
    // =====================================================================

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartY = y;
                scrollStart = scrollY;
                dragging = false;

                // Grabbing a knob claims the gesture outright. Without this the
                // vertical slop that starts a scroll fires first and the knob
                // slides out from under the thumb.
                activeKnob = -1;
                for (int i = 0; i < knobTracks.length; i++) {
                    if (!knobTracks[i].isEmpty() && knobTracks[i].contains(x, y + scrollY)) {
                        activeKnob = i;
                        moveKnob(i, x);
                        return true;
                    }
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activeKnob >= 0) { moveKnob(activeKnob, x); return true; }
                if (Math.abs(y - dragStartY) > Skin.dp(8)) dragging = true;
                if (dragging) {
                    scrollY = (int) Math.max(0, Math.min(
                            Math.max(0, contentHeight - getHeight() + Skin.dp(40)),
                            scrollStart - (y - dragStartY)));
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                activeKnob = -1;
                return true;

            case MotionEvent.ACTION_UP:
                if (activeKnob >= 0) { activeKnob = -1; return true; }
                if (dragging) return true;
                performHapticFeedback(1);

                // DONE is pinned, so it is tested in raw screen coordinates.
                if (closeRect.contains(x, y)) {
                    if (listener != null) listener.onClose();
                    return true;
                }
                for (int i = 0; i < layoutCards.length; i++) {
                    if (layoutCards[i].contains(x, y + scrollY)) { pick(i, palette, backdrop); return true; }
                }
                for (int i = 0; i < paletteCards.length; i++) {
                    if (paletteCards[i].contains(x, y + scrollY)) { pick(layout, i, backdrop); return true; }
                }
                for (int i = 0; i < backdropCards.length; i++) {
                    if (backdropCards[i].contains(x, y + scrollY)) { pick(layout, palette, i); return true; }
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void moveKnob(int i, float x) {
        RectF t = knobTracks[i];
        float l = t.left + Skin.dp(10), r = t.right - Skin.dp(10);
        float f = Math.max(0f, Math.min(1f, (x - l) / Math.max(1f, r - l)));
        int v = Math.round(f * KNOB_MAX[i]);
        if (v == knobs[i]) return;
        knobs[i] = v;
        if (listener != null) listener.onCustomKnob(i, v);
        invalidate();
    }

    private void pick(int l, int p, int b) {
        layout = l; palette = p; backdrop = b;
        if (listener != null) listener.onPick(l, p, b);
        invalidate();
    }
}
