package com.spaceos.os;

import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

/**
 * Keeps the input row above the keyboard.
 *
 * Every screen with a text field already declares adjustResize in the
 * manifest, which is the normal answer and does nothing here: adjustResize is
 * ignored the moment a window lays itself out fullscreen, and Space OS draws
 * behind the system bars everywhere. So the window never shrank, the keyboard
 * came up over the bottom of it, and the command line was underneath — you
 * could type, you just could not see what you were typing.
 *
 * The fix is to stop relying on the window resizing and pad the content by the
 * keyboard's own height instead.
 */
public final class Keyboard {

    private Keyboard() {}

    /**
     * Pads {@code root}'s bottom by however much of it the keyboard covers.
     *
     * The view's existing bottom padding is preserved and added to, so callers
     * that already reserve space for the navigation bar keep it.
     */
    public static void liftAboveIme(final View root) {
        if (root == null) return;
        final int basePadding = root.getPaddingBottom();

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    // Only the keyboard. The navigation bar inset is
                    // deliberately ignored: these screens were laid out
                    // edge-to-edge on purpose, and padding for it here would
                    // change how every one of them looks when nothing is
                    // wrong. The keyboard covering the input is the bug; the
                    // rest of the layout is not.
                    int lift = in.getInsets(WindowInsets.Type.ime()).bottom;
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                            v.getPaddingRight(), basePadding + lift);
                    return in;
                }
            });
            root.requestApplyInsets();
            return;
        }

        // Pre-30 there is no IME inset to ask for. The keyboard's height is
        // whatever the window can no longer see.
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private int last = -1;
                    @Override public void onGlobalLayout() {
                        Rect visible = new Rect();
                        root.getWindowVisibleDisplayFrame(visible);
                        int screen = root.getRootView().getHeight();
                        int covered = screen - visible.bottom;
                        // Anything under a sixth of the screen is the
                        // navigation bar, not a keyboard.
                        int lift = covered > screen / 6 ? covered : 0;
                        if (lift == last) return;
                        last = lift;
                        root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                                root.getPaddingRight(), basePadding + lift);
                    }
                });
    }
}
