package com.spaceos.os;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The bridge. One surface, drawn entirely in code: hull grid, reactor core,
 * telemetry stack and the module lattice. Touch is hit-tested against the
 * lattice directly, so there is no view hierarchy to fight with.
 */
public class HudView extends View {

    /** Fired on commit; {@code from} is the tile the module should grow out of. */
    public interface OnModule {
        void onModule(String id, RectF from);
        /** The reactor core was tapped — hand over to the voice interface. */
        void onVoice();
        /** The wordmark was tapped — open the preset picker. */
        void onPresets();
        /** A dock slot was held — offer to change or clear it. */
        void onDockEdit(int slot);
        /** Long-pressed empty space — show the recent apps list. */
        void onRecents();
        /** Swiped on empty space. dir is -1 for up, +1 for down. */
        void onSwipe(int dir);
        /** A notification icon was tapped; -1 means the strip itself. */
        void onNotification(int index);
    }

    // ---- module lattice : 4 x 4 ------------------------------------------
    static final String[][] MODULES = {
            {"shell",    "SHELL"},
            {"apps",     "APPS"},
            {"files",    "ARCHIVE"},
            {"comms",    "COMMS"},
            {"sensors",  "SENSORS"},
            {"network",  "UPLINK"},
            {"power",    "REACTOR"},
            {"scope",    "SEEKER"},
            {"media",    "OPTICS"},
            {"packages", "REGISTRY"},
            {"location", "NAV"},
            {"config",   "CONFIG"},
            {"theme",    "THEME"},
            {"system",   "CORE"},
            {"journal",  "LOGS"},
            {"brain",    "MEMORY"},
    };
    // 16 tiles, exactly 4x4. NEURAL has its own full-width strip and CONFIG
    // lives inside the CORE panel — an 18th and 17th tile forced a fifth row
    // that did not fit and collided with the dock.

    /** One slot on the bottom dock: a real app resolved from the host. */
    public static class Dock {
        public String label;
        public android.graphics.drawable.Drawable icon;
        public Runnable action;
        /** Cleared by the user. Draws nothing at all — no panel, no glyph. */
        public boolean empty;

        public Dock(String label, android.graphics.drawable.Drawable icon, Runnable action) {
            this.label = label; this.icon = icon; this.action = action;
        }

        public Dock markEmpty() { this.empty = true; return this; }
    }

    private Dock[] dock = new Dock[0];
    private RectF[] dockRects = new RectF[0];
    private float[] dockPress = new float[0];
    private int dockPressed = -1;

    private OnModule listener;
    private final RectF[] tileRects = new RectF[MODULES.length];
    private int pressed = -1;

    // ---- animation ---------------------------------------------------------
    private float phase = 0f;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private long lastFrameNs = 0;

    /** Per-tile press energy, eased toward 1 while held. */
    private final float[] press = new float[MODULES.length];
    /** 0..1 entry cascade, restarted every time the bridge is resumed. */
    private float entry = 0f;
    /** Warp flash fired when a module is committed. */
    private float warp = 0f;

    // ---- sampled telemetry -------------------------------------------------
    private Sys.Power power = new Sys.Power();
    private Sys.Mem mem = new Sys.Mem();
    private Sys.Store store = new Sys.Store();
    private Sys.Link link = new Sys.Link();
    private float load, thermal = Float.NaN;
    private long lastSample = 0;
    private final float[] loadHistory = new float[64];
    private int historyHead = 0;

    private SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private boolean showSeconds = true;

