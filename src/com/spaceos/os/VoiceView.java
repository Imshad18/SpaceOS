package com.spaceos.os;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * The listening surface. A reactive core that breathes with the microphone
 * level, ringed by a spectrum the speaker's own voice drives.
 *
 * Tapping anywhere dismisses it, so it never traps the user mid-command.
 */
public class VoiceView extends View {

    public interface OnDismiss { void onDismiss(); }

    private OnDismiss dismiss;

    private float phase;
    private boolean running;
    private long lastNs;

    private float level;            // smoothed mic level 0..1
    private float levelTarget;
    private float open;             // 0..1 entry

    private int state = Voice.IDLE;
    private String status = "";
    private String transcript = "";
    private String reply = "";

    /** Rolling spectrum ring; each slot is one historical level sample. */
    private final float[] spectrum = new float[72];
    private int spectrumHead = 0;
    private float sampleAccum = 0;

    public VoiceView(Context c) {
        super(c);
        setClickable(true);
    }

    public void setOnDismiss(OnDismiss d) { this.dismiss = d; }

    public void start() {
        if (running) return;
        running = true;
        open = 0f;
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

            phase += dt * 60f;
            open = approach(open, 1f, dt, 5f);
            level = approach(level, levelTarget, dt, 12f);
            levelTarget *= (1f - Math.min(1f, dt * 2.2f));   // decay between callbacks

            sampleAccum += dt;
            if (sampleAccum > 0.045f) {                       // ~22 samples/sec
                sampleAccum = 0;
                spectrum[spectrumHead] = level;
                spectrumHead = (spectrumHead + 1) % spectrum.length;
            }

            invalidate();
            postOnAnimation(this);
        }
    };

    private static float approach(float cur, float target, float dt, float rate) {
        return cur + (target - cur) * (1f - (float) Math.exp(-rate * dt));
    }

    // =====================================================================

    public void setLevel(float l) { levelTarget = Math.max(levelTarget, l); }

    public void setState(int s, String msg) {
        state = s;
        if (msg != null) status = msg;
        invalidate();
    }

    public void setTranscript(String t) { transcript = t == null ? "" : t; invalidate(); }

    /** Heard, but not addressed to us — drawn dim so it reads as passed over. */
    public void setIgnored(boolean b) { ignored = b; invalidate(); }

    private boolean ignored;
    private final RectF stopButton = new RectF();

    public void setReply(String r) { reply = r == null ? "" : r; invalidate(); }

    public void reset() {
        transcript = ""; reply = ""; status = ""; level = 0; levelTarget = 0;
        state = Voice.IDLE;
        for (int i = 0; i < spectrum.length; i++) spectrum[i] = 0;
    }

    // =====================================================================

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();

        // dim the bridge behind us rather than hiding it
        c.drawColor(Skin.alpha(Skin.VOID, 0.90f * open));
        Skin.stars(c, w, h, phase, 60);

        float cx = w / 2f, cy = h * 0.40f;
        float base = Math.min(w, h) * 0.17f * (0.85f + 0.15f * open);

        int accent = state == Voice.ERROR ? Skin.FLARE
                : state == Voice.SPEAKING ? Skin.MAGENTA
                : state == Voice.THINKING ? Skin.SOLAR
                : Skin.PLASMA;

        // spectrum ring — the user's own voice, last three seconds of it
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setStrokeWidth(Skin.dp(2.4f));
        bar.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < spectrum.length; i++) {
            float v = spectrum[(spectrumHead + i) % spectrum.length];
            double a = Math.toRadians(i * (360.0 / spectrum.length) - 90);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float r0 = base * 1.28f;
            float r1 = r0 + Skin.dp(6) + v * Skin.dp(34);
            bar.setColor(Skin.alpha(accent, (0.20f + 0.70f * v) * open));
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, bar);
        }

        // breathing core
        float pulse = 1f + 0.16f * level + 0.03f * (float) Math.sin(phase * 0.09f);
        float r = base * pulse;
        c.drawCircle(cx, cy, r, Skin.fill(Skin.alpha(Skin.VOID_2, 0.92f * open)));
        c.drawCircle(cx, cy, r, Skin.stroke(1.6f, Skin.alpha(accent, open)));
        c.drawCircle(cx, cy, r * (0.62f + 0.22f * level),
                Skin.stroke(1f, Skin.alpha(accent, 0.55f * open)));

        // counter-rotating reticle
        Paint tick = Skin.stroke(1.2f, Skin.alpha(accent, 0.75f * open));
        for (int i = 0; i < 36; i++) {
            double a = Math.toRadians(-phase * 0.5 + i * 10);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float len = i % 9 == 0 ? Skin.dp(10) : Skin.dp(4);
            float rr = base * 1.14f;
            c.drawLine(cx + ca * rr, cy + sa * rr,
                    cx + ca * (rr + len), cy + sa * (rr + len), tick);
        }

        // thinking sweep
        if (state == Voice.THINKING) {
            RectF arc = new RectF(cx - base * 1.05f, cy - base * 1.05f,
                    cx + base * 1.05f, cy + base * 1.05f);
            Paint sweep = Skin.stroke(3f, Skin.alpha(Skin.SOLAR, open));
            sweep.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(arc, phase * 3f % 360f, 70, false, sweep);
        }

        // centre glyph : a microphone
        drawMic(c, cx, cy, base * 0.36f, Skin.alpha(accent, open));

        // status line
        Paint st = Skin.text(9, Skin.alpha(Skin.TEXT_DIM, open), true);
        st.setLetterSpacing(0.3f);
        String label = state == Voice.LISTENING ? "LISTENING"
                : state == Voice.THINKING ? "PARSING"
                : state == Voice.SPEAKING ? "RESPONDING"
                : state == Voice.ERROR ? "FAULT" : "STANDBY";
        Skin.textCenter(c, label, cx, cy + base * 1.95f, st);

        if (status.length() > 0 && !status.equalsIgnoreCase(label)) {
            Paint sp = Skin.text(8, Skin.alpha(accent, 0.85f * open), false);
            Skin.textCenter(c, status.toUpperCase(Locale.US), cx, cy + base * 1.95f + Skin.dp(14), sp);
        }

        // transcript
        float ty = h * 0.72f;
        if (transcript.length() > 0) {
            Paint tp = Skin.text(15,
                    Skin.alpha(ignored ? Skin.TEXT_DIM : Skin.TEXT, open * (ignored ? 0.6f : 1f)),
                    !ignored);
            drawWrapped(c, "“" + transcript + "”", cx, ty, w - Skin.dp(48), tp);
        }

        // reply
        if (reply.length() > 0) {
            Paint rp = Skin.text(11, Skin.alpha(Skin.ION, open), false);
            drawWrapped(c, reply, cx, ty + Skin.dp(46), w - Skin.dp(48), rp);
        }

        // explicit stop control — in continuous mode the overlay never closes
        // on its own, so there must be an obvious way out.
        float bw = Skin.dp(150), bh = Skin.dp(38);
        stopButton.set(cx - bw / 2f, h - Skin.dp(74), cx + bw / 2f, h - Skin.dp(74) + bh);
        Skin.panel(c, stopButton, Skin.alpha(Skin.FLARE, open),
                Skin.alpha(Skin.FLARE, 0.14f * open), 7);
        Paint sb = Skin.text(10, Skin.alpha(Skin.FLARE, open), true);
        sb.setLetterSpacing(0.22f);
        Skin.textCenter(c, "STOP LISTENING", cx,
                stopButton.centerY() + Skin.dp(4), sb);

        Paint hint = Skin.text(7.5f, Skin.alpha(Skin.TEXT_DIM, 0.7f * open), false);
        hint.setLetterSpacing(0.3f);
        Skin.textCenter(c, "MIC IS OPEN · TAP ANYWHERE TO CLOSE", cx, h - Skin.dp(24), hint);

        Skin.scanlines(c, w, h, phase);
    }

    private void drawMic(Canvas c, float cx, float cy, float s, int color) {
        Paint p = Skin.stroke(2f, color);
        p.setStrokeCap(Paint.Cap.ROUND);
        RectF cap = new RectF(cx - s * 0.34f, cy - s * 0.95f, cx + s * 0.34f, cy + s * 0.18f);
        c.drawRoundRect(cap, s * 0.34f, s * 0.34f, p);
        c.drawArc(new RectF(cx - s * 0.66f, cy - s * 0.5f, cx + s * 0.66f, cy + s * 0.62f),
                0, 180, false, p);
        c.drawLine(cx, cy + s * 0.62f, cx, cy + s * 0.95f, p);
    }

    /** Two-line centred wrap; a transcript never needs more than that. */
    private void drawWrapped(Canvas c, String text, float cx, float y, float max, Paint p) {
        if (p.measureText(text) <= max) {
            Skin.textCenter(c, text, cx, y, p);
            return;
        }
        int cut = p.breakText(text, true, max, null);
        int space = text.lastIndexOf(' ', cut);
        if (space <= 0) space = cut;
        String l1 = text.substring(0, space).trim();
        String l2 = text.substring(space).trim();
        if (p.measureText(l2) > max) {
            int c2 = p.breakText(l2, true, max - p.measureText("..."), null);
            l2 = l2.substring(0, Math.max(0, c2)) + "...";
        }
        Skin.textCenter(c, l1, cx, y, p);
        Skin.textCenter(c, l2, cx, y + p.getTextSize() * 1.25f, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            performHapticFeedback(1);
            if (dismiss != null) dismiss.onDismiss();
        }
        return true;
    }
}
