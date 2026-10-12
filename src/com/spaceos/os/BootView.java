package com.spaceos.os;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * Cold-start POST. Runs once per process launch, about 1.4s, then hands the
 * surface to the bridge. Purely decorative — but it is the first thing anyone
 * sees, so it does the work of establishing what Space OS is.
 */
public class BootView extends View {

    public interface OnDone { void onBootComplete(); }

    private static final String[] LINES = {
            "SPACE OS KERNEL .......... OK",
            "REACTOR HANDSHAKE ........ OK",
            "MEMORY CORE MAP .......... OK",
            "ARCHIVE VOLUME MOUNT ..... OK",
            "INERTIAL DECK CALIBRATE .. OK",
            "COMMS ARRAY SYNC ......... OK",
            "UPLINK NEGOTIATION ....... OK",
            "MODULE LATTICE LOAD ...... OK",
    };

    private static final float DURATION = 1.45f;   // seconds

    private OnDone done;
    private float t = 0f;
    private long lastNs = 0;
    private boolean running, fired;

    public BootView(Context c) { super(c); }

    public void setOnDone(OnDone d) { this.done = d; }

    public void start() {
        if (running) return;
        running = true;
        lastNs = 0;
        postOnAnimation(tick);
    }

    public void stop() { running = false; removeCallbacks(tick); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 1f / 60f : (now - lastNs) / 1_000_000_000f;
            lastNs = now;
            if (dt > 0.1f) dt = 0.1f;
            t += dt;
            invalidate();
            if (t >= DURATION) {
                if (!fired) { fired = true; if (done != null) done.onBootComplete(); }
                running = false;
                return;
            }
            postOnAnimation(this);
        }
    };

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float p = Math.min(1f, t / DURATION);

        c.drawColor(Skin.VOID);
        Skin.stars(c, w, h, t * 20f, 70);

        float cx = w / 2f, cy = h * 0.36f;

        // opening iris
        float iris = ease(Math.min(1f, p / 0.35f));
        float r = Skin.dp(78) * iris;
        c.drawCircle(cx, cy, r, Skin.stroke(1.4f, Skin.alpha(Skin.EDGE, iris)));
        c.drawCircle(cx, cy, r * 0.72f, Skin.stroke(1f, Skin.alpha(Skin.PLASMA_D, iris * 0.8f)));

        // spin-up ticks
        Paint tick = Skin.stroke(1.2f, Skin.alpha(Skin.PLASMA, iris));
        for (int i = 0; i < 48; i++) {
            double a = Math.toRadians(t * 220 + i * 7.5);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float len = i % 6 == 0 ? Skin.dp(9) : Skin.dp(4);
            c.drawLine(cx + ca * r, cy + sa * r, cx + ca * (r + len), cy + sa * (r + len), tick);
        }

        // charge arc
        RectF arc = new RectF(cx - r * 0.86f, cy - r * 0.86f, cx + r * 0.86f, cy + r * 0.86f);
        c.drawArc(arc, 135, 270, false, Skin.stroke(4f, Skin.alpha(Skin.PLASMA, 0.14f)));
        Paint live = Skin.stroke(4f, Skin.PLASMA);
        live.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(arc, 135, 270 * p, false, live);

        // wordmark
        float wm = ease(clamp((p - 0.12f) / 0.30f));
        Paint title = Skin.text(26, Skin.alpha(Skin.PLASMA, wm), true);
        title.setLetterSpacing(0.30f * wm + 0.05f);
        Skin.textCenter(c, "SPACE OS", cx, cy + Skin.dp(6), title);

        Paint ver = Skin.text(8, Skin.alpha(Skin.TEXT_DIM, wm), false);
        ver.setLetterSpacing(0.24f);
        Skin.textCenter(c, "V1.0.0  ·  " + Sys.hull(), cx, cy + Skin.dp(22), ver);

        // POST log
        float logTop = h * 0.60f;
        Paint mono = Skin.text(9, Skin.TEXT, false);
        Paint ok = Skin.text(9, Skin.ION, true);
        float left = Skin.dp(28);
        int shown = (int) Math.floor(clamp((p - 0.15f) / 0.70f) * LINES.length);
        for (int i = 0; i < Math.min(shown + 1, LINES.length); i++) {
            float rowAlpha = i < shown ? 1f : 0.45f;
            String line = LINES[i];
            String head = line.substring(0, line.length() - 2);
            mono.setColor(Skin.alpha(Skin.TEXT, rowAlpha * 0.85f));
            c.drawText(head, left, logTop + i * Skin.dp(15), mono);
            if (i < shown) {
                ok.setColor(Skin.alpha(Skin.ION, 1f));
                c.drawText("OK", left + mono.measureText(head),
                        logTop + i * Skin.dp(15), ok);
            }
        }

        // progress readout
        Paint pct = Skin.text(8, Skin.TEXT_DIM, true);
        pct.setLetterSpacing(0.2f);
        Skin.textCenter(c, String.format(Locale.US, "INITIALISING  %d%%", Math.round(p * 100)),
                cx, h - Skin.dp(46), pct);

        RectF bar = new RectF(Skin.dp(28), h - Skin.dp(36),
                w - Skin.dp(28), h - Skin.dp(30));
        Skin.gauge(c, bar, p, Skin.PLASMA);

        // fade to the bridge
        float out = clamp((p - 0.86f) / 0.14f);
        if (out > 0) c.drawColor(Skin.alpha(Skin.VOID, out));

        Skin.scanlines(c, w, h, t * 20f);
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static float ease(float x) {
        x = clamp(x);
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }
}