    /**
     * Rebuilds the time formatter from the CLOCK settings. A preference that
     * nothing reads is worse than no preference at all.
     */
    public void setClockFormat(boolean h24, boolean seconds) {
        showSeconds = seconds;
        clockFmt = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.US);
        secondsFmt = new SimpleDateFormat("ss", Locale.US);
        ampmFmt = new SimpleDateFormat("a", Locale.US);
        this.h24 = h24;
        invalidate();
    }

    private SimpleDateFormat secondsFmt = new SimpleDateFormat("ss", Locale.US);
    private SimpleDateFormat ampmFmt = new SimpleDateFormat("a", Locale.US);
    private boolean h24 = true;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy.MM.dd EEE", Locale.US);

    // ---- geometry ----------------------------------------------------------
    private RectF header = new RectF();
    private RectF core = new RectF();
    private RectF telemetry = new RectF();
    private RectF lattice = new RectF();
    private RectF dockBar = new RectF();
    private RectF neuralStrip = new RectF();
    private final RectF wordmark = new RectF();
    private final RectF themeButton = new RectF();
    private boolean themePressed;
    private float themePress;
    private int paletteIndex;
    private boolean wordmarkPressed;
    private float wordmarkPress;
    private boolean neuralPressed;
    private float neuralPress;
    private float coreCx, coreCy, coreR;

    public HudView(Context c) {
        super(c);
        setFocusable(true);
        for (int i = 0; i < tileRects.length; i++) tileRects[i] = new RectF();
        sample();
    }

    public void setOnModule(OnModule l) { this.listener = l; }

    // =====================================================================
    // LIFECYCLE
    // =====================================================================

    public void start() {
        if (running) return;
        running = true;
        entry = 0f;
        lastFrameNs = 0;
        // Registered here, not on the sampling thread: PhoneStateListener's
        // constructor takes the calling thread's Looper and throws outright
        // when there is not one.
        Cell.start(getContext());
        postOnAnimation(tick);
    }

    public void stop() {
        running = false;
        removeCallbacks(tick);
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * Vsync-locked frame loop. Everything advances on real elapsed time rather
     * than frame count, so the console runs at the same speed whether the
     * device is giving us 60, 90 or 120Hz.
     */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.nanoTime();
            float dt = lastFrameNs == 0 ? 1f / 60f : (now - lastFrameNs) / 1_000_000_000f;
            lastFrameNs = now;
            if (dt > 0.1f) dt = 0.1f;          // swallow stalls instead of jumping

            phase += dt * 20f;                  // legacy phase unit == 20/sec
            entry = approach(entry, 1f, dt, 3.2f);
            warp = Math.max(0f, warp - dt * 3.6f);
            for (int i = 0; i < press.length; i++) {
                press[i] = approach(press[i], i == pressed ? 1f : 0f, dt, 14f);
            }
            for (int i = 0; i < dockPress.length; i++) {
                dockPress[i] = approach(dockPress[i], i == dockPressed ? 1f : 0f, dt, 14f);
            }
            corePress = approach(corePress, corePressed ? 1f : 0f, dt, 14f);
            neuralPress = approach(neuralPress, neuralPressed ? 1f : 0f, dt, 14f);
            wordmarkPress = approach(wordmarkPress, wordmarkPressed ? 1f : 0f, dt, 14f);
            themePress = approach(themePress, themePressed ? 1f : 0f, dt, 14f);

            long ms = System.currentTimeMillis();
            if (ms - lastSample > 1000) sample();

            invalidate();
            postOnAnimation(this);
        }
    };

    /** Exponential ease — frame-rate independent, no allocation, no springs. */
    private static float approach(float cur, float target, float dt, float rate) {
        float k = 1f - (float) Math.exp(-rate * dt);
        float v = cur + (target - cur) * k;
        return Math.abs(target - v) < 0.001f ? target : v;
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    /**
     * Telemetry is read on a worker thread. Wi-Fi state and interface
     * enumeration are slow enough to drop frames if they run on the draw
     * thread, and a stale reading for one frame costs nothing.
     */
    private void sample() {
        lastSample = System.currentTimeMillis();
        if (sampling) return;
        sampling = true;
        final Context c = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Sys.Power p = null; Sys.Mem m = null; Sys.Store s = null; Sys.Link l = null;
                float ld = 0, th = Float.NaN;
                String cl = null; int clv = -1;
                try {
                    p = Sys.power(c);
                    m = Sys.mem(c);
                    s = Sys.store();
                    l = Sys.link(c);
                    ld = Sys.loadEstimate();
                    th = Sys.thermalC();
                    cl = Cell.label(c);
                    clv = Cell.level();
                } catch (Throwable ignored) {}
                final String fcl = cl; final int fclv = clv;
                final Sys.Power fp = p; final Sys.Mem fm = m;
                final Sys.Store fs = s; final Sys.Link fl = l;
                final float fld = ld, fth = th;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (fp != null) power = fp;
                        if (fm != null) mem = fm;
                        if (fs != null) store = fs;
                        if (fl != null) link = fl;
                        load = fld;
                        thermal = fth;
                        cellLabel = fcl;
                        cellLevel = fclv;
                        loadHistory[historyHead] = fld;
                        historyHead = (historyHead + 1) % loadHistory.length;
                        sampling = false;
                    }
                });
            }
        }, "spaceos-telemetry").start();
    }

    private volatile boolean sampling = false;

    // ---- preset state ------------------------------------------------------

    private int layout = Theme.BRIDGE;
    private int backdrop = Theme.BG_STARS;

    /** Switches layout; geometry is rebuilt on the next measure pass. */
    public void setLayout(int l) {
        layout = Math.max(0, Math.min(Theme.LAYOUT_NAMES.length - 1, l));
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);
        entry = 0f;                 // replay the cascade so the change reads
        invalidate();
    }

    public void setBackdrop(int b) { backdrop = b; invalidate(); }

    /** Drives the top and bottom insets — see the note in onSizeChanged. */
    private boolean fullscreen;

    public void setFullscreen(boolean on) {
        fullscreen = on;
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);
        invalidate();
    }

    /**
     * Height of the display cutout in pixels, or 0 if there is none.
     *
     * With SHORT_EDGES the window now genuinely owns the full screen, which
     * means content can land underneath a punch-hole camera. The header has to
     * clear whatever the cutout actually occupies rather than a guessed
     * constant — that is what this is measured for.
     */
    private float cutoutTop;

    public void setCutoutTop(float px) {
        if (Math.abs(cutoutTop - px) < 0.5f) return;
        cutoutTop = px;
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);
        invalidate();
    }

    /** Height of the status strip. */
    private static final float NOTIF_H = 24f;

    /**
     * Where the status strip starts.
     *
     * This used to return cutoutTop + 2, which pushes everything BELOW the
     * punch-hole — a whole status-bar's height of nothing across the full
     * width of the screen, to avoid a camera that occupies about six percent
     * of it in the middle. The strip belongs level with the hole, using the
     * space either side of it, which is what every other launcher does and
     * what the system status bar itself does.
     *
     * Nothing is drawn in the middle of the strip: notification icons sit on
     * the left, link and charge on the right, and the camera sits between them
     * in the gap they leave.
     */
    private float topInset() {
        if (!fullscreen) return Skin.dp(30);
        if (cutoutTop <= 0f) return Skin.dp(3);
        // centre the strip in the cutout band so it lines up with the camera
        return Math.max(Skin.dp(2), (cutoutTop - Skin.dp(NOTIF_H)) / 2f);
    }

    /** Only used to label the theme button. */
    public void setPaletteIndex(int p) { paletteIndex = p; invalidate(); }

    public int layout() { return layout; }

    /**
     * Where a module's tile sits, for anchoring its opening animation.
     *
     * Falls back to a strip across the bottom of the screen rather than null:
     * a gesture-opened module has no tile under the finger, and growing it out
     * of the bottom edge is what a swipe up should look like anyway.
     */
    public RectF tileFor(String id) {
        for (int i = 0; i < MODULES.length; i++) {
            if (MODULES[i][0].equals(id) && !tileRects[i].isEmpty()) {
                return new RectF(tileRects[i]);
            }
        }
        float h = getHeight(), w = getWidth();
        return new RectF(w * 0.25f, h - Skin.dp(40), w * 0.75f, h);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (layout != Theme.BRIDGE) { layoutAlternate(w, h); return; }

        float pad = Skin.dp(14);
        // Clearance for the system status bar. When we have hidden it there is
        // nothing to clear, and reserving 30dp anyway is what produced the
        // black band at the top in fullscreen.
        float top = topInset();

        notifBar.set(pad, top, w - pad, top + Skin.dp(NOTIF_H));
        header.set(pad, notifBar.bottom + Skin.dp(4), w - pad,
                notifBar.bottom + Skin.dp(74));

        // The core is the hero and gets the largest share. Telemetry shrank to
        // a stat strip because its three values now live on the core's rings,
        // and the neural strip is a hairline rather than a slab.
        float coreTop = header.bottom + Skin.dp(10);
        float coreH = Math.min(h * 0.30f, Skin.dp(252));
        core.set(pad, coreTop, w - pad, coreTop + coreH);
        coreCx = w / 2f;
        coreCy = core.centerY() - Skin.dp(6);
        coreR = Math.min(core.width(), core.height()) * 0.315f;

        float telTop = core.bottom + Skin.dp(4);
        telemetry.set(pad, telTop, w - pad, telTop + Skin.dp(42));

        neuralStrip.set(pad, telemetry.bottom + Skin.dp(10),
                w - pad, telemetry.bottom + Skin.dp(32));

        // dock pinned to the bottom, lattice fills whatever is left. A dock
        // with every slot cleared collapses to nothing rather than reserving
        // empty space at the bottom of the screen.
        float dockH = dockIsEmpty() ? 0f : Skin.dp(66);
        dockBar.set(pad, h - Skin.dp(10) - dockH, w - pad, h - Skin.dp(10));

        // 24dp of clearance so the QUICK LAUNCH label has a band of its own
        lattice.set(pad, neuralStrip.bottom + Skin.dp(18), w - pad,
                dockBar.top - Skin.dp(dockIsEmpty() ? 4 : 24));
        layoutLattice();
        layoutDock();
    }

    /**
     * Geometry for every layout that is not BRIDGE. They all share the same
     * frame — header at the top, dock at the bottom, one big instrument in
     * between — and differ in how the module tiles are scattered through it.
     */
    private void layoutAlternate(int w, int h) {
        float pad = Skin.dp(14);
        float top = topInset();

        notifBar.set(pad, top, w - pad, top + Skin.dp(NOTIF_H));
        header.set(pad, notifBar.bottom + Skin.dp(4), w - pad,
                notifBar.bottom + Skin.dp(74));

        float dockH = dockIsEmpty() ? 0f : Skin.dp(72);
        float bottomPad = Skin.dp(fullscreen ? 6 : 12);
        dockBar.set(pad, h - bottomPad - dockH, w - pad, h - bottomPad);

        // alternate layouts draw their own telemetry, so these collapse
        telemetry.set(pad, header.bottom, w - pad, header.bottom);
        neuralStrip.set(pad, dockBar.top - Skin.dp(46),
                w - pad, dockBar.top - Skin.dp(8));

        float fieldTop = header.bottom + Skin.dp(10);
        float fieldBottom = neuralStrip.top - Skin.dp(10);
        lattice.set(pad, fieldTop, w - pad, fieldBottom);

        switch (layout) {
            case Theme.ORBITAL:
                Layouts.layoutOrbital(tileRects, w, h, fieldTop, fieldBottom); break;
            case Theme.TACTICAL:
                Layouts.layoutTactical(tileRects, w, h, fieldTop, fieldBottom); break;
            case Theme.MINIMAL:
                Layouts.layoutMinimal(tileRects, w, h, fieldTop, fieldBottom); break;
            case Theme.MAINFRAME:
                // header block eats the top third; the menu fills the rest
                Layouts.layoutMainframe(tileRects, w, h,
                        fieldTop + Skin.dp(126), fieldBottom); break;
            case Theme.REACTOR:
                Layouts.layoutReactor(tileRects, w, h, fieldTop, fieldBottom); break;
            case Theme.HEXCELL:
                Layouts.layoutHexcell(tileRects, w, h, fieldTop, fieldBottom); break;
            case Theme.CONSTELLATION:
                Layouts.layoutConstellation(tileRects, w, h, fieldTop, fieldBottom); break;
            case Theme.HELIX:
                Layouts.layoutHelix(tileRects, w, h, fieldTop, fieldBottom); break;
            case Theme.CASCADE:
                Layouts.layoutCascade(tileRects, w, h, fieldTop, fieldBottom); break;
        }
        layoutDock();
    }

    private void layoutLattice() {
        int cols = 4;
        int rows = (int) Math.ceil(MODULES.length / (float) cols);
        float gap = Skin.dp(7);
        float tw = (lattice.width() - gap * (cols - 1)) / cols;
        float th = (lattice.height() - gap * (rows - 1)) / rows;
        for (int i = 0; i < MODULES.length; i++) {
            int r = i / cols, cIdx = i % cols;
            float x = lattice.left + cIdx * (tw + gap);
            float y = lattice.top + r * (th + gap);
            tileRects[i].set(x, y, x + tw, y + th);
        }
    }

    /** Hands the dock its resolved apps; safe to call before layout. */
    public void setDock(Dock[] items) {
        dock = items == null ? new Dock[0] : items;
        dockRects = new RectF[dock.length];
        dockPress = new float[dock.length];
        for (int i = 0; i < dock.length; i++) dockRects[i] = new RectF();
        // an all-empty dock collapses, and the lattice takes the space back
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);
        layoutDock();
        invalidate();
    }

    /** True when every slot has been cleared. */
    private boolean dockIsEmpty() {
        if (dock.length == 0) return true;
        for (Dock d : dock) if (d != null && !d.empty) return false;
        return true;
    }

    private void layoutDock() {
        if (dock.length == 0 || dockBar.width() <= 0) return;
        float gap = Skin.dp(8);
        float w = (dockBar.width() - gap * (dock.length - 1)) / dock.length;
        for (int i = 0; i < dock.length; i++) {
            float x = dockBar.left + i * (w + gap);
            dockRects[i].set(x, dockBar.top, x + w, dockBar.bottom);
        }
    }

    // =====================================================================
    // DRAW
    // =====================================================================

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Skin.VOID);

        // A wallpaper goes under everything and replaces nothing: the backdrop
        // still animates over it, so stars or code rain read as a layer on the
        // picture rather than the picture being an alternative to them.
        boolean hasPaper = wallpaperOn
                && Wallpaper.draw(c, getContext(), w, h, wallpaperDim);

        Layouts.backdrop(c, w, h, backdrop, phase);
        // pull the eye toward the middle before anything is drawn on top
        Skin.vignette(c, w, h, hasPaper ? 0.35f : 0.55f);

        drawNotifBar(c);
        drawStatusStrip(c);
        drawHeader(c);
        if (layout == Theme.BRIDGE) {
            drawCore(c);
            drawTelemetry(c);
        } else {
            drawInstrument(c, w, h);
        }
        drawNeuralStrip(c);
        drawLattice(c);
        drawDock(c);
        drawSwipeHandle(c);

        Skin.scanlines(c, w, h, phase);

        // warp flash on commit — a single expanding ring plus a wash
        if (warp > 0f) {
            float t = 1f - warp;
            c.drawColor(Skin.alpha(Skin.PLASMA, warp * 0.14f));
            Paint ring = Skin.stroke(2.5f, Skin.alpha(Skin.PLASMA, warp * 0.8f));
            c.drawCircle(warpX, warpY, t * Math.max(w, h) * 0.9f, ring);
        }
    }

    private float warpX, warpY;

    // ---- header ------------------------------------------------------------

    /**
     * A single status rail. The old header stacked a wordmark, a greeting, a
     * hull string and a clock into 58dp of competing text — this gives the
     * time real presence and demotes everything else to one quiet line.
     */
    private void drawHeader(Canvas c) {
        Date now = new Date();

        // the time, as the anchor
        Paint clock = Skin.text(34, Skin.TEXT, false);
        clock.setLetterSpacing(-0.02f);
        String hhmm = clockFmt.format(now);
        c.drawText(hhmm, header.left, header.top + Skin.dp(26), clock);

        // seconds (or AM/PM) trail smaller, so the eye locks to the hour
        String trail = showSeconds ? secondsFmt.format(now)
                : (h24 ? "" : ampmFmt.format(now));
        if (trail.length() > 0) {
            Paint secs = Skin.text(11, Skin.alpha(Skin.PLASMA, 0.75f), true);
            c.drawText(trail, header.left + clock.measureText(hhmm) + Skin.dp(4),
                    header.top + Skin.dp(26), secs);
        }

        // one quiet line beneath: greeting, then hull, clipped to fit
        Paint meta = Skin.text(7.5f, Skin.TEXT_DIM, false);
        meta.setLetterSpacing(0.2f);
        String line = greeting() + "  ·  " + dateFmt.format(now).toUpperCase(Locale.US);
        c.drawText(clip(line, meta, header.width() * 0.62f),
                header.left, header.top + Skin.dp(40), meta);

        // Link, charge and the mic pip used to live here. They are status, so
        // they moved up into the strip on the cutout line where status goes —
        // which is what makes that band earn its height instead of sitting
        // empty above a second row saying the same things.
        Paint hullPaint = Skin.text(7, Skin.alpha(Skin.TEXT_DIM, 0.8f), false);
        hullPaint.setLetterSpacing(0.16f);
        Skin.textRight(c, clip(Sys.hull(), hullPaint, header.width() * 0.4f),
                header.right, header.top + Skin.dp(26), hullPaint);

        drawThemeChip(c);
        wordmark.setEmpty();      // wordmark is gone; the theme chip replaced it
    }

    /**
     * The theme chip lives in the header, in EVERY layout.
     *
     * It used to be drawn inside drawLattice(), which returns early for every
     * layout except BRIDGE — and MINIMAL only lays out its first eight tiles,
     * so the THEME tile was gone too. Choosing MINIMAL therefore removed every
     * route back to the picker. A control that changes the layout must not
     * live inside the layout it changes.
     */
    private void drawThemeChip(Canvas c) {
        String label = "◈ " + Theme.LAYOUT_NAMES[layout]
                + " / " + Theme.PALETTE_NAMES[paletteIndex];
        Paint tp = Skin.text(7f, Skin.MAGENTA, true);
        tp.setLetterSpacing(0.08f);
        float tw = tp.measureText(label);

        // Left side, below the meta line — the right side is taken by the
        // status chips, the hull string and the MIC OPEN pip.
        float left = header.left;
        float top = header.top + Skin.dp(48);
        themeButton.set(left - Skin.dp(4), top,
                left + tw + Skin.dp(10), top + Skin.dp(17));

        Skin.panel(c, themeButton, Skin.alpha(Skin.MAGENTA, 0.7f),
                Skin.alpha(Skin.MAGENTA, themePress > 0.01f ? 0.28f : 0.10f), 4);
        c.drawText(label, left + Skin.dp(3), top + Skin.dp(12), tp);
    }

    /** Small right-anchored value chip. Returns the new right edge. */
    private float statChip(Canvas c, float right, float y, String value, int color) {
        Paint v = Skin.text(11, color, true);
        float vw = v.measureText(value);
        float dot = Skin.dp(4);
        Skin.textRight(c, value, right, y + Skin.dp(4), v);
        c.drawCircle(right - vw - Skin.dp(7), y, dot, Skin.fill(Skin.alpha(color, 0.9f)));
        return right - vw - Skin.dp(14);
    }

    private void drawHeaderLegacy(Canvas c) {
        Paint title = Skin.text(21, Skin.PLASMA, true);
        title.setLetterSpacing(0.22f);
        c.drawText("SPACE OS", header.left, header.top + Skin.dp(19), title);

        // the wordmark is the preset button; give it a hit box and a hint
        float tw = title.measureText("SPACE OS") * 1.22f;
        wordmark.set(header.left - Skin.dp(6), header.top - Skin.dp(4),
                header.left + tw + Skin.dp(34), header.top + Skin.dp(26));
        Paint chev = Skin.text(9, Skin.alpha(Skin.MAGENTA,
                0.5f + 0.5f * (float) Math.abs(Math.sin(phase * 0.05f))), true);
        c.drawText("▾", header.left + tw + Skin.dp(8),
                header.top + Skin.dp(19), chev);
        if (wordmarkPress > 0.01f) {
            Skin.brackets(c, wordmark, Skin.alpha(Skin.MAGENTA, wordmarkPress), 8, 1.2f);
        }

        Paint sub = Skin.text(8.5f, Skin.TEXT_DIM, false);
        sub.setLetterSpacing(0.14f);
        c.drawText(greeting(), header.left, header.top + Skin.dp(33),
                Skin.text(8.5f, Skin.alpha(Skin.MAGENTA, 0.9f), true));

        // Clip the hull string to whatever the link status leaves free — it
        // used to run straight underneath "WIFI ONLINE".
        String linkText = link.type + (link.online ? " ONLINE" : " NO ROUTE");
        Paint linkPaint = Skin.text(8.5f, Skin.ION, true);
        linkPaint.setLetterSpacing(0.14f);
        float free = header.width() - linkPaint.measureText(linkText) - Skin.dp(14);
        c.drawText(clip(Sys.hull() + " · " + Sys.androidRelease(), sub, free),
                header.left, header.top + Skin.dp(45), sub);

        Paint clock = Skin.text(24, Skin.TEXT, true);
        Date now = new Date();
        Skin.textRight(c, clockFmt.format(now), header.right, header.top + Skin.dp(21), clock);

        Paint date = Skin.text(8.5f, Skin.TEXT_DIM, false);
        date.setLetterSpacing(0.14f);
        Skin.textRight(c, dateFmt.format(now).toUpperCase(Locale.US), header.right,
                header.top + Skin.dp(34), date);

        // link + uptime strip
        String linkStr = link.type + (link.online ? " ONLINE" : " NO ROUTE");
        Paint lp = Skin.text(8.5f, link.online ? Skin.ION : Skin.FLARE, true);
        lp.setLetterSpacing(0.14f);
        Skin.textRight(c, linkStr, header.right, header.top + Skin.dp(46), lp);

        // rule under the header, with a live blip
        Paint rule = Skin.stroke(1f, Skin.EDGE);
        float ry = header.bottom;
        c.drawLine(header.left, ry, header.right, ry, rule);
        float blip = header.left + (phase * 4f % header.width());
        Paint bp = Skin.fill(Skin.PLASMA);
        c.drawRect(blip, ry - Skin.dp(1.5f), blip + Skin.dp(26), ry + Skin.dp(1.5f), bp);
    }

    // ---- reactor core ------------------------------------------------------

    /**
     * The hero. Everything that used to be a chunky segmented bar is now a
     * concentric ring around this, so the eye has one thing to land on
     * instead of six competing rectangles.
     */
    private void drawCore(Canvas c) {
        float breathe = 1f + 0.012f * (float) Math.sin(phase * 0.09f);
        float R = coreR * breathe;

        // ambient bloom behind everything
        Skin.halo(c, coreCx, coreCy, R * 1.9f, Skin.PLASMA, 0.55f + 0.45f * load);

        // fine outer graticule — detail that rewards looking closely
        drawTickRing(c, R * 1.46f, 96, -phase * 0.22f, Skin.alpha(Skin.EDGE, 0.75f), 3f);

        // slow sweep, faint
        Paint sweep = Skin.stroke(1.1f, Skin.alpha(Skin.PLASMA, 0.30f));
        float a = (float) Math.toRadians(phase * 1.6f % 360f);
        c.drawLine(coreCx, coreCy,
                coreCx + (float) Math.cos(a) * R * 1.42f,
                coreCy + (float) Math.sin(a) * R * 1.42f, sweep);

        // ---- three telemetry rings, outermost first --------------------
        ring(c, R * 1.30f, store.frac, "DSK", 3.2f);
        ring(c, R * 1.16f, mem.frac, "MEM", 3.2f);
        float pf = power.level < 0 ? 0 : power.level / 100f;
        ringInverted(c, R * 1.02f, pf, "PWR", 3.2f);

        // ---- the disc ---------------------------------------------------
        c.drawCircle(coreCx, coreCy, R * 0.88f,
                Skin.fill(Skin.alpha(Skin.VOID_2, 0.96f)));
        Skin.bloomCircle(c, coreCx, coreCy, R * 0.88f,
                Skin.alpha(Skin.EDGE, 1f), 1f, 5f);

        // load arc, the brightest thing on the screen
        RectF arc = new RectF(coreCx - R * 0.74f, coreCy - R * 0.74f,
                coreCx + R * 0.74f, coreCy + R * 0.74f);
        c.drawArc(arc, 132, 276, false,
                Skin.stroke(4.5f, Skin.alpha(Skin.PLASMA, 0.10f)));
        Skin.bloomArc(c, arc, 132, 276 * load, Skin.level(load), 4.5f, 7f);

        // press state
        if (corePress > 0.01f) {
            Skin.halo(c, coreCx, coreCy, R * 1.5f, Skin.MAGENTA, corePress);
            Skin.bloomCircle(c, coreCx, coreCy, R * (0.88f + 0.10f * corePress),
                    Skin.alpha(Skin.MAGENTA, corePress), 1.6f, 6f);
        }

        // ---- centre readout ---------------------------------------------
        Paint big = Skin.text(40, Skin.level(load), false);
        big.setLetterSpacing(-0.03f);
        String pct = String.valueOf(Math.round(load * 100));
        float pw = big.measureText(pct);
        Paint unit = Skin.text(14, Skin.alpha(Skin.level(load), 0.6f), false);
        float total = pw + unit.measureText("%");
        c.drawText(pct, coreCx - total / 2f, coreCy + Skin.dp(6), big);
        c.drawText("%", coreCx - total / 2f + pw, coreCy + Skin.dp(6), unit);

        Paint cap = Skin.text(6.5f, Skin.TEXT_DIM, false);
        cap.setLetterSpacing(0.42f);
        Skin.textCenter(c, "CORE LOAD", coreCx, coreCy + Skin.dp(20), cap);

        drawHistory(c);

        // voice affordance sits below the disc, out of the readout's way
        float pulse = 0.45f + 0.55f * (float) Math.abs(Math.sin(phase * 0.05f));
        Paint mic = Skin.text(6.5f, Skin.alpha(Skin.MAGENTA, pulse), true);
        mic.setLetterSpacing(0.34f);
        Skin.textCenter(c, "◉  TAP TO SPEAK", coreCx, coreCy + R * 1.72f, mic);
    }

    /** One labelled telemetry ring around the core. */
    private void ring(Canvas c, float r, float frac, String label, float widthDp) {
        RectF box = new RectF(coreCx - r, coreCy - r, coreCx + r, coreCy + r);
        c.drawArc(box, 132, 276, false,
                Skin.stroke(widthDp, Skin.alpha(Skin.PLASMA, 0.09f)));
        Skin.bloomArc(c, box, 132, 276 * Math.max(0f, Math.min(1f, frac)),
                Skin.level(frac), widthDp, 4f);
        ringLabel(c, r, label, Math.round(frac * 100) + "%", Skin.level(frac));
    }

    /** Same, but full is good — used for the power cell. */
    private void ringInverted(Canvas c, float r, float frac, String label, float widthDp) {
        RectF box = new RectF(coreCx - r, coreCy - r, coreCx + r, coreCy + r);
        c.drawArc(box, 132, 276, false,
                Skin.stroke(widthDp, Skin.alpha(Skin.PLASMA, 0.09f)));
        int col = Skin.level(1f - frac);
        Skin.bloomArc(c, box, 132, 276 * Math.max(0f, Math.min(1f, frac)),
                col, widthDp, 4f);
        ringLabel(c, r, label, Math.round(frac * 100) + "%", col);
    }

    /** Tiny tag at the ring's opening, where the arc leaves a natural gap. */
    private void ringLabel(Canvas c, float r, String label, String value, int color) {
        double a = Math.toRadians(90);          // bottom, inside the arc gap
        float x = coreCx + (float) Math.cos(a) * r;
        float y = coreCy + (float) Math.sin(a) * r;

        Paint l = Skin.text(6, Skin.alpha(Skin.TEXT_DIM, 1f), false);
        l.setLetterSpacing(0.22f);
        Paint v = Skin.text(7, color, true);

        float vw = v.measureText(value), lw = l.measureText(label);
        float startX = x - (vw + lw + Skin.dp(4)) / 2f;
        c.drawText(label, startX, y + Skin.dp(2.5f), l);
        c.drawText(value, startX + lw + Skin.dp(4), y + Skin.dp(2.5f), v);
    }

    /** Truncates to fit a width, with an ellipsis. */
    private static String clip(String s, Paint p, float max) {
        if (s == null) return "";
        if (max <= 0 || p.measureText(s) <= max) return s;
        int n = p.breakText(s, true, max - p.measureText("…"), null);
        return n <= 0 ? "" : s.substring(0, n).trim() + "…";
    }

    /** Time-of-day greeting, the one place the console addresses the user. */
    static String greeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour < 5) return "BURNING THE MIDNIGHT OIL";
        if (hour < 12) return "GOOD MORNING";
        if (hour < 17) return "GOOD AFTERNOON";
        if (hour < 22) return "GOOD EVENING";
        return "GOOD NIGHT";
    }

    private void drawTickRing(Canvas c, float r, int ticks, float rot, int color, float lenDp) {
        Paint p = Skin.stroke(1f, color);
        float len = Skin.dp(lenDp);
        for (int i = 0; i < ticks; i++) {
            double a = Math.toRadians(rot + i * (360.0 / ticks));
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            boolean major = i % 6 == 0;
            float l = major ? len * 1.8f : len;
            c.drawLine(coreCx + ca * r, coreCy + sa * r,
                    coreCx + ca * (r + l), coreCy + sa * (r + l), p);
        }
    }

    private void drawHistory(Canvas c) {
        Path path = new Path();
        float wRib = coreR * 1.15f;
        float hRib = Skin.dp(20);
        float x0 = coreCx - wRib / 2f;
        float yBase = coreCy - Skin.dp(40);
        int n = loadHistory.length;
        for (int i = 0; i < n; i++) {
            float val = loadHistory[(historyHead + i) % n];
            float x = x0 + wRib * (i / (float) (n - 1));
            float y = yBase - val * hRib;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, Skin.stroke(1.1f, Skin.alpha(Skin.PLASMA, 0.65f)));
    }

    // ---- telemetry stack ---------------------------------------------------

    /**
     * The bars are gone — their values live on the core's rings now. What is
     * left is a single quiet strip of the numbers a ring cannot express.
     */
    private void drawTelemetry(Canvas c) {
        float y = telemetry.top + telemetry.height() * 0.55f;
        float colW = telemetry.width() / 4f;

        stat(c, telemetry.left + colW * 0.5f, y, "MEMORY",
                Sys.bytes(mem.avail), "FREE");
        stat(c, telemetry.left + colW * 1.5f, y, "ARCHIVE",
                Sys.bytes(store.free), "FREE");
        stat(c, telemetry.left + colW * 2.5f, y, "THERMAL",
                Float.isNaN(thermal) ? "--" : Math.round(thermal) + "°", "CORE");
        stat(c, telemetry.left + colW * 3.5f, y, "UPTIME",
                shortUptime(), "");

        // hairline rules between the columns
        Paint sep = Skin.stroke(0.7f, Skin.alpha(Skin.EDGE, 0.6f));
        for (int i = 1; i < 4; i++) {
            float x = telemetry.left + colW * i;
            c.drawLine(x, y - Skin.dp(15), x, y + Skin.dp(7), sep);
        }
    }

    /** One column of the stat strip: label above, value below. */
    private void stat(Canvas c, float cx, float y, String label,
                      String value, String suffix) {
        Paint l = Skin.text(6, Skin.TEXT_DIM, false);
        l.setLetterSpacing(0.3f);
        Skin.textCenter(c, label, cx, y - Skin.dp(9), l);

        Paint v = Skin.text(11, Skin.TEXT, false);
        Skin.textCenter(c, value, cx, y + Skin.dp(4), v);

        if (suffix.length() > 0) {
            Paint s = Skin.text(5.5f, Skin.alpha(Skin.TEXT_DIM, 0.7f), false);
            s.setLetterSpacing(0.3f);
            Skin.textCenter(c, suffix, cx, y + Skin.dp(12), s);
        }
    }

    /** Days-and-hours, so uptime fits a narrow column. */
    private static String shortUptime() {
        long s = Sys.uptimeMs() / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    private void drawBar(Canvas c, float top, float h, String label, String value, float frac) {
        Paint k = Skin.text(8.5f, Skin.TEXT_DIM, false);
        k.setLetterSpacing(0.16f);
        c.drawText(label, telemetry.left, top + Skin.dp(10), k);

        Paint v = Skin.text(9.5f, Skin.TEXT, true);
        Skin.textRight(c, value, telemetry.right, top + Skin.dp(10), v);

        RectF bar = new RectF(telemetry.left, top + Skin.dp(16),
                telemetry.right, top + Skin.dp(24));
        // power cell reads inverted: full is good
        int color = label.startsWith("POWER") ? Skin.level(1f - frac) : Skin.level(frac);
        Skin.gauge(c, bar, frac, color);
    }

    // ---- module lattice ----------------------------------------------------

    /** Draws whichever big instrument the active layout calls for. */
    private void drawInstrument(Canvas c, int w, int h) {
        float top = header.bottom + Skin.dp(10);
        float bottom = neuralStrip.top - Skin.dp(10);
        Date now = new Date();

        switch (layout) {
            case Theme.ORBITAL:
                Layouts.drawOrbital(c, w, h, top, bottom, phase, load, power, mem, store);
                break;
            case Theme.TACTICAL:
                Layouts.drawTactical(c, w, h, top, bottom, phase, load, link);
                break;
            case Theme.MINIMAL:
                Layouts.drawMinimal(c, w, h, top, bottom,
                        clockFmt.format(now),
                        dateFmt.format(now), load, power, mem, store);
                break;
            case Theme.MAINFRAME:
                Layouts.drawMainframeHeader(c, w, top + Skin.dp(14), phase,
                        load, power, mem, store, link, thermal);
                break;
            case Theme.REACTOR:
                Layouts.drawReactor(c, w, h, top, bottom, phase,
                        load, power, mem, store, thermal);
                break;
            case Theme.HEXCELL:
                Layouts.drawHexcell(c, w, h, top, bottom, phase, load, power, mem, store);
                break;
            case Theme.CONSTELLATION:
                Layouts.drawConstellation(c, w, h, top, bottom, tileRects,
                        phase, load, mem, power, link);
                break;
            case Theme.HELIX:
                Layouts.drawHelix(c, w, h, top, bottom, phase, load, mem, power);
                break;
            case Theme.CASCADE:
                Layouts.drawCascade(c, w, h, top, bottom, phase, load, mem, store, power);
                break;
        }
    }

    // ---- notification bar ---------------------------------------------------

    /** Icons for the notification strip; refreshed by the activity. */
    private java.util.List<Ears.Note> notes = new java.util.ArrayList<Ears.Note>();
    private final RectF notifBar = new RectF();
    private RectF[] notifRects = new RectF[0];
    private int notifPressed = -1;
    private boolean notifShown = true;

    private boolean notifAccess = true;

    public void setNotifAccess(boolean granted) {
        notifAccess = granted; invalidate();
    }

    public void setNotes(java.util.List<Ears.Note> list, boolean show) {
        notes = list == null ? new java.util.ArrayList<Ears.Note>() : list;
        notifShown = show;
        notifRects = new RectF[Math.min(notes.size(), MAX_NOTIF_ICONS)];
        for (int i = 0; i < notifRects.length; i++) notifRects[i] = new RectF();
        invalidate();
    }

    private static final int MAX_NOTIF_ICONS = 7;

    /**
     * Space OS's own status strip, built from the notification feed rather
     * than from Android's status bar — which no app is allowed to read.
     */
    private void drawNotifBar(Canvas c) {
        if (!notifShown || notifBar.width() <= 0) return;

        if (notes.isEmpty()) {
            // "NO NOTIFICATIONS" when we simply are not allowed to look is a
            // lie that costs the user an hour wondering why the bar is empty.
            boolean denied = !notifAccess;
            Paint quiet = Skin.text(6.5f, Skin.alpha(
                    denied ? Skin.SOLAR : Skin.TEXT_DIM,
                    (denied ? 0.95f : 0.5f) * entry), false);
            quiet.setLetterSpacing(0.3f);
            c.drawText(denied ? "NOTIFICATION ACCESS OFF · TAP TO GRANT"
                              : "NO NOTIFICATIONS",
                    notifBar.left, notifBar.centerY() + Skin.dp(2), quiet);
            return;
        }

        float size = Skin.dp(20);
        float gap = Skin.dp(8);
        float x = notifBar.left;
        float cy = notifBar.centerY();

        int shownCount = Math.min(notes.size(), MAX_NOTIF_ICONS);
        for (int i = 0; i < shownCount; i++) {
            Ears.Note n = notes.get(i);
            float e = easeOut((entry - 0.03f * i) / 0.97f);
            if (e <= 0) continue;

            notifRects[i].set(x - Skin.dp(3), cy - size / 2 - Skin.dp(3),
                    x + size + Skin.dp(3), cy + size / 2 + Skin.dp(3));

            if (i == notifPressed) {
                Skin.halo(c, x + size / 2, cy, size * 1.5f, Skin.PLASMA, 1f);
            }
            if (n.icon != null) {
                n.icon.setAlpha((int) (255 * e));
                n.icon.setBounds((int) x, (int) (cy - size / 2),
                        (int) (x + size), (int) (cy + size / 2));
                n.icon.draw(c);
            } else {
                c.drawCircle(x + size / 2, cy, size / 2,
                        Skin.stroke(1.2f, Skin.alpha(Skin.PLASMA, e)));
            }
            x += size + gap;
        }

        // overflow count
        if (notes.size() > MAX_NOTIF_ICONS) {
            Paint more = Skin.text(8, Skin.alpha(Skin.PLASMA, entry), true);
            c.drawText("+" + (notes.size() - MAX_NOTIF_ICONS), x, cy + Skin.dp(3), more);
        }
    }

    /**
     * The right-hand half of the top strip: link, charge, microphone.
     *
     * This is where a status bar goes, and it is where these belong. They were
     * sitting in the header underneath instead, which left the whole strip
     * beside the punch-hole empty and pushed the clock and everything below it
     * down by a band's height for nothing. Notification icons on the left,
     * state on the right, all on the cutout line — the shape every Android has.
     */
    private void drawStatusStrip(Canvas c) {
        if (!notifShown || notifBar.width() <= 0) return;
        float cy = notifBar.centerY();
        float rx = notifBar.right;

        if (listening) {
            float pulse = 0.35f + 0.65f * (float) Math.abs(Math.sin(phase * 0.11f));
            c.drawCircle(rx - Skin.dp(3), cy, Skin.dp(2.6f),
                    Skin.fill(Skin.alpha(Skin.MAGENTA, 0.5f + 0.5f * pulse)));
            rx -= Skin.dp(11);
        }

        Paint v = Skin.text(9, Skin.TEXT, true);
        Paint lbl = Skin.text(6.5f, Skin.TEXT_DIM, false);
        lbl.setLetterSpacing(0.2f);

        // charge
        float pf = power.level < 0 ? 0 : power.level / 100f;
        int pcol = power.charging ? Skin.ION : Skin.level(1f - pf);
        String pct = power.level < 0 ? "--" : power.level + "%";
        v.setColor(pcol);
        Skin.textRight(c, pct, rx, cy + Skin.dp(3), v);
        rx -= v.measureText(pct) + Skin.dp(5);
        c.drawCircle(rx - Skin.dp(2.5f), cy, Skin.dp(2.5f), Skin.fill(pcol));
        rx -= Skin.dp(12);

        // link
        String net = link.online ? link.type : "OFFLINE";
        int ncol = link.online ? Skin.PLASMA : Skin.FLARE;
        v.setColor(ncol);
        Skin.textRight(c, net, rx, cy + Skin.dp(3), v);
        rx -= v.measureText(net) + Skin.dp(5);
        c.drawCircle(rx - Skin.dp(2.5f), cy, Skin.dp(2.5f), Skin.fill(ncol));
        rx -= Skin.dp(13);

        // cellular, beside the link so a dropped network is visible at a glance
        if (cellLabel != null) {
            boolean dead = cellLabel.startsWith("NO ") || cellLabel.startsWith("SIM");
            int ccol = dead ? Skin.FLARE : Skin.TEXT_DIM;
            Paint cv = Skin.text(8.5f, ccol, true);
            Skin.textRight(c, cellLabel, rx, cy + Skin.dp(3), cv);
            rx -= cv.measureText(cellLabel) + Skin.dp(5);
            if (!dead) rx = drawBars(c, rx, cy, cellLevel);
        }
    }

    /** Four ascending bars, lit to the reported signal level. */
    private float drawBars(Canvas c, float rightEdge, float cy, int level) {
        float bw = Skin.dp(2f), gap = Skin.dp(1.4f), maxH = Skin.dp(9);
        float x = rightEdge - (bw * 4 + gap * 3);
        for (int i = 0; i < 4; i++) {
            float h = maxH * (0.34f + 0.22f * i);
            float bx = x + i * (bw + gap);
            boolean lit = level > i;
            c.drawRect(bx, cy + maxH / 2 - h, bx + bw, cy + maxH / 2,
                    Skin.fill(lit ? Skin.PLASMA
                                  : Skin.alpha(Skin.TEXT_DIM, 0.30f)));
        }
        return x;
    }

    private boolean wallpaperOn;
    private int wallpaperDim = 55;

    public void setWallpaper(boolean on, int dim) {
        wallpaperOn = on; wallpaperDim = dim; invalidate();
    }

    /** Refreshed by the activity along with the rest of the telemetry. */
    private String cellLabel;
    private int cellLevel = -1;

    public void setCell(String label, int level) {
        cellLabel = label;
        cellLevel = level;
        invalidate();
    }

    private int hitNotif(float x, float y) {
        for (int i = 0; i < notifRects.length; i++) {
            if (notifRects[i] != null && notifRects[i].contains(x, y)) return i;
        }
        return -1;
    }

    /**
     * Ambient listening indicator — a small live dot, not a takeover. The mic
     * being open should be visible without covering what you are looking at.
     */
    private boolean listening;

    public void setListening(boolean on) { listening = on; invalidate(); }

    /** Whether the LLM link is configured; drives the AI status strip. */
    private boolean neuralOnline;

    public void setNeuralOnline(boolean on) { neuralOnline = on; invalidate(); }

    /**
     * A full-width, unmissable strip for the neural link. Burying this in one
     * of sixteen small tiles made the headline feature undiscoverable.
     */
    private void drawNeuralStrip(Canvas c) {
        if (neuralStrip.width() <= 0) return;
        float p = neuralPress;

        int accent = neuralOnline ? Skin.ION : Skin.MAGENTA;
        float cy = neuralStrip.centerY();

        // A hairline rule, not a filled slab. It used to be the loudest thing
        // on the screen while being the least important to look at.
        Skin.rule(c, neuralStrip.left, neuralStrip.bottom,
                neuralStrip.right, Skin.alpha(accent, 0.5f + 0.5f * p), 18);

        if (p > 0.01f) {
            Skin.halo(c, neuralStrip.centerX(), cy,
                    neuralStrip.height() * 1.6f, accent, p);
        }

        float cx = neuralStrip.left + Skin.dp(9);
        drawGlyph(c, "neural", cx, cy - Skin.dp(3), Skin.dp(8),
                Skin.alpha(accent, 0.9f));

        Paint title = Skin.text(9, accent, true);
        title.setLetterSpacing(0.2f);
        c.drawText(neuralOnline ? "AI LINKED" : "CONNECT AI",
                cx + Skin.dp(14), cy + Skin.dp(0.5f), title);

        Paint sub = Skin.text(6.5f, Skin.alpha(Skin.TEXT_DIM, 0.85f), false);
        sub.setLetterSpacing(0.14f);
        String hint = neuralOnline ? "agent console" : "gemini · openai · local";
        Skin.textRight(c, hint, neuralStrip.right - Skin.dp(14), cy + Skin.dp(0.5f), sub);

        // live lamp
        float pulse = 0.5f + 0.5f * (float) Math.abs(Math.sin(phase * 0.06f));
        c.drawCircle(neuralStrip.right - Skin.dp(4), cy,
                Skin.dp(2.8f), Skin.fill(Skin.alpha(accent, neuralOnline ? pulse : 0.5f)));
    }

    private void drawLattice(Canvas c) {
        if (layout == Theme.MAINFRAME) { drawMenuLattice(c); return; }
        if (layout == Theme.HEXCELL) { drawHexLattice(c); return; }
        if (layout == Theme.CASCADE) { drawStripLattice(c); return; }
        if (layout == Theme.CONSTELLATION) { drawStarLattice(c); return; }
        if (layout != Theme.BRIDGE) { drawPuckLattice(c); return; }

        Paint hdr = Skin.text(7.5f, Skin.TEXT_DIM, false);
        hdr.setLetterSpacing(0.22f);
        c.drawText("MODULE LATTICE", lattice.left, lattice.top - Skin.dp(6), hdr);


        for (int i = 0; i < MODULES.length; i++) {
            RectF r = tileRects[i];

            // staggered arrival: each tile trails the one before it
            float stagger = 0.055f * i;
            float e = easeOut((entry - stagger) / (1f - stagger * 0.5f));
            if (e <= 0f) continue;

            float p = press[i];
            c.save();
            // rise into place, then dip under the finger
            float scale = (0.86f + 0.14f * e) * (1f - 0.06f * p);
            c.translate(r.centerX(), r.centerY());
            c.scale(scale, scale);
            c.translate(-r.centerX(), -r.centerY() + (1f - e) * Skin.dp(18));

            float th = r.height();
            float labelSize = Math.min(7.5f, th * 0.155f / Skin.density);
            float labelBaseline = r.bottom - th * 0.13f;
            float glyphCy = r.top + th * 0.38f;
            float glyphR = Math.min(Skin.dp(13), th * 0.21f);

            // Borderless. Sixteen outlined boxes was the single biggest source
            // of visual noise on this screen — the grid itself already implies
            // the structure, so the frame was doing nothing but shouting.
            // A tile only materialises when you touch it.
            if (p > 0.01f) {
                Skin.halo(c, r.centerX(), glyphCy, glyphR * 3.4f, Skin.PLASMA, p);
                RectF pad = new RectF(r.left + Skin.dp(2), r.top + Skin.dp(2),
                        r.right - Skin.dp(2), r.bottom - Skin.dp(2));
                Skin.panel(c, pad, Skin.alpha(Skin.PLASMA, 0.75f * p),
                        Skin.alpha(Skin.PLASMA, 0.14f * p), 7);
            }

            drawGlyph(c, MODULES[i][0], r.centerX(), glyphCy,
                    glyphR * (1f + 0.10f * p),
                    Skin.alpha(blend(Skin.PLASMA, 0xFFFFFFFF, p * 0.55f),
                            (0.80f + 0.20f * p) * e));

            Paint name = Skin.text(labelSize,
                    Skin.alpha(blend(Skin.TEXT, Skin.PLASMA, p), e), true);
            name.setLetterSpacing(0.04f);
            Skin.textCenter(c, MODULES[i][1], r.centerX(), labelBaseline, name);

            c.restore();
        }
    }

    // ---- app dock ----------------------------------------------------------

    private boolean swipeHint = true;

    public void setSwipeHint(boolean on) { swipeHint = on; invalidate(); }

    /**
     * A handle at the very bottom saying the bridge opens upward.
     *
     * A gesture nobody can see is a gesture nobody uses, and the app drawer is
     * not something to make people guess at. It breathes slowly rather than
     * sitting still, because a static line reads as a divider.
     */
    private void drawSwipeHandle(Canvas c) {
        if (!swipeHint) return;
        float w = getWidth(), h = getHeight();
        float bw = Math.min(Skin.dp(96), w * 0.30f);
        float y = h - Skin.dp(fullscreen ? 5 : 4);
        float breathe = 0.45f + 0.25f * (float) Math.abs(Math.sin(phase * 0.030f));

        c.drawLine(w / 2f - bw / 2f, y, w / 2f + bw / 2f, y,
                Skin.stroke(3f, Skin.alpha(Skin.PLASMA, 0.16f)));
        c.drawLine(w / 2f - bw / 2f * breathe, y, w / 2f + bw / 2f * breathe, y,
                Skin.stroke(3f, Skin.alpha(Skin.PLASMA, breathe * 0.85f)));
    }

    private void drawDock(Canvas c) {
        if (dock.length == 0 || dockIsEmpty()) return;

        // The label used to sit at dockBar.top - 5dp, which put it straight
        // through the rule above it. It now has its own clear band, with the
        // edit affordance beside it.
        Paint hdr = Skin.text(7, Skin.alpha(Skin.TEXT_DIM, entry), false);
        hdr.setLetterSpacing(0.3f);
        float labelY = dockBar.top - Skin.dp(9);
        c.drawText("QUICK LAUNCH", dockBar.left, labelY, hdr);

        Paint ed = Skin.text(7, Skin.alpha(Skin.MAGENTA, entry * 0.9f), true);
        ed.setLetterSpacing(0.2f);
        Skin.textRight(c, "HOLD A SLOT TO EDIT", dockBar.right, labelY, ed);

        for (int i = 0; i < dock.length; i++) {
            Dock d = dock[i];
            if (d == null) continue;

            // A cleared slot draws NOTHING. It used to fall back to a
            // placeholder "apps" glyph inside a full panel, so removing an app
            // left an identical grey box behind — the opposite of removing it.
            if (d.empty) continue;

            RectF r = dockRects[i];
            float p = dockPress[i];
            float e = easeOut((entry - 0.30f - 0.04f * i) / 0.7f);
            if (e <= 0f) continue;

            c.save();
            float scale = (0.88f + 0.12f * e) * (1f - 0.07f * p);
            c.translate(r.centerX(), r.centerY());
            c.scale(scale, scale);
            c.translate(-r.centerX(), -r.centerY());

            Skin.panel(c, r, Skin.alpha(blend(Skin.EDGE, Skin.PLASMA, p), e),
                    blend(Skin.alpha(Skin.VOID_2, 0.7f * e),
                            Skin.alpha(Skin.PLASMA, 0.18f), p), 6);

            int s = (int) Skin.dp(26);
            float iconTop = r.top + Skin.dp(9);
            if (d.icon != null) {
                d.icon.setAlpha((int) (255 * e));
                d.icon.setBounds((int) (r.centerX() - s / 2f), (int) iconTop,
                        (int) (r.centerX() + s / 2f), (int) (iconTop + s));
                d.icon.draw(c);
            } else if ("ALL".equals(d.label)) {
                // only the app-drawer slot gets the lattice glyph
                drawGlyph(c, "apps", r.centerX(), iconTop + s / 2f, Skin.dp(11),
                        Skin.alpha(Skin.PLASMA, e));
            }

            Paint lp = Skin.text(6.5f, Skin.alpha(blend(Skin.TEXT_DIM, Skin.PLASMA, p), e), true);
            Skin.textCenter(c, d.label, r.centerX(), r.bottom - Skin.dp(7), lp);

            c.restore();
        }
    }

    /**
     * Compact circular pucks for the scattered layouts. A labelled rectangle
     * would collide with its neighbours on a ring, so these are glyph-only
     * and reveal their name only while held.
     */
    private void drawPuckLattice(Canvas c) {
        for (int i = 0; i < MODULES.length; i++) {
            RectF r = tileRects[i];
            if (r.isEmpty()) continue;

            float stagger = 0.035f * i;
            float e = easeOut((entry - stagger) / (1f - stagger * 0.5f));
            if (e <= 0f) continue;

            float p = press[i];
            float rad = Math.min(r.width(), r.height()) / 2f * (0.80f + 0.20f * e)
                    * (1f + 0.14f * p);
            float cx = r.centerX(), cy = r.centerY();

            c.drawCircle(cx, cy, rad,
                    Skin.fill(blend(Skin.alpha(Skin.VOID_2, 0.90f * e),
                            Skin.alpha(Skin.PLASMA, 0.30f), p)));
            c.drawCircle(cx, cy, rad,
                    Skin.stroke(1.2f, Skin.alpha(blend(Skin.EDGE, Skin.PLASMA, p), e)));

            drawGlyph(c, MODULES[i][0], cx, cy, rad * 0.52f,
                    Skin.alpha(blend(Skin.PLASMA, 0xFFFFFFFF, p * 0.5f), e));

            // name appears on press, so the ring stays uncluttered at rest
            if (p > 0.02f) {
                Paint n = Skin.text(8, Skin.alpha(Skin.PLASMA, p), true);
                n.setLetterSpacing(0.12f);
                Skin.textCenter(c, MODULES[i][1], cx, cy + rad + Skin.dp(12), n);
            }
        }
    }

    /** HEXCELL renders each module as a honeycomb cell with its name inside. */
    private void drawHexLattice(Canvas c) {
        for (int i = 0; i < MODULES.length; i++) {
            RectF r = tileRects[i];
            if (r.isEmpty()) continue;

            float stagger = 0.030f * i;
            float e = easeOut((entry - stagger) / (1f - stagger * 0.5f));
            if (e <= 0f) continue;
            float p = press[i];

            // shrink slightly so neighbouring cells keep a visible seam
            float inset = r.width() * (0.055f + 0.12f * (1f - e)) - r.width() * 0.05f * p;
            RectF box = new RectF(r.left + inset, r.top + inset * 1.15f,
                    r.right - inset, r.bottom - inset * 1.15f);
            android.graphics.Path hex = Layouts.hexPath(box);

            c.drawPath(hex, Skin.fill(blend(Skin.alpha(Skin.VOID_2, 0.92f * e),
                    Skin.alpha(Skin.PLASMA, 0.32f), p)));
            c.drawPath(hex, Skin.stroke(1.2f,
                    Skin.alpha(blend(Skin.EDGE, Skin.PLASMA, p), e)));

            float cx = box.centerX(), cy = box.centerY();
            drawGlyph(c, MODULES[i][0], cx, cy - box.height() * 0.10f,
                    box.height() * 0.20f,
                    Skin.alpha(blend(Skin.PLASMA, 0xFFFFFFFF, p * 0.5f), e));

            Paint n = Skin.text(6, Skin.alpha(blend(Skin.TEXT_DIM, Skin.PLASMA, p), e), true);
            n.setLetterSpacing(0.08f);
            Skin.textCenter(c, MODULES[i][1], cx, cy + box.height() * 0.30f, n);
        }
    }

    /** CASCADE renders the lattice as wide strips: glyph left, name beside it. */
    private void drawStripLattice(Canvas c) {
        for (int i = 0; i < MODULES.length; i++) {
            RectF r = tileRects[i];
            if (r.isEmpty()) continue;

            // strips arrive from the side they sit on, alternating
            float stagger = 0.030f * i;
            float e = easeOut((entry - stagger) / (1f - stagger * 0.5f));
            if (e <= 0f) continue;
            float p = press[i];

            float slide = (1f - e) * Skin.dp(26) * ((i % 2 == 0) ? -1f : 1f);
            c.save();
            c.translate(slide, 0);

            Skin.panel(c, r, Skin.alpha(blend(Skin.EDGE, Skin.PLASMA, p), e),
                    blend(Skin.alpha(Skin.VOID_2, 0.90f * e),
                            Skin.alpha(Skin.PLASMA, 0.26f), p), 5);

            // an accent rule down the leading edge
            c.drawLine(r.left + Skin.dp(1.5f), r.top + Skin.dp(5),
                    r.left + Skin.dp(1.5f), r.bottom - Skin.dp(5),
                    Skin.stroke(2f, Skin.alpha(Skin.PLASMA, (0.45f + 0.55f * p) * e)));

            float gx = r.left + Skin.dp(17);
            drawGlyph(c, MODULES[i][0], gx, r.centerY(), Skin.dp(8),
                    Skin.alpha(blend(Skin.PLASMA, 0xFFFFFFFF, p * 0.5f), e));

            Paint n = Skin.text(8.5f, Skin.alpha(blend(Skin.TEXT, Skin.PLASMA, p), e), true);
            n.setLetterSpacing(0.12f);
            c.drawText(MODULES[i][1], gx + Skin.dp(13),
                    r.centerY() + Skin.dp(3), n);

            c.restore();
        }
    }

    /** CONSTELLATION renders each module as a star with its name beneath. */
    private void drawStarLattice(Canvas c) {
        for (int i = 0; i < MODULES.length; i++) {
            RectF r = tileRects[i];
            if (r.isEmpty()) continue;

            float stagger = 0.030f * i;
            float e = easeOut((entry - stagger) / (1f - stagger * 0.5f));
            if (e <= 0f) continue;
            float p = press[i];

            float cx = r.centerX(), cy = r.centerY();
            float rad = Math.min(r.width(), r.height()) / 2f;

            // twinkle, seeded per star so they are not in lockstep
            float tw = 0.75f + 0.25f * (float) Math.sin(phase * 0.05f + i * 1.7f);
            float halo = rad * (0.62f + 0.30f * p) * tw;

            c.drawCircle(cx, cy, halo * 1.9f,
                    Skin.fill(Skin.alpha(Skin.PLASMA, 0.06f * e * (1f + p))));
            c.drawCircle(cx, cy, halo,
                    Skin.fill(Skin.alpha(blend(Skin.VOID_2, Skin.PLASMA, 0.35f + 0.55f * p), e)));
            c.drawCircle(cx, cy, halo,
                    Skin.stroke(1.1f, Skin.alpha(blend(Skin.PLASMA, 0xFFFFFFFF, p * 0.6f), e)));

            // four-point sparkle, the thing that makes it read as a star
            float sp = halo * 2.1f;
            Paint ray = Skin.stroke(0.9f, Skin.alpha(Skin.PLASMA, (0.30f + 0.5f * p) * e));
            c.drawLine(cx - sp, cy, cx + sp, cy, ray);
            c.drawLine(cx, cy - sp, cx, cy + sp, ray);

            Paint n = Skin.text(6.5f,
                    Skin.alpha(blend(Skin.TEXT_DIM, Skin.PLASMA, p), e), true);
            n.setLetterSpacing(0.14f);
            Skin.textCenter(c, MODULES[i][1], cx, cy + rad + Skin.dp(10), n);
        }
    }

    /** MAINFRAME renders the lattice as a numbered command menu. */
    private void drawMenuLattice(Canvas c) {
        for (int i = 0; i < MODULES.length; i++) {
            RectF r = tileRects[i];
            if (r.isEmpty()) continue;

            float e = easeOut((entry - 0.02f * i) / 0.98f);
            if (e <= 0f) continue;
            float p = press[i];

            if (p > 0.02f) {
                c.drawRect(r, Skin.fill(Skin.alpha(Skin.ION, 0.20f * p)));
            }

            Paint num = Skin.text(9, Skin.alpha(Skin.PLASMA_D, e), false);
            String idx = String.format(Locale.US, "%02d", i + 1);
            c.drawText(idx, r.left, r.bottom - Skin.dp(4), num);

            Paint nm = Skin.text(9, Skin.alpha(p > 0.5f ? Skin.TEXT : Skin.ION, e), p > 0.5f);
            c.drawText(MODULES[i][1].toLowerCase(Locale.US),
                    r.left + Skin.dp(24), r.bottom - Skin.dp(4), nm);
        }
    }

    /** Straight RGB blend, used for press/idle state crossfades. */
    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24), ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24), br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (((int) (aa + (ba - aa) * t)) << 24)
                | (((int) (ar + (br - ar) * t)) << 16)
                | (((int) (ag + (bg - ag) * t)) << 8)
                | ((int) (ab + (bb - ab) * t));
    }

    /**
     * Module glyphs, drawn as line art so they stay crisp at any density and
     * match the rest of the console rather than importing an icon set.
     */
    private void drawGlyph(Canvas c, String id, float cx, float cy, float s, int color) {
        Paint p = Skin.stroke(1.5f, color);
        p.setStrokeCap(Paint.Cap.ROUND);
        Paint f = Skin.fill(color);

        if (id.equals("apps")) {
            float g = s * 0.28f, b = s * 0.34f;
            for (int r = -1; r <= 1; r++)
                for (int col = -1; col <= 1; col++)
                    c.drawRect(cx + col * (b + g) - b / 2, cy + r * (b + g) - b / 2,
                            cx + col * (b + g) + b / 2, cy + r * (b + g) + b / 2, f);
        } else if (id.equals("files")) {
            c.drawRect(cx - s * .8f, cy - s * .5f, cx + s * .8f, cy + s * .6f, p);
            c.drawLine(cx - s * .8f, cy - s * .15f, cx + s * .8f, cy - s * .15f, p);
            c.drawLine(cx - s * .3f, cy - s * .5f, cx - s * .3f, cy - s * .15f, p);
        } else if (id.equals("comms")) {
            c.drawCircle(cx, cy, s * .3f, p);
            c.drawArc(new RectF(cx - s * .7f, cy - s * .7f, cx + s * .7f, cy + s * .7f), -60, 120, false, p);
            c.drawArc(new RectF(cx - s, cy - s, cx + s, cy + s), -55, 110, false, p);
        } else if (id.equals("media")) {
            c.drawRect(cx - s * .85f, cy - s * .6f, cx + s * .85f, cy + s * .6f, p);
            c.drawCircle(cx, cy, s * .3f, p);
            c.drawCircle(cx + s * .55f, cy - s * .35f, s * .08f, f);
        } else if (id.equals("sensors")) {
            c.drawCircle(cx, cy, s * .85f, p);
            c.drawLine(cx, cy - s * .85f, cx, cy + s * .85f, p);
            c.drawLine(cx - s * .85f, cy, cx + s * .85f, cy, p);
            c.drawCircle(cx + s * .35f, cy - s * .35f, s * .16f, f);
        } else if (id.equals("network")) {
            for (int i = 1; i <= 4; i++) {
                float bh = s * (0.2f + i * 0.28f);
                float bx = cx - s * .8f + (i - 1) * s * .45f;
                c.drawRect(bx, cy + s * .6f - bh, bx + s * .26f, cy + s * .6f, f);
            }
        } else if (id.equals("power")) {
            c.drawRect(cx - s * .55f, cy - s * .8f, cx + s * .55f, cy + s * .8f, p);
            c.drawLine(cx - s * .2f, cy - s * .95f, cx + s * .2f, cy - s * .95f, p);
            c.drawRect(cx - s * .35f, cy - s * .1f, cx + s * .35f, cy + s * .6f, f);
        } else if (id.equals("packages")) {
            Path box = new Path();
            box.moveTo(cx, cy - s * .85f); box.lineTo(cx + s * .8f, cy - s * .4f);
            box.lineTo(cx + s * .8f, cy + s * .45f); box.lineTo(cx, cy + s * .9f);
            box.lineTo(cx - s * .8f, cy + s * .45f); box.lineTo(cx - s * .8f, cy - s * .4f);
            box.close();
            c.drawPath(box, p);
            c.drawLine(cx, cy - s * .85f, cx, cy + s * .9f, p);
        } else if (id.equals("location")) {
            Path pin = new Path();
            pin.moveTo(cx, cy + s * .9f);
            pin.cubicTo(cx - s * .95f, cy - s * .1f, cx - s * .55f, cy - s * .9f, cx, cy - s * .9f);
            pin.cubicTo(cx + s * .55f, cy - s * .9f, cx + s * .95f, cy - s * .1f, cx, cy + s * .9f);
            c.drawPath(pin, p);
            c.drawCircle(cx, cy - s * .28f, s * .22f, f);
        } else if (id.equals("system")) {
            c.drawRect(cx - s * .6f, cy - s * .6f, cx + s * .6f, cy + s * .6f, p);
            c.drawRect(cx - s * .22f, cy - s * .22f, cx + s * .22f, cy + s * .22f, f);
            for (int i = -1; i <= 1; i++) {
                c.drawLine(cx + i * s * .35f, cy - s * .6f, cx + i * s * .35f, cy - s * .9f, p);
                c.drawLine(cx + i * s * .35f, cy + s * .6f, cx + i * s * .35f, cy + s * .9f, p);
                c.drawLine(cx - s * .6f, cy + i * s * .35f, cx - s * .9f, cy + i * s * .35f, p);
                c.drawLine(cx + s * .6f, cy + i * s * .35f, cx + s * .9f, cy + i * s * .35f, p);
            }
        } else if (id.equals("journal")) {
            // a stack of log lines with a recording dot
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * s * .48f;
                c.drawLine(cx - s * .8f, y, cx + s * .45f - i * s * .2f, y, p);
            }
            c.drawCircle(cx + s * .72f, cy - s * .48f, s * .16f, f);
        } else if (id.equals("scope")) {
            // concentric sweep with a bearing needle
            c.drawCircle(cx, cy, s * .85f, p);
            c.drawCircle(cx, cy, s * .45f, p);
            c.drawLine(cx, cy, cx + s * .6f, cy - s * .6f, p);
            c.drawCircle(cx + s * .6f, cy - s * .6f, s * .16f, f);
        } else if (id.equals("config")) {
            // a slider stack
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * s * .5f;
                c.drawLine(cx - s * .85f, y, cx + s * .85f, y, p);
                c.drawCircle(cx + (i == 0 ? -s * .3f : s * .35f * i), y, s * .18f, f);
            }
        } else if (id.equals("theme")) {
            // a palette: swatch stack with a highlight
            for (int i = 0; i < 3; i++) {
                float y = cy - s * .55f + i * s * .55f;
                c.drawRect(cx - s * .8f, y, cx + s * .2f, y + s * .34f,
                        i == 1 ? f : p);
            }
            c.drawCircle(cx + s * .58f, cy - s * .38f, s * .2f, p);
            c.drawCircle(cx + s * .58f, cy + s * .34f, s * .2f, f);
        } else if (id.equals("neural")) {
            // an uplink dish: local node beaming outward
            c.drawArc(new RectF(cx - s * .9f, cy - s * .35f, cx + s * .9f, cy + s * 1.1f),
                    200, 140, false, p);
            c.drawLine(cx, cy + s * .28f, cx, cy + s * .95f, p);
            c.drawCircle(cx, cy - s * .55f, s * .17f, f);
            for (int i = 1; i <= 2; i++) {
                c.drawArc(new RectF(cx - s * .3f * i - s * .18f, cy - s * .9f - s * .3f * i,
                                cx + s * .3f * i + s * .18f, cy - s * .2f + s * .3f * i),
                        215, 110, false, p);
            }
        } else if (id.equals("brain")) {
            // a node graph — retrieval fanning out from a centre
            c.drawCircle(cx, cy, s * .26f, f);
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(i * 60 + 12);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                float nx = cx + ca * s * .78f, ny = cy + sa * s * .78f;
                c.drawLine(cx + ca * s * .3f, cy + sa * s * .3f, nx, ny, p);
                c.drawCircle(nx, ny, s * (i % 2 == 0 ? .17f : .11f), p);
            }
        } else if (id.equals("shell")) {
            // a terminal window with a prompt caret
            c.drawRect(cx - s * .9f, cy - s * .7f, cx + s * .9f, cy + s * .7f, p);
            c.drawLine(cx - s * .9f, cy - s * .42f, cx + s * .9f, cy - s * .42f, p);
            Path caret = new Path();
            caret.moveTo(cx - s * .5f, cy - s * .12f);
            caret.lineTo(cx - s * .16f, cy + s * .16f);
            caret.lineTo(cx - s * .5f, cy + s * .44f);
            c.drawPath(caret, p);
            c.drawLine(cx + s * .02f, cy + s * .44f, cx + s * .55f, cy + s * .44f, p);
        } else if (id.equals("proc")) {
            // stacked task bars
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * s * .5f;
                c.drawLine(cx - s * .85f, y, cx + s * .3f + i * s * .28f, y, p);
                c.drawCircle(cx + s * .68f, y, s * .12f, f);
            }
        } else if (id.equals("clip")) {
            c.drawRect(cx - s * .6f, cy - s * .7f, cx + s * .6f, cy + s * .8f, p);
            c.drawRect(cx - s * .28f, cy - s * .92f, cx + s * .28f, cy - s * .55f, p);
            c.drawLine(cx - s * .3f, cy - s * .1f, cx + s * .3f, cy - s * .1f, p);
            c.drawLine(cx - s * .3f, cy + s * .28f, cx + s * .12f, cy + s * .28f, p);
        } else if (id.equals("display")) {
            c.drawRect(cx - s * .9f, cy - s * .65f, cx + s * .9f, cy + s * .45f, p);
            c.drawLine(cx - s * .35f, cy + s * .85f, cx + s * .35f, cy + s * .85f, p);
            c.drawLine(cx, cy + s * .45f, cx, cy + s * .85f, p);
            c.drawRect(cx - s * .5f, cy - s * .3f, cx + s * .5f, cy + s * .12f, f);
        } else if (id.equals("torch")) {
            Path beam = new Path();
            beam.moveTo(cx - s * .45f, cy - s * .8f); beam.lineTo(cx + s * .45f, cy - s * .8f);
            beam.lineTo(cx + s * .2f, cy + s * .85f); beam.lineTo(cx - s * .2f, cy + s * .85f);
            beam.close();
            c.drawPath(beam, p);
            c.drawLine(cx - s * .45f, cy - s * .38f, cx + s * .45f, cy - s * .38f, p);
        } else { // settings
            c.drawCircle(cx, cy, s * .35f, p);
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                c.drawLine(cx + ca * s * .55f, cy + sa * s * .55f,
                        cx + ca * s * .9f, cy + sa * s * .9f, p);
            }
        }
    }

    // =====================================================================
    // TOUCH
    // =====================================================================

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(x, y);
                dockPressed = pressed >= 0 ? -1 : hitDock(x, y);
                notifPressed = pressed < 0 && dockPressed < 0 ? hitNotif(x, y) : -1;
                themePressed = pressed < 0 && dockPressed < 0 && notifPressed < 0
                        && themeButton.contains(x, y);
                wordmarkPressed = pressed < 0 && dockPressed < 0 && !themePressed && wordmark.contains(x, y);
                neuralPressed = pressed < 0 && dockPressed < 0 && !wordmarkPressed && neuralStrip.contains(x, y);
                corePressed = pressed < 0 && dockPressed < 0 && !neuralPressed && !wordmarkPressed && inCore(x, y);
                // remember where an empty-space touch began, for the swipe
                swipeStartX = x; swipeStartY = y;
                swipeCandidate = pressed < 0 && dockPressed < 0 && notifPressed < 0
                        && !neuralPressed && !wordmarkPressed && !themePressed && !corePressed;
                if (swipeCandidate) {
                    swipeFired = false;
                    handler.postDelayed(emptyLongPress, 520);
                    return true;                    // watch for a swipe instead
                }
                // themePressed MUST be in this list. Leaving it out meant the
                // DOWN event returned false, so the view never received UP and
                // the theme chip was simply dead to the touch.
                if (pressed >= 0 || dockPressed >= 0 || corePressed || notifPressed >= 0
                        || neuralPressed || wordmarkPressed || themePressed) {
                    performHapticFeedback(1);
                    // arm the long-press for dock editing
                    if (dockPressed >= 0) {
                        longPressSlot = dockPressed;
                        handler.postDelayed(dockLongPress, 480);
                    }
                    invalidate();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (pressed >= 0 && hit(x, y) != pressed) { pressed = -1; invalidate(); }
                if (dockPressed >= 0 && hitDock(x, y) != dockPressed) {
                    dockPressed = -1; cancelDockLongPress(); invalidate();
                }
                if (swipeCandidate && !swipeFired
                        && (Math.abs(y - swipeStartY) > Skin.dp(14)
                            || Math.abs(x - swipeStartX) > Skin.dp(14))) {
                    cancelEmptyLongPress();   // moving means a swipe, not a hold
                }
                checkSwipe(x, y);
                if (corePressed && !inCore(x, y)) { corePressed = false; invalidate(); }
                if (neuralPressed && !neuralStrip.contains(x, y)) {
                    neuralPressed = false; invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                cancelEmptyLongPress();
                swipeCandidate = false; swipeFired = false;
                if (pressed >= 0) {
                    int idx = pressed;
                    pressed = -1;
                    fireWarp(tileRects[idx]);
                    if (listener != null) listener.onModule(MODULES[idx][0], tileRects[idx]);
                } else if (dockPressed >= 0) {
                    int idx = dockPressed;
                    dockPressed = -1;
                    cancelDockLongPress();
                    if (!longPressFired) {
                        fireWarp(dockRects[idx]);
                        if (dock[idx] != null && dock[idx].action != null) {
                            dock[idx].action.run();
                        }
                    }
                    longPressFired = false;
                } else if (neuralPressed) {
                    neuralPressed = false;
                    fireWarp(neuralStrip);
                    if (listener != null) listener.onModule("neural", neuralStrip);
                } else if (notifPressed >= 0) {
                    int idx = notifPressed;
                    notifPressed = -1;
                    if (listener != null) listener.onNotification(idx);
                } else if (themePressed) {
                    themePressed = false;
                    if (listener != null) listener.onPresets();
                } else if (wordmarkPressed) {
                    wordmarkPressed = false;
                    if (listener != null) listener.onPresets();
                } else if (corePressed) {
                    corePressed = false;
                    if (listener != null) listener.onVoice();
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                pressed = -1; dockPressed = -1;
                corePressed = false; neuralPressed = false;
                wordmarkPressed = false; themePressed = false; notifPressed = -1;
                cancelDockLongPress(); longPressFired = false;
                cancelEmptyLongPress(); swipeCandidate = false; swipeFired = false;
                invalidate(); return true;
        }
        return super.onTouchEvent(e);
    }

    /** Held a dock slot long enough to mean "change this one". */
    private int longPressSlot = -1;
    private boolean longPressFired;

    private final Runnable dockLongPress = new Runnable() {
        @Override public void run() {
            if (longPressSlot < 0 || dockPressed != longPressSlot) return;
            longPressFired = true;
            performHapticFeedback(0);
            dockPressed = -1;
            invalidate();
            if (listener != null) listener.onDockEdit(longPressSlot);
        }
    };

    private void cancelDockLongPress() {
        handler.removeCallbacks(dockLongPress);
        longPressSlot = -1;
    }

    private void fireWarp(RectF from) {
        warpX = from.centerX();
        warpY = from.centerY();
        warp = 1f;
    }

    /**
     * Which module tile is under a touch.
     *
     * The grid layouts tile the plane, so a rectangle test is exact. The
     * scattered ones do not, and two of them cannot use a rectangle test at
     * all: a honeycomb's cells only nest because their bounding boxes overlap,
     * and beads on the helix pack tighter than their boxes on a short screen.
     * With contains() the first overlapping box in index order wins, so a tap
     * near a seam opens the wrong module — the same way picking a palette off
     * a scrolled list used to select the one above it.
     *
     * Nearest centre is the correct test here, and for a honeycomb it is
     * exactly right: a hex grid IS the Voronoi diagram of its centres. It also
     * gives every tile its full cell as a touch target, so a small bead on a
     * cramped screen is still comfortably hittable.
     */
    private int hit(float x, float y) {
        if (!scattered(layout)) {
            for (int i = 0; i < tileRects.length; i++) {
                if (tileRects[i].contains(x, y)) return i;
            }
            return -1;
        }

        int best = -1;
        float bestD2 = Float.MAX_VALUE;
        float reach = 0f;
        for (int i = 0; i < tileRects.length; i++) {
            RectF r = tileRects[i];
            if (r.isEmpty()) continue;
            reach = Math.max(reach, Math.max(r.width(), r.height()));
            float dx = x - r.centerX(), dy = y - r.centerY();
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) { bestD2 = d2; best = i; }
        }
        // Bounded so a tap on empty space well away from the field still falls
        // through to the backdrop gestures instead of opening a module.
        return bestD2 <= reach * reach ? best : -1;
    }

    private static boolean scattered(int l) {
        return l == Theme.ORBITAL || l == Theme.TACTICAL || l == Theme.REACTOR
                || l == Theme.HEXCELL || l == Theme.CONSTELLATION || l == Theme.HELIX;
    }

    private int hitDock(float x, float y) {
        for (int i = 0; i < dockRects.length; i++) if (dockRects[i].contains(x, y)) return i;
        return -1;
    }

    /**
     * Vertical swipes on empty space.
     *
     * Up is the app lattice, the way every launcher's drawer opens, and down
     * is the notification shade. Recents moved to a long press: third-party
     * launchers do not get the system's swipe-up-and-hold recents animation —
     * on Samsung and Pixel that is wired to the stock launcher and cannot be
     * claimed — so it was only ever a substitute reached through the
     * accessibility service, and it does not deserve the gesture people
     * actually reach for.
     *
     * What each direction does is a setting; the activity decides.
     */
    private float swipeStartX, swipeStartY;
    private boolean swipeCandidate;
    private boolean swipeFired;

    private void checkSwipe(float x, float y) {
        if (!swipeCandidate || swipeFired) return;
        int dir = Gestures.vertical(x - swipeStartX, y - swipeStartY,
                getHeight() * 0.10f);
        if (dir == Gestures.NONE) return;
        swipeFired = true;
        cancelEmptyLongPress();
        performHapticFeedback(0);
        if (listener != null) listener.onSwipe(dir == Gestures.UP ? -1 : 1);
    }

    // ---- long press on empty space ----------------------------------------

    private final Runnable emptyLongPress = new Runnable() {
        @Override public void run() {
            if (!swipeCandidate || swipeFired) return;
            swipeFired = true;                       // consume the gesture
            performHapticFeedback(0);
            if (listener != null) listener.onRecents();
        }
    };

    private void cancelEmptyLongPress() { handler.removeCallbacks(emptyLongPress); }

    private boolean inCore(float x, float y) {
        float dx = x - coreCx, dy = y - coreCy;
        return dx * dx + dy * dy <= coreR * coreR;
    }

    private boolean corePressed;
    private float corePress;
}
