package com.spaceos.os;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * HANDS :: the accessibility bridge.
 *
 * This is what turns Space OS from something that reads the phone into
 * something that operates it. With the service enabled, the agent can see the
 * contents of whatever app is on screen and drive it — tap, swipe, type, go
 * back, open the notification shade — in any application, not just this one.
 *
 * It is dormant unless the user enables it in Android's Accessibility
 * settings, and it can be revoked there at any time. Nothing here works
 * without that explicit grant.
 */
public class Hands extends AccessibilityService {

    private static volatile Hands instance;

    public static boolean connected() { return instance != null; }

    public static Hands get() { return instance; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // The agent pulls state on demand rather than reacting to every event;
        // reacting here would mean processing thousands of events a minute for
        // nothing.
    }

    @Override public void onInterrupt() { }

    // =====================================================================
    // READING THE SCREEN
    // =====================================================================

    /**
     * Flattens the active window into indexed text. The agent works from this,
     * so every interactive element gets a stable index it can tap by.
     */
    public String readScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "SCREEN UNAVAILABLE (no active window)";

        List<Element> found = new ArrayList<Element>();
        walk(root, found, 0);

        StringBuilder sb = new StringBuilder();
        sb.append("PACKAGE: ").append(root.getPackageName()).append('\n');
        sb.append("ELEMENTS (tap by index):\n");
        int n = 0;
        for (Element e : found) {
            if (n >= 120) { sb.append("... (truncated)\n"); break; }
            sb.append('[').append(n).append("] ")
              .append(e.kind).append(' ')
              .append('"').append(e.label).append('"');
            if (e.clickable) sb.append(" clickable");
            if (e.editable) sb.append(" editable");
            sb.append(" @").append(e.cx).append(',').append(e.cy).append('\n');
            n++;
        }
        if (n == 0) sb.append("(nothing readable — the app may block accessibility)\n");
        lastScan = found;
        return sb.toString();
    }

    private static class Element {
        String label = "";
        String kind = "view";
        boolean clickable, editable;
        int cx, cy;
        AccessibilityNodeInfo node;
    }

    private List<Element> lastScan = new ArrayList<Element>();

    private void walk(AccessibilityNodeInfo node, List<Element> out, int depth) {
        if (node == null || depth > 40 || out.size() > 200) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = text != null ? text.toString()
                : desc != null ? desc.toString() : "";

        boolean interesting = node.isClickable() || node.isEditable()
                || node.isCheckable() || label.length() > 0;

        if (interesting) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) {
                Element e = new Element();
                e.label = label.length() > 80 ? label.substring(0, 80) : label;
                e.clickable = node.isClickable();
                e.editable = node.isEditable();
                e.cx = b.centerX();
                e.cy = b.centerY();
                e.node = node;

                CharSequence cls = node.getClassName();
                String c = cls == null ? "" : cls.toString();
                if (c.contains("Button")) e.kind = "button";
                else if (c.contains("EditText")) e.kind = "input";
                else if (c.contains("Image")) e.kind = "image";
                else if (c.contains("Switch") || c.contains("CheckBox")) e.kind = "toggle";
                else if (c.contains("TextView")) e.kind = "text";
                else if (c.contains("RecyclerView") || c.contains("ListView")) e.kind = "list";

                out.add(e);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), out, depth + 1);
        }
    }

    // =====================================================================
    // ACTING
    // =====================================================================

    /** Taps an element from the last {@link #readScreen()} by its index. */
    public String tapIndex(int index) {
        if (index < 0 || index >= lastScan.size()) {
            return "NO SUCH ELEMENT: " + index + " (read the screen first)";
        }
        Element e = lastScan.get(index);
        if (e.node != null && e.node.isClickable()) {
            boolean ok = e.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (ok) return "TAPPED [" + index + "] \"" + e.label + "\"";
        }
        return tapAt(e.cx, e.cy);
    }

    /** Taps whatever is on screen whose label matches. */
    public String tapText(String query) {
        if (query == null || query.length() == 0) return "ERROR: text required";
        if (lastScan.isEmpty()) readScreen();
        String q = query.toLowerCase(Locale.US);

        Element exact = null, partial = null;
        for (Element e : lastScan) {
            String l = e.label.toLowerCase(Locale.US);
            if (l.equals(q)) { exact = e; break; }
            if (partial == null && l.contains(q)) partial = e;
        }
        Element pick = exact != null ? exact : partial;
        if (pick == null) return "NOT FOUND ON SCREEN: \"" + query + "\"";

        if (pick.node != null && pick.node.isClickable()
                && pick.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "TAPPED \"" + pick.label + "\"";
        }
        return tapAt(pick.cx, pick.cy);
    }

    /** Raw coordinate tap, dispatched as a real gesture. */
    public String tapAt(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return "ERROR: gestures need Android 7+";
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
        return dispatch(b.build(), "TAPPED " + x + "," + y);
    }

    public String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (Build.VERSION.SDK_INT < 24) return "ERROR: gestures need Android 7+";
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(
                p, 0, Math.max(50, Math.min(3000, durationMs))));
        return dispatch(b.build(), "SWIPED " + x1 + "," + y1 + " -> " + x2 + "," + y2);
    }

    /** Types into the focused field, or the first editable one found. */
    public String typeText(String text) {
        if (text == null) return "ERROR: text required";
        AccessibilityNodeInfo target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null) {
            if (lastScan.isEmpty()) readScreen();
            for (Element e : lastScan) {
                if (e.editable && e.node != null) { target = e.node; break; }
            }
        }
        if (target == null) return "NO INPUT FIELD FOCUSED";

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        return ok ? "TYPED: " + text : "TYPE FAILED (field rejected input)";
    }

    /** back / home / recents / notifications / quicksettings / lock. */
    public String global(String action) {
        if (action == null) return "ERROR: action required";
        String a = action.toLowerCase(Locale.US);
        int code;
        if (a.contains("back")) code = GLOBAL_ACTION_BACK;
        else if (a.contains("home")) code = GLOBAL_ACTION_HOME;
        else if (a.contains("recent")) code = GLOBAL_ACTION_RECENTS;
        else if (a.contains("notif")) code = GLOBAL_ACTION_NOTIFICATIONS;
        else if (a.contains("quick") || a.contains("setting")) code = GLOBAL_ACTION_QUICK_SETTINGS;
        else if (a.contains("split")) code = GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN;
        else if (a.contains("lock") && Build.VERSION.SDK_INT >= 28) code = GLOBAL_ACTION_LOCK_SCREEN;
        else if (a.contains("shot") && Build.VERSION.SDK_INT >= 28) code = GLOBAL_ACTION_TAKE_SCREENSHOT;
        else return "UNKNOWN ACTION: " + action;

        boolean ok = performGlobalAction(code);
        return ok ? "DID: " + a : "REFUSED: " + a;
    }

    /** Scrolls the first scrollable container found. */
    public String scroll(boolean forward) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "NO ACTIVE WINDOW";
        AccessibilityNodeInfo scrollable = findScrollable(root, 0);
        if (scrollable == null) return "NOTHING SCROLLABLE ON SCREEN";
        boolean ok = scrollable.performAction(forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        return ok ? ("SCROLLED " + (forward ? "DOWN" : "UP")) : "SCROLL REFUSED";
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** Blocks until the gesture completes so the agent sees a real result. */
    private String dispatch(GestureDescription gesture, String successMessage) {
        final boolean[] done = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        boolean queued = dispatchGesture(gesture,
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription d) {
                        done[0] = true; latch.countDown();
                    }
                    @Override public void onCancelled(GestureDescription d) {
                        done[0] = false; latch.countDown();
                    }
                }, null);
        if (!queued) return "GESTURE REJECTED BY SYSTEM";
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return done[0] ? successMessage : "GESTURE CANCELLED";
    }
}
