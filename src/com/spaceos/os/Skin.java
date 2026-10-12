package com.spaceos.os;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * The Space OS visual language. Everything on screen is drawn from these
 * primitives so the whole system reads as one instrument.
 */
public final class Skin {

    // ---- palette ---------------------------------------------------------
    // NOT final. A `static final int` with a literal initialiser is a compile
    // time constant, and javac inlines it at every use site — re-assigning it
    // at runtime would change nothing anywhere. These are plain mutable
    // statics so Theme.apply() actually repaints the whole system.
    public static int VOID      = 0xFF000509;  // hull interior
    public static int VOID_2    = 0xFF03101A;  // panel fill
    public static int GRID      = 0xFF0B2733;  // structural grid
    public static int EDGE      = 0xFF14485C;  // panel edge
    public static int PLASMA    = 0xFF35E8FF;  // primary readout
    public static int PLASMA_D  = 0xFF0E7C93;  // dimmed readout
    public static int ION       = 0xFF6BFF9E;  // nominal / go
    public static int SOLAR     = 0xFFFFC24A;  // caution
    public static int FLARE     = 0xFFFF5A6E;  // critical
    public static int MAGENTA   = 0xFFD46BFF;  // secondary channel
    public static int TEXT      = 0xFFBDEEFF;  // body text
    public static int TEXT_DIM  = 0xFF5C8A9B;  // label text

    public static float density = 3f;

    private Skin() {}

    public static void init(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        density = dm.density;
    }

    /** dp -> px */
    public static float dp(float v) { return v * density; }

    /**
     * Dresses an activity window to match the active palette.
     *
     * The theme hardcodes windowBackground to black, and the DecorView paints
     * that anywhere the content view does not reach — which on a light palette
     * showed as a black band above and below everything. The system bar
     * colours were hardcoded near-black in four separate files for the same
     * reason. Both now follow the palette, and on a light palette the system
     * bar ICONS are flipped to dark so they remain visible.
     *
     * @param fullscreen hide the status bar entirely
     */
    public static void dressWindow(android.app.Activity a, boolean fullscreen) {
        try {
            android.view.Window win = a.getWindow();
            win.addFlags(android.view.WindowManager.LayoutParams
                    .FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            // THE BLACK BAR AT THE TOP.
            //
            // On a phone with a punch-hole or notch, Android's default cutout
            // mode refuses to lay content out in that region once the status
            // bar is hidden — it letterboxes the window below the cutout and
            // paints the gap with the window background. No amount of
            // immersive flags or inset arithmetic touches it, because the
            // window itself was never allowed up there. SHORT_EDGES is the
            // switch that lets the window own the full height.
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = win.getAttributes();
                lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                win.setAttributes(lp);
            }

            // the decor's own background, behind everything
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(VOID));
            win.setStatusBarColor(VOID);
            win.setNavigationBarColor(VOID);

            boolean light = Theme.currentIsLight();
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

            // dark icons on a light bar, or they vanish
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            if (fullscreen) {
                flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }
            win.getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
    }

    public static Typeface mono() { return Typeface.MONOSPACE; }

    public static Paint text(float sizeDp, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        p.setTextSize(dp(sizeDp));
        p.setColor(color);
        return p;
    }

