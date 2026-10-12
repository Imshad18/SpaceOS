package com.spaceos.os;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SPACE OS :: SHELL — a live Android shell inside the console.
 *
 * Unprivileged by construction: it runs as this app's uid, so it sees exactly
 * what Space OS is permitted to see. No root, no escalation, no pty.
 */
public class Terminal extends Activity implements Shell.Sink {

    // Swipe in from either edge to go back, the way the system gesture does.
    private EdgeBack edgeBack;

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (edgeBack == null) edgeBack = new EdgeBack(this);
        if (edgeBack.onDispatch(e)) return true;
        return super.dispatchTouchEvent(e);
    }

    private static final int MAX_CHARS = 240_000;   // ring the buffer, never OOM

    private Shell shell;
    private TextView out;
    private ScrollView scroll;
    private EditText input;
    private Bar bar;

    private String cwd = "/sdcard";
    private int lastCode = 0;
    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<String> history = new ArrayList<String>();
    private int historyCursor = -1;

    /** Preset probes — the things you actually want on a phone, one tap away. */
    private static final String[][] QUICK = {
            {"TOOLS",    "tools"},
            {"SCAN",     "scan 127.0.0.1"},
            {"SWEEP",    "sweep"},
            {"PORTS",    "ports"},
            {"ARP",      "arp"},
            {"NET",      "netinfo"},
            {"ID",       "id"},
            {"PROPS",    "getprop | head -60"},
            {"PROCS",    "ps -A"},
            {"SDCARD",   "ls -la /sdcard"},
            {"DISK",     "df -h"},
            {"IPADDR",   "ip addr"},
            {"ROUTE",    "ip route"},
            {"PKGS",     "pm list packages | head -60"},
            {"BATTERY",  "dumpsys battery"},
            {"CPU",      "cat /proc/cpuinfo"},
            {"MEM",      "cat /proc/meminfo"},
            {"MOUNTS",   "cat /proc/self/mounts"},
            {"KERNEL",   "uname -a"},
            {"SELINUX",  "cat /proc/self/attr/current"},
    };

    // =====================================================================

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Skin.init(this);

        // On a cold start Skin still holds the compile-time palette, so the
        // active one has to be applied before anything is measured or drawn.
        Theme.apply(Config.get(this).palette());
        Skin.dressWindow(this, Config.get(this).fullscreen());
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.VOID);

        // ---- header ----
        bar = new Bar(this);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) Skin.dp(78)));

        // ---- quick probe rail ----
        HorizontalScrollView rail = new HorizontalScrollView(this);
        rail.setHorizontalScrollBarEnabled(false);
        rail.setBackgroundColor(Skin.VOID_2);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding((int) Skin.dp(10), (int) Skin.dp(7),
                (int) Skin.dp(10), (int) Skin.dp(7));
        for (String[] q : QUICK) chips.addView(chip(q[0], q[1]));
        rail.addView(chips);
        root.addView(rail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- output ----
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(11.5f);
        out.setTextColor(Skin.TEXT);
        out.setTextIsSelectable(true);
        out.setPadding((int) Skin.dp(12), (int) Skin.dp(10),
                (int) Skin.dp(12), (int) Skin.dp(10));
        out.setLineSpacing(Skin.dp(1.5f), 1f);
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- prompt ----
        root.addView(promptRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        // adjustResize is inert behind a fullscreen layout; lift it ourselves.
        Keyboard.liftAboveIme(root);

        banner();
        shell = new Shell(this);
        shell.start(this);
        // First command establishes the real cwd and proves the pipe works.
        shell.exec("echo \"shell $(id -un 2>/dev/null || echo uid=$(id -u))"
                + " on $(getprop ro.product.model 2>/dev/null)\"");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (shell != null) shell.stop();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fall_in, R.anim.fall_out);
    }

    // =====================================================================
    // UI PIECES
    // =====================================================================

    private View chip(final String label, final String cmd) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setTextSize(9f);
        t.setTextColor(Skin.PLASMA);
        t.setPadding((int) Skin.dp(11), (int) Skin.dp(6),
                (int) Skin.dp(11), (int) Skin.dp(6));
        t.setBackground(new ChipBg());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) Skin.dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(1);
                submit(cmd);
            }
        });
        return t;
    }

    private View promptRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Skin.VOID_2);
        row.setPadding((int) Skin.dp(12), (int) Skin.dp(6),
                (int) Skin.dp(8), (int) Skin.dp(6));

        final TextView caret = new TextView(this);
        caret.setText(">");
        caret.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        caret.setTextSize(15f);
        caret.setTextColor(Skin.ION);
        caret.setPadding(0, 0, (int) Skin.dp(8), 0);
        row.addView(caret);

        input = new EditText(this);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(12.5f);
        input.setTextColor(Skin.TEXT);
        input.setHintTextColor(Skin.TEXT_DIM);
        input.setHint("enter command");
        input.setBackground(null);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                if (a == EditorInfo.IME_ACTION_SEND
                        || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && e.getAction() == KeyEvent.ACTION_DOWN)) {
                    submit(input.getText().toString());
                    return true;
                }
                return false;
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(navButton("^", new Runnable() {
            @Override public void run() { recall(-1); }
        }));
        row.addView(navButton("v", new Runnable() {
            @Override public void run() { recall(1); }
        }));
        row.addView(navButton("X", new Runnable() {
            @Override public void run() {
                buffer.clear();
                out.setText(buffer);
                banner();
            }
        }));
        return row;
    }

    private View navButton(String glyph, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setTextSize(12f);
        t.setTextColor(Skin.MAGENTA);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) Skin.dp(10), (int) Skin.dp(6),
                (int) Skin.dp(10), (int) Skin.dp(6));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(1);
                action.run();
            }
        });
        return t;
    }

    /** Cut-corner chip background, matching the bridge panels. */
    private class ChipBg extends android.graphics.drawable.Drawable {
        @Override public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            r.inset(Skin.dp(0.5f), Skin.dp(0.5f));
            Skin.panel(c, r, Skin.EDGE, Skin.alpha(Skin.PLASMA, 0.08f), 5);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // =====================================================================
    // CONSOLE I/O
    // =====================================================================

    private void banner() {
        append("SPACE OS SHELL v1.0.0", Skin.PLASMA, true);
        append("android " + Build.VERSION.RELEASE + " · sdk " + Build.VERSION.SDK_INT
                + " · " + Sys.hull().toLowerCase(Locale.US), Skin.TEXT_DIM, false);
        append("unprivileged session — app uid, no root, no pty", Skin.TEXT_DIM, false);
        append("type  tools  for the built-in network commands", Skin.PLASMA_D, false);
        append("", Skin.TEXT, false);
    }

    private void submit(String cmd) {
        if (cmd == null) return;
        cmd = cmd.trim();
        if (cmd.length() == 0) return;

        if (cmd.equals("clear")) {
            buffer.clear();
            out.setText(buffer);
            banner();
            input.setText("");
            return;
        }

        history.add(cmd);
        historyCursor = history.size();

        append(shortCwd() + " > " + cmd, Skin.ION, true);
        input.setText("");
        keepTyping();

        // Built-in network tools run in Java rather than being handed to sh,
        // because the real binaries they replace cannot execute on Android.
        if (NetTools.isBuiltin(cmd)) {
            runBuiltin(cmd);
            return;
        }

        shell.exec(cmd);
        bar.invalidate();
    }

    /** Built-ins can take seconds, so they run off the UI thread. */
    private void runBuiltin(final String cmd) {
        busyBuiltin = true;
        bar.invalidate();
        new Thread(new Runnable() {
            @Override public void run() {
                final String result = NetTools.run(Terminal.this, cmd);
                ui.post(new Runnable() {
                    @Override public void run() {
                        for (String line : result.split("\n")) {
                            append(line, Skin.TEXT, false);
                        }
                        append("", Skin.TEXT, false);
                        busyBuiltin = false;
                        bar.invalidate();
                    }
                });
            }
        }, "spaceos-nettool").start();
    }

    private volatile boolean busyBuiltin;

    /**
     * Puts the caret back in the command box and keeps the keyboard up.
     *
     * The output view is selectable, which makes it focusable — and every
     * append() calls setText() on it, so after each command the focus jumped
     * out of the input and the keyboard closed. A terminal you have to tap
     * before every single command is not a terminal.
     */
    private void keepTyping() {
        input.requestFocus();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Throwable ignored) {}
    }

    private void recall(int dir) {
        if (history.isEmpty()) return;
        historyCursor = Math.max(0, Math.min(history.size(), historyCursor + dir));
        if (historyCursor >= history.size()) {
            input.setText("");
        } else {
            input.setText(history.get(historyCursor));
            input.setSelection(input.getText().length());
        }
    }

    @Override public void onLine(String line) {
        append(line, Skin.TEXT, false);
    }

    @Override public void onComplete(int exitCode, String newCwd) {
        lastCode = exitCode;
        if (newCwd != null && newCwd.length() > 0) cwd = newCwd;
        if (exitCode != 0) {
            append("[ exit " + exitCode + " ]", Skin.FLARE, true);
        }
        append("", Skin.TEXT, false);
        bar.invalidate();
    }

    @Override public void onDead(String reason) {
        append("[ " + reason.toUpperCase(Locale.US) + " ]", Skin.FLARE, true);
        bar.invalidate();
    }

    private void append(String line, int color, boolean bold) {
        int start = buffer.length();
        buffer.append(line == null ? "" : line).append('\n');
        buffer.setSpan(new ForegroundColorSpan(color), start, buffer.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            buffer.setSpan(new StyleSpan(Typeface.BOLD), start, buffer.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // trim from the head so a chatty command cannot exhaust memory
        if (buffer.length() > MAX_CHARS) {
            int cut = buffer.length() - MAX_CHARS;
            int nl = indexOfNewline(cut);
            buffer.delete(0, nl < 0 ? cut : nl + 1);
        }
        out.setText(buffer);
        ui.post(new Runnable() {
            @Override public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
                // setText on a selectable TextView can pull focus; take it back
                if (input != null && !input.hasFocus()) input.requestFocus();
            }
        });
    }

    private int indexOfNewline(int from) {
        for (int i = from; i < buffer.length(); i++) if (buffer.charAt(i) == '\n') return i;
        return -1;
    }

    private String shortCwd() {
        String c = cwd == null ? "" : cwd;
        if (c.startsWith("/storage/emulated/0")) c = "~sd" + c.substring(19);
        else if (c.equals("/sdcard")) c = "~sd";
        else if (c.startsWith("/sdcard")) c = "~sd" + c.substring(7);
        return c;
    }

    // =====================================================================
    // HEADER
    // =====================================================================

    private class Bar extends View {
        private float phase;
        private boolean running;
        private final Handler h = new Handler(Looper.getMainLooper());

        Bar(Context c) { super(c); }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            running = true; postOnAnimation(anim);
        }
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            running = false; removeCallbacks(anim);
        }

        private final Runnable anim = new Runnable() {
            @Override public void run() {
                if (!running) return;
                phase += 1f;
                invalidate();
                postOnAnimation(this);
            }
        };

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            c.drawColor(Skin.VOID);
            Skin.grid(c, w, hh, 26);

            float pad = Skin.dp(14), top = Skin.dp(28);

            Paint back = Skin.text(9, Skin.MAGENTA, true);
            back.setLetterSpacing(0.2f);
            c.drawText("< BRIDGE", pad, top, back);

            Paint t = Skin.text(19, Skin.PLASMA, true);
            t.setLetterSpacing(0.24f);
            c.drawText("SHELL", pad, top + Skin.dp(24), t);

            Paint sub = Skin.text(8, Skin.TEXT_DIM, false);
            sub.setLetterSpacing(0.14f);
            c.drawText(shortCwd(), pad, top + Skin.dp(37), sub);

            // live state lamp
            boolean busy = busyBuiltin || (shell != null && shell.isBusy());
            boolean up = shell != null && shell.isAlive();
            int lamp = !up ? Skin.FLARE : busy ? Skin.SOLAR : Skin.ION;
            String stateTxt = !up ? "DEAD" : busy ? "RUNNING" : "READY";
            Paint sp = Skin.text(8.5f, lamp, true);
            sp.setLetterSpacing(0.16f);
            Skin.textRight(c, stateTxt, w - pad, top, sp);

            float lampR = Skin.dp(3.5f);
            float pulse = busy ? (0.5f + 0.5f * (float) Math.sin(phase * 0.25f)) : 1f;
            c.drawCircle(w - pad - Skin.dp(52), top - Skin.dp(3.5f), lampR,
                    Skin.fill(Skin.alpha(lamp, pulse)));

            Skin.textRight(c, "rc " + lastCode, w - pad, top + Skin.dp(24),
                    Skin.text(10, lastCode == 0 ? Skin.ION : Skin.FLARE, true));

            float ry = hh - Skin.dp(4);
            c.drawLine(pad, ry, w - pad, ry, Skin.stroke(1f, Skin.EDGE));
            float blip = pad + (phase * 6f % (w - pad * 2));
            c.drawRect(blip, ry - Skin.dp(1.5f), blip + Skin.dp(30), ry + Skin.dp(1.5f),
                    Skin.fill(Skin.PLASMA));

            Skin.scanlines(c, w, hh, phase);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getY() < Skin.dp(40)) {
                performHapticFeedback(1);
                finish();
            }
            return true;
        }
    }
}
