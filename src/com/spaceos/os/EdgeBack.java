package com.spaceos.os;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Swipe in from either edge to go back, the way the system gesture does.
 *
 * Space OS draws its own full-screen chrome, so on a phone using gesture
 * navigation the system's own back swipe is either hidden behind our immersive
 * flags or fighting our scrolling lists for the same touch. Rather than leave
 * back stranded on a hardware key that may not exist, every screen carries
 * this.
 *
 * It watches at dispatch level, before the content view sees anything, but
 * deliberately does NOT consume the gesture until it is unmistakably
 * horizontal. A list that starts under the finger keeps scrolling normally;
 * only once the swipe travels far enough sideways does this take over, and at
 * that moment the content receives a CANCEL so it does not finish a fling it
 * never really started.
 */
public final class EdgeBack {

    private static final float EDGE_DP = Gestures.EDGE_DP;
    private static final float TRIGGER_DP = Gestures.TRIGGER_DP;

    private final Activity host;
    private final Runnable onBack;

    private boolean armed, fired, fromLeft;
    private float startX, startY, curX;

    private Arrow arrow;

    public EdgeBack(Activity a) { this(a, null); }

    public EdgeBack(Activity a, Runnable back) {
        host = a;
        onBack = back;
    }

    /**
     * Call from the activity's dispatchTouchEvent.
     *
     * @return true if this gesture owns the event and the content should not
     *         see it.
     */
    public boolean onDispatch(MotionEvent e) {
        if (!Config.get(host).edgeBack()) return false;

        float x = e.getX(), y = e.getY();
        int w = host.getWindow().getDecorView().getWidth();
        float edge = Skin.dp(EDGE_DP);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                fired = false;
                fromLeft = x <= edge;
                armed = fromLeft || x >= w - edge;
                startX = x; startY = y; curX = x;
                return false;                    // always let the content see it

            case MotionEvent.ACTION_MOVE: {
                if (!armed) return false;
                if (fired) return true;          // we own the rest of this stroke

                float dx = x - startX, dy = y - startY;
                int verdict = Gestures.edge(fromLeft, dx, dy,
                        Skin.dp(TRIGGER_DP), Skin.dp(18));

                if (verdict == Gestures.DROP) {
                    armed = false; hideArrow();
                    return false;
                }
                if (verdict == Gestures.PASS) {
                    float travel = fromLeft ? dx : -dx;
                    curX = x;
                    if (travel > Skin.dp(10)) showArrow(travel);
                    return false;
                }
                {
                    fired = true;
                    hideArrow();
                    // The content may be mid-drag on a list. Cancel it, or the
                    // list finishes the fling behind the screen we just left.
                    cancelContent(e);
                    host.getWindow().getDecorView().performHapticFeedback(0);
                    if (onBack != null) onBack.run(); else host.onBackPressed();
                    return true;
                }
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean owned = fired;
                armed = false; fired = false;
                hideArrow();
                return owned;
            }
        }
        return false;
    }

    private void cancelContent(MotionEvent e) {
        try {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            View content = host.getWindow().getDecorView();
            content.dispatchTouchEvent(cancel);
            cancel.recycle();
        } catch (Throwable ignored) {}
    }

    // =====================================================================
    // The arrow that follows the finger, so the gesture is visible mid-swipe
    // =====================================================================

    private void showArrow(float travel) {
        if (arrow == null) {
            try {
                arrow = new Arrow(host);
                host.addContentView(arrow, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable t) { return; }
        }
        arrow.set(fromLeft, curX, startY,
                Math.min(1f, travel / Skin.dp(TRIGGER_DP)));
    }

    private void hideArrow() {
        if (arrow != null) arrow.clear();
    }

    /** A chevron in a lozenge, pinned to the edge, growing as you pull. */
    private static class Arrow extends View {
        private boolean left;
        private float y, progress;

        Arrow(Activity a) {
            super(a);
            setWillNotDraw(false);
            // purely decorative: never take a touch away from the content
            setClickable(false);
            setFocusable(false);
        }

        void set(boolean fromLeft, float x, float atY, float p) {
            left = fromLeft; y = atY; progress = p;
            setVisibility(VISIBLE);
            invalidate();
        }

        void clear() {
            progress = 0f;
            setVisibility(GONE);
        }

        @Override public boolean onTouchEvent(MotionEvent e) { return false; }

        @Override protected void onDraw(Canvas c) {
            if (progress <= 0f) return;
            float w = getWidth();
            float reach = Skin.dp(10) + progress * Skin.dp(20);
            float cx = left ? reach : w - reach;
            float r = Skin.dp(15) + progress * Skin.dp(5);

            int glow = progress >= 1f ? Skin.ION : Skin.PLASMA;
            c.drawCircle(cx, y, r * 1.5f,
                    Skin.fill(Skin.alpha(glow, 0.10f * progress)));
            c.drawCircle(cx, y, r,
                    Skin.fill(Skin.alpha(Skin.VOID_2, 0.88f * progress)));
            c.drawCircle(cx, y, r,
                    Skin.stroke(1.4f, Skin.alpha(glow, 0.85f * progress)));

            // chevron pointing the way the screen will leave
            float a = Skin.dp(5);
            float tip = left ? cx - a * 0.7f : cx + a * 0.7f;
            float tail = left ? cx + a * 0.5f : cx - a * 0.5f;
            Path p = new Path();
            p.moveTo(tail, y - a);
            p.lineTo(tip, y);
            p.lineTo(tail, y + a);
            Paint stroke = Skin.stroke(1.8f, Skin.alpha(glow, progress));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(p, stroke);
        }
    }
}
