package com.spaceos.os;

/**
 * The arithmetic behind every swipe, with no Android types in it.
 *
 * Gesture bugs are the ones that survive review: the code reads fine, the
 * happy path works on the device it was written on, and the failure is a
 * diagonal drag on a list that goes back instead of scrolling. Keeping the
 * decisions here — pure functions of the distances travelled — means they can
 * be run against a few hundred synthetic strokes on a desktop JVM instead of
 * being tested by hand, badly, four times.
 */
public final class Gestures {

    private Gestures() {}

    // ---- edge-in back ------------------------------------------------------

    /** Keep watching; the content view also sees the event. */
    public static final int PASS = 0;
    /** Committed: go back and take ownership of the rest of the stroke. */
    public static final int BACK = 1;
    /** Clearly a scroll — disarm and never fire for this stroke. */
    public static final int DROP = 2;

    /** How far in from the edge a touch must start to arm the gesture. */
    public static final float EDGE_DP = 26f;
    /** How far it must then travel sideways to commit. */
    public static final float TRIGGER_DP = 62f;

    /**
     * @param fromLeft which edge the stroke started at
     * @param dx       signed horizontal travel, positive = rightward
     * @param dy       signed vertical travel
     * @param dpTrigger commit distance in pixels
     * @param dpSlop   vertical distance that settles it as a scroll
     */
    public static int edge(boolean fromLeft, float dx, float dy,
                           float dpTrigger, float dpSlop) {
        float inward = fromLeft ? dx : -dx;
        float vert = Math.abs(dy);

        // Travelling back out of the screen, or mostly up and down: this is
        // the list scrolling, not a back gesture. Only give up once the
        // vertical movement is decisive, so a slightly wobbly horizontal drag
        // still counts.
        if (inward < 0 || vert > Math.abs(dx) * 1.2f) {
            return vert > dpSlop ? DROP : PASS;
        }
        return inward > dpTrigger ? BACK : PASS;
    }

    // ---- vertical swipe on the bridge --------------------------------------

    public static final int NONE = 0, UP = 1, DOWN = 2;

    /**
     * @param dx     signed horizontal travel
     * @param dy     signed vertical travel, positive = downward
     * @param needed how far it must travel to count
     */
    public static int vertical(float dx, float dy, float needed) {
        float travel = Math.abs(dy);
        if (travel <= needed) return NONE;
        // A swipe that wanders sideways as much as it goes up is not a swipe
        // up; it is a drag, and firing on it makes the bridge feel twitchy.
        if (travel <= Math.abs(dx) * 1.6f) return NONE;
        return dy < 0 ? UP : DOWN;
    }
}
