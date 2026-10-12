package com.spaceos.os;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * The seeker scope.
 *
 * A compass rose with the recorded signal strength plotted per heading. Turn
 * on the spot to fill the sweep, then walk toward the hot side and watch the
 * bar climb. That is the honest way to find a transmitter without
 * Angle-of-Arrival hardware.
 */
public class SeekerView extends View implements SensorEventListener {

    public interface Host {
        Seeker.Target current();
        Seeker seeker();
        void onSwitchTarget();
        void onReset();
    }

    private Host host;
    private SensorManager sensors;
    private float heading;                 // degrees, magnetic north
    private final float[] gravity = new float[3];
    private final float[] geomag = new float[3];
    private boolean haveG, haveM;

    private float phase;
    private boolean running;
    private long lastNs;

    private final RectF resetBtn = new RectF();
    private final RectF switchBtn = new RectF();

    public SeekerView(Context c) { super(c); }

    public void setHost(Host h) { host = h; }

    public void start() {
        if (running) return;
        running = true;
        lastNs = 0;
        sensors = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        if (sensors != null) {
            Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor mag = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
            if (mag != null) sensors.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI);
        }
        postOnAnimation(tick);
    }

    public void stop() {
        running = false;
        removeCallbacks(tick);
        try { if (sensors != null) sensors.unregisterListener(this); } catch (Throwable ignored) {}
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 1f / 60f : (now - lastNs) / 1_000_000_000f;
            lastNs = now;
            phase += dt * 20f;

            // fold the live reading into the polar plot at the current heading
            if (host != null) {
                Seeker.Target t = host.current();
                Seeker s = host.seeker();
                if (t != null && s != null) s.record(heading, t.rssi);
            }
            invalidate();
            postOnAnimation(this);
        }
    };

    // ---- compass ------------------------------------------------------------

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            smooth(gravity, e.values); haveG = true;
        } else if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            smooth(geomag, e.values); haveM = true;
        }
        if (!haveG || !haveM) return;

        float[] r = new float[9], i = new float[9];
        if (SensorManager.getRotationMatrix(r, i, gravity, geomag)) {
            float[] orient = new float[3];
            SensorManager.getOrientation(r, orient);
            float deg = (float) Math.toDegrees(orient[0]);
            if (deg < 0) deg += 360f;
            // short-path angular smoothing, so the rose does not jitter
            float delta = ((deg - heading + 540f) % 360f) - 180f;
            heading = (heading + delta * 0.18f + 360f) % 360f;
        }
    }

    private static void smooth(float[] dst, float[] src) {
        for (int i = 0; i < 3; i++) dst[i] = dst[i] * 0.82f + src[i] * 0.18f;
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    // =====================================================================

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Skin.VOID);
        Skin.stars(c, w, h, phase, 40);

        Seeker.Target t = host == null ? null : host.current();
        Seeker s = host == null ? null : host.seeker();

        float cx = w / 2f;
        float cy = h * 0.40f;
        float R = Math.min(w, h) * 0.30f;

        // ---- rose ----
        Skin.halo(c, cx, cy, R * 1.5f, Skin.PLASMA, 0.5f);
        for (int i = 1; i <= 4; i++) {
            c.drawCircle(cx, cy, R * i / 4f,
                    Skin.stroke(0.9f, Skin.alpha(Skin.EDGE, 0.9f)));
        }

        // cardinal marks, rotated so north stays north as you turn
        Paint card = Skin.text(8, Skin.TEXT_DIM, true);
        String[] marks = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90 - heading - 90);
            Skin.textCenter(c, marks[i],
                    cx + (float) Math.cos(a) * (R + Skin.dp(14)),
                    cy + (float) Math.sin(a) * (R + Skin.dp(14)) + Skin.dp(3), card);
        }

        // ---- the plot: one wedge per sector, length = recorded strength ----
        if (s != null) {
            float step = 360f / Seeker.SECTORS;
            for (int i = 0; i < Seeker.SECTORS; i++) {
                if (!s.hasSector(i)) continue;
                int rssi = s.peakAt(i);
                float norm = Math.max(0f, Math.min(1f, (rssi + 100) / 55f));
                float len = R * (0.18f + 0.82f * norm);

                // sector bearing, rotated into screen space
                float bearing = i * step + step / 2f;
                double a = Math.toRadians(bearing - heading - 90);

                Paint p = Skin.stroke(6f, Skin.alpha(Seeker.colourFor(rssi), 0.85f));
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(cx + (float) Math.cos(a) * R * 0.16f,
                        cy + (float) Math.sin(a) * R * 0.16f,
                        cx + (float) Math.cos(a) * len,
                        cy + (float) Math.sin(a) * len, p);
            }

            // ---- the arrow: strongest heading, relative to where you face ----
            float best = s.bestBearing();
            if (best >= 0 && s.coverage() >= 3) {
                double a = Math.toRadians(best - heading - 90);
                float tip = R * 1.02f;
                Paint arrow = Skin.stroke(2.6f, Skin.PLASMA);
                arrow.setStrokeCap(Paint.Cap.ROUND);
                float ax = cx + (float) Math.cos(a) * tip;
                float ay = cy + (float) Math.sin(a) * tip;
                c.drawLine(cx, cy, ax, ay, arrow);
                for (int k = -1; k <= 1; k += 2) {
                    double b = a + k * 0.42;
                    c.drawLine(ax, ay,
                            ax - (float) Math.cos(b) * Skin.dp(14),
                            ay - (float) Math.sin(b) * Skin.dp(14), arrow);
                }
            }
        }

        // ---- centre readout ----
        int rssi = t == null ? -127 : t.rssi;
        Paint big = Skin.text(30, t == null ? Skin.TEXT_DIM : Seeker.colourFor(rssi), false);
        Skin.textCenter(c, t == null ? "--" : String.valueOf(rssi), cx, cy + Skin.dp(6), big);
        Paint unit = Skin.text(7, Skin.TEXT_DIM, false);
        unit.setLetterSpacing(0.3f);
        Skin.textCenter(c, "dBm", cx, cy + Skin.dp(18), unit);

        // ---- readout block ----
        float y = cy + R + Skin.dp(46);
        Paint name = Skin.text(15, Skin.TEXT, true);
        Skin.textCenter(c, t == null ? "NO TARGET" : t.name, cx, y, name);

        Paint det = Skin.text(8.5f, Skin.TEXT_DIM, false);
        Skin.textCenter(c, t == null ? "connect to wifi or start a bluetooth sweep"
                : t.detail, cx, y + Skin.dp(15), det);

        if (t != null) {
            Paint prox = Skin.text(11, Seeker.colourFor(rssi), true);
            prox.setLetterSpacing(0.18f);
            Skin.textCenter(c, Seeker.proximity(rssi), cx, y + Skin.dp(36), prox);

            float m = t.metres();
            Paint dist = Skin.text(9, Skin.TEXT_DIM, false);
            Skin.textCenter(c, m < 0 ? "distance unknown"
                    : String.format(Locale.US, "roughly %.1f m away", m),
                    cx, y + Skin.dp(50), dist);
        }

        // ---- coaching ----
        if (s != null) {
            int cov = s.coverage();
            Paint coach = Skin.text(7.5f, Skin.alpha(Skin.SOLAR, 0.9f), false);
            coach.setLetterSpacing(0.16f);
            String msg = cov < 6
                    ? "TURN SLOWLY ON THE SPOT TO MAP THE SIGNAL  (" + cov + "/24)"
                    : "WALK TOWARD THE ARROW — THE BAR SHOULD CLIMB";
            Skin.textCenter(c, msg, cx, y + Skin.dp(70), coach);
        }

        // ---- buttons ----
        float bw = Skin.dp(120), bh = Skin.dp(34);
        resetBtn.set(cx - bw - Skin.dp(6), h - Skin.dp(58),
                cx - Skin.dp(6), h - Skin.dp(58) + bh);
        switchBtn.set(cx + Skin.dp(6), h - Skin.dp(58),
                cx + Skin.dp(6) + bw, h - Skin.dp(58) + bh);

        drawButton(c, resetBtn, "RESET SWEEP", Skin.MAGENTA);
        drawButton(c, switchBtn, "SWITCH TARGET", Skin.PLASMA);

        Paint honest = Skin.text(6.5f, Skin.alpha(Skin.TEXT_DIM, 0.75f), false);
        Skin.textCenter(c, "phones cannot sense signal direction — this maps strength as you move",
                cx, h - Skin.dp(14), honest);

        Skin.scanlines(c, w, h, phase);
    }

    private void drawButton(Canvas c, RectF r, String label, int colour) {
        Skin.panel(c, r, colour, Skin.alpha(colour, 0.12f), 6);
        Paint p = Skin.text(9, colour, true);
        p.setLetterSpacing(0.14f);
        Skin.textCenter(c, label, r.centerX(), r.centerY() + Skin.dp(3), p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float x = e.getX(), y = e.getY();
        if (resetBtn.contains(x, y)) {
            performHapticFeedback(1);
            if (host != null) host.onReset();
        } else if (switchBtn.contains(x, y)) {
            performHapticFeedback(1);
            if (host != null) host.onSwitchTarget();
        }
        return true;
    }
}
