package com.spaceos.os;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Neural link configuration.
 *
 * Everything here is OFF until you fill it in. Space OS makes no network call
 * of any kind while {@link #enabled()} is false, which is the shipped state.
 *
 * The API key is stored in the app's private directory in plain text. Android
 * keeps that directory unreadable to other apps, but it is NOT encrypted at
 * rest — a rooted device or a full backup can read it. Use a key you are
 * willing to rotate.
 */
public class Config {

    private static final String FILE = "neural.cfg";

    // provider kinds
    public static final String OPENAI_COMPAT = "openai";
    public static final String ANTHROPIC = "anthropic";

    /** Presets: label, kind, base URL, suggested model. */
    public static final String[][] PRESETS = {
            // Google exposes Gemini through an OpenAI-compatible surface, so
            // no separate adapter is needed. Key from aistudio.google.com.
            // Model ids go stale — Google retired gemini-2.0-flash out from
            // under this list. Use FETCH MODELS in setup to pull the live
            // catalogue from the provider rather than trusting these defaults.
            {"Gemini",       OPENAI_COMPAT, "https://generativelanguage.googleapis.com/v1beta/openai",
                                                                                 "gemini-3.6-flash"},
            {"Gemini Pro",   OPENAI_COMPAT, "https://generativelanguage.googleapis.com/v1beta/openai",
                                                                                 "gemini-3.6-pro"},
            {"OpenRouter",   OPENAI_COMPAT, "https://openrouter.ai/api/v1",      "anthropic/claude-sonnet-4.5"},
            {"Anthropic",    ANTHROPIC,     "https://api.anthropic.com/v1",      "claude-sonnet-4-5"},
            {"OpenAI",       OPENAI_COMPAT, "https://api.openai.com/v1",         "gpt-4o-mini"},
            {"Groq",         OPENAI_COMPAT, "https://api.groq.com/openai/v1",    "llama-3.3-70b-versatile"},
            {"HuggingFace",  OPENAI_COMPAT, "https://router.huggingface.co/v1",  "meta-llama/Llama-3.3-70B-Instruct"},
            {"Ollama local", OPENAI_COMPAT, "http://127.0.0.1:11434/v1",         "llama3.2"},
            {"llama.cpp",    OPENAI_COMPAT, "http://127.0.0.1:8080/v1",          "local"},
            {"LM Studio",    OPENAI_COMPAT, "http://127.0.0.1:1234/v1",          "local"},
    };

    private final Map<String, String> values = new HashMap<String, String>();
    private final File file;

    /**
     * Process-wide single instance.
     *
     * There used to be one Config per component — Bridge, Neural and Island
     * each built their own and cached the file in memory at construction.
     * Turning the neural link on inside Neural therefore never reached
     * Bridge's copy, so the voice overlay kept insisting you connect a model
     * that was already connected, and the Island kept reporting it had no
     * accessibility permission long after it had been granted. One instance,
     * one source of truth.
     */
    private static Config instance;

    public static synchronized Config get(Context ctx) {
        if (instance == null) instance = new Config(ctx);
        return instance;
    }

    private Config(Context ctx) {
        file = new File(ctx.getApplicationContext().getFilesDir(), FILE);
        load();
        // Every Theme.apply in the app reaches its palette index through this
        // singleton, so seeding the CUSTOM row here means the eleven call
        // sites never have to remember to do it themselves.
        syncCustomPalette();
        chooseUnlockRoute(ctx.getApplicationContext());
    }

    // ---- accessors ---------------------------------------------------------

    public boolean enabled() {
        return "1".equals(get("enabled")) && endpoint().length() > 0 && model().length() > 0;
    }

    public void setEnabled(boolean on) { put("enabled", on ? "1" : "0"); }

    public String kind()     { return getOr("kind", OPENAI_COMPAT); }
    public String endpoint() { return getOr("endpoint", ""); }
    public String apiKey()   { return getOr("key", ""); }
    public String model()    { return getOr("model", ""); }
    public String preset()   { return getOr("preset", "—"); }

    public void setProvider(String presetLabel, String kind, String endpoint, String model) {
        put("preset", presetLabel);
        put("kind", kind);
        put("endpoint", endpoint);
        if (model != null && model.length() > 0) put("model", model);
    }

    public void setApiKey(String k) { put("key", k == null ? "" : k.trim()); }
    public void setModel(String m)  { put("model", m == null ? "" : m.trim()); }
    public void setEndpoint(String e) { put("endpoint", e == null ? "" : e.trim()); }

    /** Whether the agent may execute shell commands the model asks for. */
    public boolean shellAllowed() { return !"0".equals(getOr("shell", "1")); }
    public void setShellAllowed(boolean on) { put("shell", on ? "1" : "0"); }

    /** Whether the model may read file contents off the card. */
    public boolean filesAllowed() { return !"0".equals(getOr("files", "1")); }
    public void setFilesAllowed(boolean on) { put("files", on ? "1" : "0"); }

    /**
     * Whether the model may drive the phone through the accessibility bridge.
     * This is the broadest capability in the app: with it on, the model can
     * read any screen and tap anything on it, in any app.
     */
    public boolean handsAllowed() { return "1".equals(getOr("hands", "0")); }
    public void setHandsAllowed(boolean on) { put("hands", on ? "1" : "0"); }

    /** Whether personal data tools (contacts, location) are exposed. */
    public boolean personalAllowed() { return "1".equals(getOr("personal", "0")); }
    public void setPersonalAllowed(boolean on) { put("personal", on ? "1" : "0"); }

    /**
     * What you call it. Wake-word matching is done against this, so renaming
     * the assistant genuinely changes what it answers to.
     */
    public String assistantName() { return getOr("name", "jarvis").toLowerCase(Locale.US); }

    public void setAssistantName(String n) {
        if (n == null) return;
        n = n.trim().toLowerCase(Locale.US);
        if (n.length() == 0) return;
        put("name", n);
    }

    /** Title-cased for display and for the model's system prompt. */
    public static String displayName(String n) {
        if (n == null || n.length() == 0) return "Jarvis";
        return n.substring(0, 1).toUpperCase(Locale.US) + n.substring(1);
    }

    /** Keep the mic open and keep answering until dismissed. */
    public boolean continuousListening() { return !"0".equals(getOr("continuous", "1")); }
    public void setContinuousListening(boolean on) { put("continuous", on ? "1" : "0"); }

    /** How it decides a phrase was meant for it. */
    public static final int ADDRESS_ALWAYS = 0;   // act on everything heard
    public static final int ADDRESS_SMART = 1;    // judge it (default)
    public static final int ADDRESS_NAME = 2;     // name required, every time

    public int addressingMode() {
        try { return Integer.parseInt(getOr("address", "1")); }
        catch (Throwable t) { return ADDRESS_SMART; }
    }

    public void setAddressingMode(int mode) {
        put("address", String.valueOf(Math.max(0, Math.min(2, mode))));
    }

    public String addressingLabel() {
        switch (addressingMode()) {
            case ADDRESS_ALWAYS: return "EVERYTHING";
            case ADDRESS_NAME: return "NAME ONLY";
            default: return "SMART";
        }
    }

    /**
     * Open the microphone automatically whenever the bridge is on screen.
     * Off by default: it costs battery, and most recognition engines make a
     * network round trip for every phrase.
     */
    public boolean autoListen() { return "1".equals(getOr("autolisten", "0")); }
    public void setAutoListen(boolean on) { put("autolisten", on ? "1" : "0"); }

    /**
     * How many tool steps one request may take. Driving real apps needs a lot
     * of them, so the default is generous; UNLIMITED is capped in Agent only
     * by an absolute wall that exists to stop a looping model from running up
     * a bill overnight.
     */
    public static final int[] ROUND_OPTIONS = {20, 60, 150, 500};
    public static final String[] ROUND_LABELS = {"20", "60", "150", "UNLIMITED"};

    public int maxRounds() { return intOr("rounds", 60); }

    public void setMaxRounds(int r) { put("rounds", String.valueOf(r)); }

    public String maxRoundsLabel() {
        int r = maxRounds();
        for (int i = 0; i < ROUND_OPTIONS.length; i++) {
            if (ROUND_OPTIONS[i] == r) return ROUND_LABELS[i];
        }
        return String.valueOf(r);
    }

    public void cycleMaxRounds() {
        int cur = maxRounds();
        for (int i = 0; i < ROUND_OPTIONS.length; i++) {
            if (ROUND_OPTIONS[i] == cur) {
                setMaxRounds(ROUND_OPTIONS[(i + 1) % ROUND_OPTIONS.length]);
                return;
            }
        }
        setMaxRounds(ROUND_OPTIONS[0]);
    }

    /** Keep the CPU alive while a long agent run is in flight. */
    public boolean keepAwake() { return !"0".equals(getOr("awake", "1")); }
    public void setKeepAwake(boolean on) { put("awake", on ? "1" : "0"); }

    /**
     * Set when the user swipes up on the lock face. Island's USER_PRESENT
     * watch reads it so the bridge opens only after a swipe the user actually
     * made — not on every unlock of the day.
     */
    public boolean pendingEntry() { return "1".equals(getOr("pending", "0")); }
    public void setPendingEntry(boolean on) { put("pending", on ? "1" : "0"); }

    /** CRT scanline overlay. */
    public boolean scanlines() { return !"0".equals(getOr("scanlines", "1")); }
    public void setScanlines(boolean on) { put("scanlines", on ? "1" : "0"); }

    /** The boot POST sequence on cold start. */
    public boolean bootSequence() { return !"0".equals(getOr("boot", "1")); }
    public void setBootSequence(boolean on) { put("boot", on ? "1" : "0"); }

    /** Haptic tick on every press. */
    public boolean haptics() { return !"0".equals(getOr("haptics", "1")); }
    public void setHaptics(boolean on) { put("haptics", on ? "1" : "0"); }

    /** 24-hour clock on the bridge and lock face. */
    public boolean clock24() { return !"0".equals(getOr("clock24", "1")); }
    public void setClock24(boolean on) { put("clock24", on ? "1" : "0"); }

    /** Seconds beside the clock. */
    public boolean clockSeconds() { return !"0".equals(getOr("seconds", "1")); }
    public void setClockSeconds(boolean on) { put("seconds", on ? "1" : "0"); }

    /** Hide Android.s status bar so Space OS owns the whole screen. */
    public boolean fullscreen() { return "1".equals(getOr("fullscreen", "0")); }
    public void setFullscreen(boolean on) { put("fullscreen", on ? "1" : "0"); }

    /** Draw our own notification bar from the notification feed. */
    public boolean notifBar() { return !"0".equals(getOr("notifbar", "1")); }
    public void setNotifBar(boolean on) { put("notifbar", on ? "1" : "0"); }

    /** Show the Space OS face over the keyguard when the screen wakes. */
    public boolean lockFace() { return "1".equals(getOr("lockface", "0")); }
    public void setLockFace(boolean on) { put("lockface", on ? "1" : "0"); }

    /** Touchless scroll from the front camera. Off by default: it costs power. */
    public boolean gestures() { return "1".equals(getOr("gestures", "0")); }
    public void setGestures(boolean on) { put("gestures", on ? "1" : "0"); }

    // ---- quick launch dock -------------------------------------------------
    //
    // Each slot is either a package name, or a token:
    //   @dial @msg @web @cam   resolve to whatever the user's default is
    //   @all                   opens the full app lattice
    //   @empty                 an unused slot

    private static final String DOCK_DEFAULT = "@dial,@msg,@web,@cam,@all";

    public String[] dockSlots() {
        String raw = getOr("dock", DOCK_DEFAULT);
        String[] parts = raw.split(",");
        // always exactly five slots, so layout never has to special-case
        String[] out = new String[5];
        for (int i = 0; i < 5; i++) {
            out[i] = i < parts.length && parts[i].trim().length() > 0
                    ? parts[i].trim() : "@empty";
        }
        return out;
    }

    public void setDockSlot(int index, String token) {
        if (index < 0 || index > 4) return;
        String[] slots = dockSlots();
        slots[index] = token == null || token.length() == 0 ? "@empty" : token;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(slots[i]);
        }
        put("dock", sb.toString());
    }

    public void resetDock() { put("dock", DOCK_DEFAULT); }

    // ---- floating island ---------------------------------------------------

    public boolean islandEnabled() { return "1".equals(getOr("island", "0")); }
    public void setIslandEnabled(boolean on) { put("island", on ? "1" : "0"); }

    public int islandX() { return intOr("islandx", 24); }
    public int islandY() { return intOr("islandy", 220); }

    public void setIslandPos(int x, int y) {
        put("islandx", String.valueOf(x));
        put("islandy", String.valueOf(y));
    }

    // ---- home screen preset ------------------------------------------------

    public int layout()   { return intOr("layout", 0); }
    public int palette()  { return intOr("palette", 0); }
    public int backdrop() { return intOr("backdrop", 0); }

    public void setPreset(int layout, int palette, int backdrop) {
        put("layout", String.valueOf(layout));
        put("palette", String.valueOf(palette));
        put("backdrop", String.valueOf(backdrop));
    }

    // ---- wallpaper ----------------------------------------------------------

    /** How far the image is dimmed so the instrument stays readable, 0..100. */
    public int wallpaperDim() { return intOr("wp_dim", 55); }
    public void setWallpaperDim(int d) {
        put("wp_dim", String.valueOf(Math.max(0, Math.min(100, d))));
    }

    /** Whether the picture is used at all; the file survives being switched off. */
    public boolean wallpaperOn() { return !"0".equals(getOr("wp_on", "1")); }
    public void setWallpaperOn(boolean on) { put("wp_on", on ? "1" : "0"); }

    /** Also behind the lock screen, or only the bridge. */
    public boolean wallpaperOnLock() { return !"0".equals(getOr("wp_lock", "1")); }
    public void setWallpaperOnLock(boolean on) { put("wp_lock", on ? "1" : "0"); }

    // ---- home screen swipes -------------------------------------------------

    public static final int SWIPE_NONE   = 0;
    public static final int SWIPE_APPS   = 1;
    public static final int SWIPE_NOTIFS = 2;
    public static final int SWIPE_RECENTS = 3;
    public static final int SWIPE_VOICE  = 4;
    public static final int SWIPE_SEARCH = 5;
    public static final int SWIPE_QUICK  = 6;
    public static final int SWIPE_COUNT  = 7;

    public static final String[] SWIPE_NAMES = {
            "NOTHING", "APP LATTICE", "NOTIFICATIONS", "RECENTS",
            "VOICE", "SEARCH APPS", "QUICK SETTINGS"
    };

    public int swipeUp()   { return intOr("swipeup", SWIPE_APPS); }
    public int swipeDown() { return intOr("swipedown", SWIPE_NOTIFS); }

    public void setSwipeUp(int a)   { put("swipeup", String.valueOf(a % SWIPE_COUNT)); }
    public void setSwipeDown(int a) { put("swipedown", String.valueOf(a % SWIPE_COUNT)); }

    public static String swipeName(int a) {
        return SWIPE_NAMES[Math.max(0, Math.min(SWIPE_NAMES.length - 1, a))];
    }

    /** Swipe in from either edge to go back, on every screen but the bridge. */
    public boolean edgeBack() { return "1".equals(getOr("edgeback", "1")); }
    public void setEdgeBack(boolean on) { put("edgeback", on ? "1" : "0"); }

    /** Whether to draw the handle hinting that up opens something. */
    public boolean swipeHint() { return "1".equals(getOr("swipehint", "0")); }
    public void setSwipeHint(boolean on) { put("swipehint", on ? "1" : "0"); }

    // ---- lock screen hand-off ----------------------------------------------

    /**
     * Straight to the system credential screen. One gesture on the face, then
     * the PIN pad — exactly the same number of steps as the stock lock screen.
     */
    public static final int LOCK_DIRECT = 0;
    /**
     * Fingerprint or face on the Space OS face itself, via BiometricPrompt,
     * then dismiss. Whether the system accepts that as sufficient and skips
     * its own prompt varies by manufacturer, so this is offered, not assumed.
     */
    public static final int LOCK_BIOMETRIC = 1;
    /** Hand back to the stock keyguard. Its own swipe comes first. */
    public static final int LOCK_SYSTEM = 2;

    // ---- lock face styles ---------------------------------------------------

    public static final String[] LOCKFACE_NAMES = {
            "INSTRUMENT", "MINIMAL", "REACTOR", "TERMINAL", "ORBIT", "STACK"
    };
    public static final String[] LOCKFACE_DESC = {
            "clock, power ring and telemetry",
            "the time and the date, nothing else",
            "one enormous core, charge as containment",
            "boot readout, cursor blinking",
            "a world on its rings, clock above",
            "left-aligned card, big date, charge bar"
    };

    public int lockFaceStyle() { return intOr("lockface_style", 0); }
    public void setLockFaceStyle(int s) {
        put("lockface_style", String.valueOf(
                ((s % LOCKFACE_NAMES.length) + LOCKFACE_NAMES.length) % LOCKFACE_NAMES.length));
    }
    public String lockFaceStyleName() {
        return LOCKFACE_NAMES[Math.max(0,
                Math.min(LOCKFACE_NAMES.length - 1, lockFaceStyle()))];
    }

    /** Raise the identity check the moment the face appears. */
    public boolean scanOnWake() { return "1".equals(getOr("scanonwake", "0")); }
    public void setScanOnWake(boolean on) { put("scanonwake", on ? "1" : "0"); }

    /**
     * Picks the best unlock route this phone can actually offer, once.
     *
     * Two reasons this exists rather than a plain default. The constants were
     * renumbered — 0 used to mean LOCK_SYSTEM and now means LOCK_DIRECT — so
     * anyone who touched the setting under the old build is carrying a value
     * that no longer means what it did. And the best route is not the same on
     * every phone: with a fingerprint enrolled, asking on our own face and
     * then dismissing costs no swipes at all, which beats every other option.
     */
    private void chooseUnlockRoute(Context ctx) {
        if (getOr("lockroute_v3", "").length() > 0) return;   // already decided
        put("lockroute_v3", "1");

        // Never BIOMETRIC, and never scan-on-wake.
        //
        // The previous version of this picked both when a fingerprint was
        // enrolled, on the theory that scanning beats swiping. It does not:
        // BiometricPrompt is a system dialog, so choosing it means a modal
        // panel thrown over the lock face every single time the screen lights,
        // and a scan accepted there does not unlock the phone anyway — the
        // keyguard still wants the credential afterwards. Two interruptions
        // instead of one. Both stay available to choose; neither is a default.
        put("lockstyle", String.valueOf(LOCK_DIRECT));
        put("scanonwake", "0");
    }

    public int lockStyle() { return intOr("lockstyle", LOCK_DIRECT); }
    public void setLockStyle(int s) { put("lockstyle", String.valueOf(s)); }

    /**
     * "FINGERPRINT" was wrong. BiometricPrompt is asked for BIOMETRIC_WEAK,
     * which means whatever the phone has enrolled — face and fingerprint both.
     * There is no separate face-only mode to offer, and naming the option
     * after one of the two made the other look missing.
     */
    public String lockStyleLabel() {
        switch (lockStyle()) {
            case LOCK_BIOMETRIC: return "SCAN, THEN PIN";
            case LOCK_SYSTEM:    return "STOCK LOCK SCREEN";
            default:             return "PIN PAD";
        }
    }

    // ---- custom palette ----------------------------------------------------
    // Five knobs, not twelve colours. See Theme.setCustom for why.

    public int customAccent()   { return intOr("cus_accent", 190); }
    public int customSat()      { return intOr("cus_sat", 85); }
    public int customBgHue()    { return intOr("cus_bghue", 205); }
    public int customLight()    { return intOr("cus_light", 3); }
    public int customContrast() { return intOr("cus_contrast", 70); }

    public void setCustomKnob(int which, int value) {
        switch (which) {
            case 0: put("cus_accent", String.valueOf(value)); break;
            case 1: put("cus_sat", String.valueOf(value)); break;
            case 2: put("cus_bghue", String.valueOf(value)); break;
            case 3: put("cus_light", String.valueOf(value)); break;
            case 4: put("cus_contrast", String.valueOf(value)); break;
        }
        syncCustomPalette();
    }

    public int customKnob(int which) {
        switch (which) {
            case 0: return customAccent();
            case 1: return customSat();
            case 2: return customBgHue();
            case 3: return customLight();
            default: return customContrast();
        }
    }

    /**
     * Pushes the stored knobs into the CUSTOM palette row. Must run before any
     * Theme.apply that might land on CUSTOM, so the row is never the stale
     * placeholder it ships with.
     */
    public void syncCustomPalette() {
        Theme.setCustom(customAccent(), customSat(), customBgHue(),
                customLight(), customContrast());
    }

    private int intOr(String k, int fallback) {
        try { return Integer.parseInt(getOr(k, String.valueOf(fallback))); }
        catch (Throwable t) { return fallback; }
    }

    /** Masked key for display — never render the real thing on screen. */
    public String maskedKey() {
        String k = apiKey();
        if (k.length() == 0) return "NOT SET";
        if (k.length() <= 8) return "········";
        return k.substring(0, 4) + "····························" + k.substring(k.length() - 4);
    }

    // ---- storage -----------------------------------------------------------

    private synchronized String get(String k) { return values.get(k); }

    private synchronized String getOr(String k, String fallback) {
        String v = values.get(k);
        return v == null ? fallback : v;
    }

    private synchronized void put(String k, String v) {
        values.put(k, v);
        save();
    }

    private synchronized void save() {
        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new FileWriter(file, false));
            for (Map.Entry<String, String> e : values.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue().replace("\n", " "));
                w.newLine();
            }
        } catch (Throwable ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }

    private synchronized void load() {
        if (!file.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(file));
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                values.put(line.substring(0, t), line.substring(t + 1));
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    public synchronized void wipe() {
        values.clear();
        try { if (file.exists()) file.delete(); } catch (Throwable ignored) {}
    }
}