    public static Paint stroke(float widthDp, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(widthDp));
        p.setColor(color);
        return p;
    }

    public static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    /** Colour that tracks a 0..1 load value: green -> amber -> red. */
    public static int level(float t) {
        if (t < 0.6f) return ION;
        if (t < 0.85f) return SOLAR;
        return FLARE;
    }

    public static int alpha(int color, float a) {
        int v = Math.max(0, Math.min(255, (int) (a * 255)));
        return (color & 0x00FFFFFF) | (v << 24);
    }

    /**
     * The signature Space OS frame: a rectangle whose corners are cut away and
     * replaced with bracket ticks, like a machined bulkhead panel.
     */
    public static void panel(Canvas c, RectF r, int edge, int fillColor, float cutDp) {
        float cut = dp(cutDp);
        Path path = new Path();
        path.moveTo(r.left + cut, r.top);
        path.lineTo(r.right - cut, r.top);
        path.lineTo(r.right, r.top + cut);
        path.lineTo(r.right, r.bottom - cut);
        path.lineTo(r.right - cut, r.bottom);
        path.lineTo(r.left + cut, r.bottom);
        path.lineTo(r.left, r.bottom - cut);
        path.lineTo(r.left, r.top + cut);
        path.close();

        if (fillColor != 0) c.drawPath(path, fill(fillColor));
        if (edge != 0) c.drawPath(path, stroke(1f, edge));
    }

    /** Corner brackets only — used for framing live regions. */
    public static void brackets(Canvas c, RectF r, int color, float armDp, float wDp) {
        Paint p = stroke(wDp, color);
        float a = dp(armDp);
        c.drawLine(r.left, r.top, r.left + a, r.top, p);
        c.drawLine(r.left, r.top, r.left, r.top + a, p);
        c.drawLine(r.right, r.top, r.right - a, r.top, p);
        c.drawLine(r.right, r.top, r.right, r.top + a, p);
        c.drawLine(r.left, r.bottom, r.left + a, r.bottom, p);
        c.drawLine(r.left, r.bottom, r.left, r.bottom - a, p);
        c.drawLine(r.right, r.bottom, r.right - a, r.bottom, p);
        c.drawLine(r.right, r.bottom, r.right, r.bottom - a, p);
    }

    /** Horizontal segmented bar — the standard Space OS gauge. */
    public static void gauge(Canvas c, RectF r, float t, int color) {
        t = Math.max(0f, Math.min(1f, t));
        int segs = 28;
        float gap = dp(1.5f);
        float segW = (r.width() - gap * (segs - 1)) / segs;
        int lit = (int) Math.ceil(t * segs);
        Paint on = fill(color);
        Paint off = fill(alpha(color, 0.13f));
        for (int i = 0; i < segs; i++) {
            float x = r.left + i * (segW + gap);
            c.drawRect(x, r.top, x + segW, r.bottom, i < lit ? on : off);
        }
    }

    /** Set false to drop the CRT overlay entirely. */
    public static boolean scanlinesEnabled = true;

    /**
     * Static CRT scanlines only.
     *
     * There used to be a gradient band sweeping top to bottom over this. In a
     * cyan palette it read as a faint instrument wash; in MONO, where PLASMA is
     * near-white, the same band became a white strobe crossing the screen every
     * few seconds. An effect whose intensity depends on the palette is not an
     * effect, it is a bug — so the sweep is gone and the lines stay still.
     */
    public static void scanlines(Canvas c, int w, int h, float phase) {
        if (!scanlinesEnabled) return;
        Paint p = new Paint();
        p.setColor(alpha(PLASMA, 0.022f));
        float step = dp(3f);
        for (float y = 0; y < h; y += step) {
            c.drawRect(0, y, w, y + dp(1f), p);
        }
    }

    /** Background structural grid. */
    public static void grid(Canvas c, int w, int h, float cellDp) {
        Paint p = stroke(0.6f, alpha(GRID, 0.65f));
        float cell = dp(cellDp);
        for (float x = 0; x < w; x += cell) c.drawLine(x, 0, x, h, p);
        for (float y = 0; y < h; y += cell) c.drawLine(0, y, w, y, p);
    }

    /** Star field seeded so it is stable frame to frame. */
    public static void stars(Canvas c, int w, int h, float phase, int count) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        long seed = 0x5DEECE66DL;
        for (int i = 0; i < count; i++) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            float x = ((seed >>> 16) % 10000) / 10000f * w;
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            float y = ((seed >>> 16) % 10000) / 10000f * h;
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            float tw = ((seed >>> 16) % 1000) / 1000f;
            float a = 0.15f + 0.5f * (float) Math.abs(Math.sin(phase * 0.03f + tw * 6.28f));
            // Hardcoded white is invisible on the light palettes — the "star"
            // is really just a fleck of contrast against the ground.
            int fleck = i % 7 == 0 ? PLASMA : (Theme.currentIsLight() ? TEXT : 0xFFFFFFFF);
            p.setColor(alpha(fleck, a * 0.6f));
            c.drawCircle(x, y, dp(tw > 0.93f ? 1.3f : 0.7f), p);
        }
    }

    // =====================================================================
    // BLOOM
    // =====================================================================
    //
    // BlurMaskFilter is unreliable on a hardware-accelerated canvas across the
    // API range we support, so glow is built by hand: the same shape stroked
    // several times, widening and fading. Costs a few draw calls and works
    // everywhere.

    public static void bloomCircle(Canvas c, float cx, float cy, float r,
                                   int color, float coreWidthDp, float spreadDp) {
        for (int i = 4; i >= 1; i--) {
            float t = i / 4f;
            Paint p = stroke(coreWidthDp + spreadDp * t, alpha(color, 0.055f * (1.1f - t)));
            c.drawCircle(cx, cy, r, p);
        }
        c.drawCircle(cx, cy, r, stroke(coreWidthDp, color));
    }

    public static void bloomArc(Canvas c, RectF box, float start, float sweep,
                                int color, float coreWidthDp, float spreadDp) {
        for (int i = 3; i >= 1; i--) {
            float t = i / 3f;
            Paint p = stroke(coreWidthDp + spreadDp * t, alpha(color, 0.07f * (1.1f - t)));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(box, start, sweep, false, p);
        }
        Paint live = stroke(coreWidthDp, color);
        live.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(box, start, sweep, false, live);
    }

    /** A soft filled halo — used behind the reactor and under pressed tiles. */
    public static void halo(Canvas c, float cx, float cy, float r, int color, float strength) {
        for (int i = 6; i >= 1; i--) {
            float t = i / 6f;
            c.drawCircle(cx, cy, r * (0.4f + 0.6f * t),
                    fill(alpha(color, strength * 0.10f * (1.05f - t))));
        }
    }

    /** Darkens the frame edges so the centre reads as the focal point. */
    public static void vignette(Canvas c, int w, int h, float strength) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = w / 2f, cy = h / 2f;
        float r = (float) Math.hypot(w, h) * 0.62f;
        p.setShader(new android.graphics.RadialGradient(cx, cy, r,
                new int[]{0x00000000, 0x00000000, (int) (strength * 255) << 24},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    /** Hairline rule with a bright nub at one end — a recurring motif. */
    public static void rule(Canvas c, float x1, float y, float x2, int color, float nubDp) {
        c.drawLine(x1, y, x2, y, stroke(0.8f, alpha(color, 0.55f)));
        c.drawRect(x1, y - dp(1f), x1 + dp(nubDp), y + dp(1f), fill(color));
    }

    /** Right-aligned text helper. */
    public static void textRight(Canvas c, String s, float right, float y, Paint p) {
        c.drawText(s, right - p.measureText(s), y, p);
    }

    /** Centred text helper. */
    public static void textCenter(Canvas c, String s, float cx, float y, Paint p) {
        c.drawText(s, cx - p.measureText(s) / 2f, y, p);
    }
}
