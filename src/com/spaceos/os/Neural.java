package com.spaceos.os;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.os.Looper;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * NEURAL :: configure a language model and talk to it.
 *
 * Two panes in one screen — setup at the top, agent transcript below. The
 * transcript shows every tool the model runs and what came back, because an
 * agent with shell access on your phone should never be a black box.
 */
public class Neural extends Activity {

    // Swipe in from either edge to go back, the way the system gesture does.
    private EdgeBack edgeBack;

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (edgeBack == null) edgeBack = new EdgeBack(this);
        if (edgeBack.onDispatch(e)) return true;
        return super.dispatchTouchEvent(e);
    }

    private Config cfg;
    private Agent agent;

    private TextView out;
    private ScrollView scroll;
    private EditText input;
    private Bar bar;
    private LinearLayout setupPane;
    private boolean setupOpen = true;

    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    // =====================================================================

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Skin.init(this);
        cfg = Config.get(this);

        Theme.apply(cfg.palette());
        Skin.dressWindow(this, cfg.fullscreen());
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.VOID);

        bar = new Bar(this);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) Skin.dp(78)));

        ScrollView setupScroll = new ScrollView(this);
        setupPane = new LinearLayout(this);
        setupPane.setOrientation(LinearLayout.VERTICAL);
        setupPane.setBackgroundColor(Skin.VOID_2);
        setupScroll.addView(setupPane);
        root.addView(setupScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildSetup();

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

        root.addView(promptRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        // adjustResize is inert behind a fullscreen layout; lift it ourselves.
        Keyboard.liftAboveIme(root);
        banner();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fall_in, R.anim.fall_out);
    }

    // =====================================================================
    // SETUP PANE
    // =====================================================================

    private void buildSetup() {
        setupPane.removeAllViews();
        setupPane.setVisibility(setupOpen ? View.VISIBLE : View.GONE);
        if (!setupOpen) return;

        // One line that answers "why isn't it working" without hunting
        // through five separate toggles.
        setupPane.addView(setting("READINESS", readinessVerdict(), readinessDetail(),
                readinessReady() ? Skin.ION : Skin.SOLAR,
                new Runnable() { @Override public void run() {
                    // jump straight to whatever is actually missing
                    if (!cfg.enabled()) { toggleEnabled(); return; }
                    if (!cfg.handsAllowed()) { toggleHands(); return; }
                    if (!Hands.connected()) { showAccessibilityDirections(); return; }
                    if (!Island.isRunning() && !cfg.islandEnabled()) { toggleIsland(); return; }
                    buildSetup();
                } }));

        setupPane.addView(sectionLabel("LINK"));

        setupPane.addView(setting("STATUS",
                cfg.enabled() ? "ONLINE" : "OFFLINE",
                cfg.enabled() ? "network calls permitted" : "no network calls are made",
                cfg.enabled() ? Skin.ION : Skin.TEXT_DIM,
                new Runnable() { @Override public void run() { toggleEnabled(); } }));

        setupPane.addView(setting("PROVIDER", cfg.preset(),
                cfg.endpoint().length() == 0 ? "tap to choose" : cfg.endpoint(),
                Skin.PLASMA,
                new Runnable() { @Override public void run() { chooseProvider(); } }));

        setupPane.addView(setting("MODEL",
                cfg.model().length() == 0 ? "NOT SET" : cfg.model(),
                "tap to pick from the provider's live list", Skin.PLASMA,
                new Runnable() { @Override public void run() { fetchModels(); } }));

        setupPane.addView(setting("API KEY", cfg.maskedKey(),
                "stored unencrypted in app-private storage", Skin.SOLAR,
                new Runnable() { @Override public void run() {
                    editText("API KEY", "", true, new Consumer() {
                        @Override public void accept(String v) { cfg.setApiKey(v); buildSetup(); }
                    }); } }));

        setupPane.addView(setting("TEST LINK",
                pingState.length() == 0 ? "PING" : pingState,
                pingDetail.length() == 0
                        ? "send one tiny request and report what comes back"
                        : pingDetail,
                pingState.startsWith("OK") ? Skin.ION
                        : pingState.startsWith("FAIL") ? Skin.FLARE : Skin.MAGENTA,
                new Runnable() { @Override public void run() { ping(); } }));

        setupPane.addView(sectionLabel("VOICE"));

        setupPane.addView(setting("NAME", Config.displayName(cfg.assistantName()),
                "what it answers to", Skin.PLASMA,
                new Runnable() { @Override public void run() {
                    editText("ASSISTANT NAME", cfg.assistantName(), false, new Consumer() {
                        @Override public void accept(String v) {
                            cfg.setAssistantName(v);
                            buildSetup();
                            toast("I will answer to " + Config.displayName(cfg.assistantName()));
                        }
                    }); } }));

        setupPane.addView(setting("ALWAYS LISTENING",
                cfg.continuousListening() ? "ON" : "OFF",
                "keep the mic open until dismissed",
                cfg.continuousListening() ? Skin.ION : Skin.TEXT_DIM,
                new Runnable() { @Override public void run() {
                    cfg.setContinuousListening(!cfg.continuousListening());
                    buildSetup(); } }));

        boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        setupPane.addView(setting("FLOATING ISLAND",
                !cfg.islandEnabled() ? "OFF" : Island.isRunning() ? "FLOATING" : "NEEDS PERMISSION",
                !cfg.islandEnabled()
                        ? "a small pill on top of every app — tap it and speak"
                        : overlayOk ? "drag it anywhere; tap to talk from any app"
                                    : "grant Display over other apps to show it",
                !cfg.islandEnabled() ? Skin.TEXT_DIM
                        : Island.isRunning() ? Skin.ION : Skin.SOLAR,
                new Runnable() { @Override public void run() { toggleIsland(); } }));

        setupPane.addView(setting("HANDS FREE",
                cfg.autoListen() ? "ON" : "OFF",
                cfg.autoListen()
                        ? "mic opens by itself on the home screen"
                        : "tap the reactor core to start listening",
                cfg.autoListen() ? Skin.SOLAR : Skin.TEXT_DIM,
                new Runnable() { @Override public void run() {
                    cfg.setAutoListen(!cfg.autoListen());
                    syncIsland();
                    buildSetup();
                    if (cfg.autoListen()) {
                        toast("MIC WILL OPEN ON THE HOME SCREEN — COSTS BATTERY");
                    } } }));

        setupPane.addView(setting("ADDRESSING", cfg.addressingLabel(),
                addressingHint(), addressingColour(),
                new Runnable() { @Override public void run() {
                    cfg.setAddressingMode((cfg.addressingMode() + 1) % 3);
                    buildSetup(); } }));

        setupPane.addView(sectionLabel("SURFACE"));

        setupPane.addView(setting("FULLSCREEN",
                cfg.fullscreen() ? "ON" : "OFF",
                cfg.fullscreen()
                        ? "Android's status bar is hidden; swipe down to peek"
                        : "Android's status bar stays visible",
                cfg.fullscreen() ? Skin.ION : Skin.TEXT_DIM,
                new Runnable() { @Override public void run() {
                    cfg.setFullscreen(!cfg.fullscreen());
                    buildSetup();
                    toast(cfg.fullscreen()
                            ? "STATUS BAR HIDDEN ON THE BRIDGE"
                            : "STATUS BAR RESTORED"); } }));

        setupPane.addView(setting("NOTIFICATION BAR",
                !cfg.notifBar() ? "OFF" : Ears.connected() ? "LIVE" : "NEEDS ACCESS",
                !cfg.notifBar()
                        ? "no notification strip on the bridge"
                        : Ears.connected()
                            ? "Space OS draws its own from the notification feed"
                            : "grant Notification access to read the feed",
                !cfg.notifBar() ? Skin.TEXT_DIM
                        : Ears.connected() ? Skin.ION : Skin.SOLAR,
                new Runnable() { @Override public void run() { toggleNotifBar(); } }));

        setupPane.addView(setting("LOCK SCREEN FACE",
                cfg.lockFace() ? "ON" : "OFF",
                cfg.lockFace()
                        ? "Space OS shows over the keyguard; swipe up to unlock"
                        : "stock lock screen",
                cfg.lockFace() ? Skin.ION : Skin.TEXT_DIM,
                new Runnable() { @Override public void run() {
                    cfg.setLockFace(!cfg.lockFace());
                    if (cfg.lockFace() && !Island.isRunning()) {
                        toast("TURN THE ISLAND ON TOO — IT HOSTS THE SCREEN WATCH");
                    }
                    buildSetup(); } }));

        setupPane.addView(setting("TOUCHLESS SCROLL",
                cfg.gestures() ? "ON" : "OFF",
                cfg.gestures()
                        ? "wave a hand up or down at the front camera"
                        : "camera motion tracking — costs battery",
                cfg.gestures() ? Skin.SOLAR : Skin.TEXT_DIM,
                new Runnable() { @Override public void run() { toggleGestures(); } }));

        setupPane.addView(setting("JOURNAL",
                String.valueOf(Journal.size()),
                "every AI exchange, tool call and fault — open LOGS on the bridge",
                Skin.PLASMA,
                new Runnable() { @Override public void run() {
                    Intent i = new Intent(Neural.this, Module.class);
                    i.putExtra(Module.EXTRA_ID, "journal");
                    try { startActivity(i); } catch (Throwable ignored) {} } }));

        setupPane.addView(sectionLabel("AGENT"));

        setupPane.addView(setting("STEP LIMIT", cfg.maxRoundsLabel(),
                "tool steps one request may take before it pauses",
                cfg.maxRounds() >= 150 ? Skin.SOLAR : Skin.PLASMA,
                new Runnable() { @Override public void run() {
                    cfg.cycleMaxRounds(); buildSetup(); } }));

        setupPane.addView(setting("KEEP AWAKE", cfg.keepAwake() ? "ON" : "OFF",
                "holds the CPU awake so long runs survive the screen sleeping",
                cfg.keepAwake() ? Skin.ION : Skin.TEXT_DIM,
                new Runnable() { @Override public void run() {
                    cfg.setKeepAwake(!cfg.keepAwake()); buildSetup(); } }));

        setupPane.addView(sectionLabel("AGENT TOOLS"));

        setupPane.addView(setting("SHELL", cfg.shellAllowed() ? "ALLOWED" : "BLOCKED",
                "lets the model run shell commands on this device",
                cfg.shellAllowed() ? Skin.SOLAR : Skin.TEXT_DIM,
                new Runnable() { @Override public void run() {
                    cfg.setShellAllowed(!cfg.shellAllowed()); buildSetup(); } }));

        setupPane.addView(setting("FILES", cfg.filesAllowed() ? "ALLOWED" : "BLOCKED",
                "lets the model list and read files",
                cfg.filesAllowed() ? Skin.SOLAR : Skin.TEXT_DIM,
                new Runnable() { @Override public void run() {
                    cfg.setFilesAllowed(!cfg.filesAllowed()); buildSetup(); } }));

        boolean handsLive = Hands.connected();
        setupPane.addView(setting("PHONE CONTROL",
                !cfg.handsAllowed() ? "BLOCKED" : handsLive ? "LIVE" : "NEEDS SETUP",
                !cfg.handsAllowed()
                        ? "model cannot see or touch other apps"
                        : handsLive
                            ? "model can read any screen and tap anything"
                            : "enable \"Space OS Hands\" in Accessibility settings",
                !cfg.handsAllowed() ? Skin.TEXT_DIM : handsLive ? Skin.FLARE : Skin.SOLAR,
                new Runnable() { @Override public void run() { toggleHands(); } }));

        if (cfg.handsAllowed() && !handsLive) {
            setupPane.addView(setting("OPEN ACCESSIBILITY", "VECTOR",
                    "turn on Space OS Hands to finish enabling control", Skin.MAGENTA,
                    new Runnable() { @Override public void run() {
                        showAccessibilityDirections();
                    } }));
        }

        setupPane.addView(setting("PERSONAL DATA",
                cfg.personalAllowed() ? "ALLOWED" : "BLOCKED",
                "contacts and GPS position — off by default",
                cfg.personalAllowed() ? Skin.FLARE : Skin.ION,
                new Runnable() { @Override public void run() {
                    cfg.setPersonalAllowed(!cfg.personalAllowed()); buildSetup(); } }));

        setupPane.addView(setting("CLEAR KEY & CONFIG", "WIPE",
                "removes the API key and every setting above", Skin.FLARE,
                new Runnable() { @Override public void run() {
                    cfg.wipe(); buildSetup(); toast("NEURAL CONFIG WIPED"); } }));
    }

    /**
     * Full phone control needs four separate things to be true, granted in
     * three different places. When one is missing the symptom is always the
     * same — it opens an app and stops — so name the missing piece directly.
     */
    private boolean readinessReady() {
        return cfg.enabled() && cfg.handsAllowed() && Hands.connected();
    }

    private String readinessVerdict() {
        if (readinessReady()) return "READY";
        if (!cfg.enabled()) return "NO LINK";
        if (!cfg.handsAllowed()) return "NO CONTROL";
        return "NO HANDS";
    }

    private String readinessDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append(cfg.enabled() ? "link ✓" : "link ✗ tap STATUS");
        sb.append("  ·  ");
        sb.append(cfg.handsAllowed() ? "control ✓" : "control ✗ tap PHONE CONTROL");
        sb.append("  ·  ");
        if (Hands.connected()) sb.append("hands ✓");
        else if (Build.VERSION.SDK_INT >= 33) sb.append("hands ✗ tap for the 2-step setup");
        else sb.append("hands ✗ enable in Accessibility");
        sb.append("  ·  ");
        sb.append(Island.isRunning() ? "island ✓" : "island ✗");
        return sb.toString();
    }

    private String addressingHint() {
        switch (cfg.addressingMode()) {
            case Config.ADDRESS_ALWAYS:
                return "acts on every phrase it hears — including other people";
            case Config.ADDRESS_NAME:
                return "must hear \"" + cfg.assistantName() + "\" every single time";
            default:
                return "works out whether you meant it; name optional";
        }
    }

    private int addressingColour() {
        switch (cfg.addressingMode()) {
            case Config.ADDRESS_ALWAYS: return Skin.FLARE;
            case Config.ADDRESS_NAME: return Skin.TEXT_DIM;
            default: return Skin.ION;
        }
    }

    private View sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText("— " + text + " —");
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setTextSize(8f);
        t.setTextColor(Skin.EDGE);
        t.setLetterSpacing(0.3f);
        t.setPadding((int) Skin.dp(14), (int) Skin.dp(10),
                (int) Skin.dp(14), (int) Skin.dp(4));
        return t;
    }

    private View setting(String label, String value, String hint, int accent,
                         final Runnable action) {
        final Row row = new Row(label).value(value).sub(hint).accent(accent);
        RowView v = new RowView(this);
        v.bind(row, -1);            // settings rows carry no index
        v.setClickable(true);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                x.performHapticFeedback(1);
                action.run();
            }
        });
        return v;
    }

    private void toggleEnabled() {
        if (cfg.enabled()) {
            cfg.setEnabled(false);
            buildSetup();
            append("[ NEURAL LINK OFFLINE — no network calls ]", Skin.TEXT_DIM, true);
            return;
        }
        if (cfg.endpoint().length() == 0 || cfg.model().length() == 0) {
            toast("SET PROVIDER AND MODEL FIRST");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Enable neural link?")
                .setMessage("Space OS currently makes no network calls at all.\n\n"
                        + "Enabling this sends what you say — and any tool results the "
                        + "model asks for, which can include file contents and shell "
                        + "output — to:\n\n" + cfg.endpoint()
                        + "\n\nThat data leaves your device. Only enable this for a "
                        + "provider you trust.")
                .setPositiveButton("ENABLE", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        cfg.setEnabled(true);
                        buildSetup();
                        append("[ NEURAL LINK ONLINE — " + cfg.model() + " ]", Skin.ION, true);
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    /**
     * Phone control is the one capability that reaches outside this app
     * entirely, so it gets a real confirmation rather than a silent toggle.
     */
    private void toggleHands() {
        if (cfg.handsAllowed()) {
            cfg.setHandsAllowed(false);
            buildSetup();
            append("[ PHONE CONTROL DISABLED ]", Skin.TEXT_DIM, true);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Give the model your phone?")
                .setMessage("With phone control on, the model can:\n\n"
                        + "• read the contents of any app on screen\n"
                        + "• tap, swipe and type anywhere\n"
                        + "• navigate, open notifications, change settings\n\n"
                        + "It acts with your hands. A wrong call sends the real "
                        + "message, makes the real purchase, deletes the real file.\n\n"
                        + "You can revoke it instantly in Settings > Accessibility.")
                .setPositiveButton("ENABLE", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        cfg.setHandsAllowed(true);
                        buildSetup();
                        append("[ PHONE CONTROL ARMED — enable Space OS Hands "
                                + "in Accessibility to finish ]", Skin.SOLAR, true);
                        showAccessibilityDirections();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    /**
     * Android's Accessibility screen differs per manufacturer and there is no
     * reliable public intent that deep-links to one service's page. Samsung in
     * particular buries third-party services under "Installed apps", which
     * makes the generic screen a dead end. So: say exactly where to look.
     */
    /**
     * Two separate gates stand between here and a working accessibility
     * service, and neither is discoverable.
     *
     * On Android 13+ a sideloaded app cannot enable an accessibility service
     * at all until the user lifts "Restricted settings" from the app's own
     * info page. Tapping the toggle before that just produces a dialog saying
     * the setting is unavailable, with no route forward. That gate is
     * deliberate on Android's part — accessibility is exactly what malware
     * abuses — and it applies to every sideloaded APK, this one included.
     */
    private void showAccessibilityDirections() {
        boolean restrictedGate = Build.VERSION.SDK_INT >= 33;
        boolean samsung = android.os.Build.MANUFACTURER != null
                && android.os.Build.MANUFACTURER.toLowerCase(Locale.US).contains("samsung");

        StringBuilder msg = new StringBuilder();

        if (restrictedGate) {
            msg.append("STEP 1 — lift the sideload block\n\n")
               .append("Android ").append(Build.VERSION.RELEASE)
               .append(" blocks sideloaded apps from using Accessibility until you allow it:\n\n")
               .append("  ·  Tap OPEN APP INFO below\n")
               .append("  ·  Tap ⋮ in the top-right corner\n")
               .append("  ·  Tap \"Allow restricted settings\"\n\n")
               .append("If that menu item is missing, the block is already lifted.\n\n")
               .append("STEP 2 — turn the service on\n\n");
        } else {
            msg.append("Turn the service on:\n\n");
        }

        msg.append(samsung
                ? "  ·  Accessibility > Installed apps\n"
                  + "  ·  Tap \"Space OS Hands\"\n"
                  + "  ·  Turn it on and accept the prompt"
                : "  ·  Accessibility > Installed apps\n"
                  + "     (or \"Downloaded apps\" / \"Downloaded services\")\n"
                  + "  ·  Tap \"Space OS Hands\"\n"
                  + "  ·  Turn it on and accept the prompt");

        msg.append("\n\nCome back here afterwards — READINESS at the top shows hands ✓ "
                + "once it is live.");

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Enable Space OS Hands")
                .setMessage(msg.toString());

        if (restrictedGate) {
            b.setPositiveButton("OPEN APP INFO", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    openAppInfo();
                }
            });
            b.setNeutralButton("ACCESSIBILITY", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    openAccessibility();
                }
            });
        } else {
            b.setPositiveButton("OPEN SETTINGS", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { openAccessibility(); }
            });
        }
        b.setNegativeButton("LATER", null).show();
    }

    /** The app's own info page — where "Allow restricted settings" lives. */
    private void openAppInfo() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            toast("TAP ⋮ TOP-RIGHT → ALLOW RESTRICTED SETTINGS");
        } catch (Throwable t) {
            toast("CANNOT OPEN APP INFO");
        }
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            toast("CANNOT OPEN ACCESSIBILITY SETTINGS");
        }
    }

    /** Starts or stops the overlay, routing through the permission if needed. */
    private void toggleIsland() {
        if (cfg.islandEnabled()) {
            cfg.setIslandEnabled(false);
            try { stopService(new Intent(this, Island.class)); } catch (Throwable ignored) {}
            buildSetup();
            toast("ISLAND WITHDRAWN");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("GRANT DISPLAY OVER OTHER APPS, THEN TAP AGAIN");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {}
            cfg.setIslandEnabled(true);   // armed; starts once the grant lands
            buildSetup();
            return;
        }
        cfg.setIslandEnabled(true);
        startIsland();
        buildSetup();
        toast("ISLAND DEPLOYED");
    }

    /**
     * Touchless scroll needs the camera, the island (which hosts it) and the
     * accessibility service (which performs the scroll). Check all three and
     * say which one is missing rather than silently doing nothing.
     */
    private void toggleGestures() {
        if (cfg.gestures()) {
            cfg.setGestures(false);
            syncIsland();
            buildSetup();
            toast("TOUCHLESS SCROLL OFF");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 21);
            toast("CAMERA ACCESS REQUIRED");
            return;
        }
        cfg.setGestures(true);
        buildSetup();
        if (!Island.isRunning()) {
            toast("TURN THE ISLAND ON — IT HOSTS THE CAMERA");
        } else if (!Hands.connected()) {
            toast("ENABLE SPACE OS HANDS — IT PERFORMS THE SCROLL");
        } else {
            syncIsland();
            toast("WAVE A HAND AT THE FRONT CAMERA");
        }
    }

    private void syncIsland() {
        if (!Island.isRunning()) return;
        try {
            Intent i = new Intent(this, Island.class);
            i.setAction(Island.ACTION_SYNC);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable ignored) {}
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == 21 && r.length > 0
                && r[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toggleGestures();
        }
    }

    /**
     * The notification bar needs Notification access — a separate grant from
     * accessibility, on its own settings screen.
     */
    private void toggleNotifBar() {
        if (cfg.notifBar() && Ears.connected()) {
            cfg.setNotifBar(false);
            buildSetup();
            toast("NOTIFICATION BAR OFF");
            return;
        }
        cfg.setNotifBar(true);
        buildSetup();
        if (Ears.connected()) { toast("NOTIFICATION BAR ON"); return; }

        new AlertDialog.Builder(this)
                .setTitle("Grant notification access")
                .setMessage("Android does not let any app read the status bar, so "
                        + "Space OS cannot mirror it.\n\n"
                        + "What it can do is subscribe to the same notification feed "
                        + "the status bar is built from, and draw its own.\n\n"
                        + "On the next screen, find \"Space OS Notifications\" and "
                        + "turn it on.")
                .setPositiveButton("OPEN SETTINGS", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(
                                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                        } catch (Throwable t) {
                            toast("CANNOT OPEN NOTIFICATION SETTINGS");
                        }
                    }
                })
                .setNegativeButton("LATER", null)
                .show();
    }

    private void startIsland() {
        try {
            Intent i = new Intent(this, Island.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            toast("ISLAND FAILED: " + t.getClass().getSimpleName());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // If the overlay grant was just given, honour the armed setting.
        if (cfg.islandEnabled() && !Island.isRunning()
                && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))) {
            startIsland();
        }
        buildSetup();
    }

    private void chooseProvider() {
        final String[] labels = new String[Config.PRESETS.length];
        for (int i = 0; i < Config.PRESETS.length; i++) {
            labels[i] = Config.PRESETS[i][0] + "  ·  " + Config.PRESETS[i][2];
        }
        new AlertDialog.Builder(this)
                .setTitle("Provider")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String[] p = Config.PRESETS[which];
                        cfg.setProvider(p[0], p[1], p[2], p[3]);
                        buildSetup();
                        toast(p[0] + " selected");
                    }
                })
                .setNeutralButton("CUSTOM URL", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        editText("BASE URL (ending in /v1)", cfg.endpoint(), false,
                                new Consumer() {
                                    @Override public void accept(String v) {
                                        cfg.setProvider("Custom", Config.OPENAI_COMPAT, v, null);
                                        buildSetup();
                                    }
                                });
                    }
                })
                .show();
    }

    private interface Consumer { void accept(String value); }

    private void editText(String title, String initial, boolean secret,
                          final Consumer onDone) {
        final EditText field = new EditText(this);
        field.setTypeface(Typeface.MONOSPACE);
        field.setTextSize(13f);
        field.setTextColor(Skin.TEXT);
        field.setText(initial == null ? "" : initial);
        field.setSingleLine(true);
        field.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = (int) Skin.dp(18);
        field.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(field)
                .setPositiveButton("SET", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        onDone.accept(field.getText().toString().trim());
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    /**
     * Pulls the provider's real model list and lets the user pick. Falls back
     * to typing an id by hand if the provider will not enumerate.
     */
    private void fetchModels() {
        if (cfg.endpoint().length() == 0) { chooseProvider(); return; }
        if (cfg.apiKey().length() == 0) {
            toast("SET THE API KEY FIRST");
            return;
        }
        toast("FETCHING MODEL LIST…");
        new Thread(new Runnable() {
            @Override public void run() {
                List<String> models = null;
                String err = null;
                try { models = new LlmClient(cfg).listModels(); }
                catch (Throwable t) { err = String.valueOf(t.getMessage()); }

                final List<String> found = models;
                final String failure = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (found == null) {
                            append("model list failed: " + failure, Skin.FLARE, true);
                            toast("COULD NOT LIST MODELS — TYPE ONE MANUALLY");
                            typeModelManually();
                            return;
                        }
                        final String[] ids = found.toArray(new String[0]);
                        new AlertDialog.Builder(Neural.this)
                                .setTitle(ids.length + " models available")
                                .setItems(ids, new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        cfg.setModel(ids[w]);
                                        pingState = ""; pingDetail = "";
                                        buildSetup();
                                        toast(ids[w] + " selected");
                                    }
                                })
                                .setNeutralButton("TYPE MANUALLY",
                                        new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        typeModelManually();
                                    }
                                })
                                .show();
                    }
                });
            }
        }, "spaceos-models").start();
    }

    private void typeModelManually() {
        editText("MODEL ID", cfg.model(), false, new Consumer() {
            @Override public void accept(String v) {
                cfg.setModel(v);
                pingState = ""; pingDetail = "";
                buildSetup();
            }
        });
    }

    private String pingState = "", pingDetail = "";

    /**
     * The result used to be written only into the chat log, which sits behind
     * the setup pane — so pressing PING looked like it did nothing at all.
     * It now reports into the row itself and as a toast.
     */
    private void ping() {
        if (cfg.endpoint().length() == 0 || cfg.model().length() == 0) {
            toast("SET PROVIDER AND MODEL FIRST");
            pingState = "FAIL";
            pingDetail = "provider or model is not set";
            buildSetup();
            return;
        }
        boolean wasEnabled = cfg.enabled();
        if (!wasEnabled) cfg.setEnabled(true);       // temporary, for the probe

        pingState = "…";
        pingDetail = "contacting " + cfg.endpoint();
        buildSetup();
        append("> ping " + cfg.endpoint() + "  model=" + cfg.model(), Skin.MAGENTA, true);

        final boolean restore = !wasEnabled;
        new Thread(new Runnable() {
            @Override public void run() {
                final String result = new LlmClient(cfg).ping();
                if (restore) cfg.setEnabled(false);
                ui.post(new Runnable() {
                    @Override public void run() {
                        boolean ok = result.startsWith("OK");
                        pingState = ok ? "OK" : "FAIL";
                        // strip the prefix; the row shows the reason
                        int dash = result.indexOf('—');
                        pingDetail = dash >= 0
                                ? result.substring(dash + 1).trim() : result;
                        if (pingDetail.length() > 90) {
                            pingDetail = pingDetail.substring(0, 90) + "…";
                        }
                        append(result, ok ? Skin.ION : Skin.FLARE, true);
                        append("", Skin.TEXT, false);
                        toast(ok ? "LINK OK" : "PING FAILED — see the TEST LINK row");
                        buildSetup();
                    }
                });
            }
        }, "spaceos-ping").start();
    }

    // =====================================================================
    // CHAT
    // =====================================================================

    private View promptRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Skin.VOID_2);
        row.setPadding((int) Skin.dp(12), (int) Skin.dp(6),
                (int) Skin.dp(8), (int) Skin.dp(6));

        TextView caret = new TextView(this);
        caret.setText("◉");
        caret.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        caret.setTextSize(14f);
        caret.setTextColor(Skin.MAGENTA);
        caret.setPadding(0, 0, (int) Skin.dp(8), 0);
        row.addView(caret);

        input = new EditText(this);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(12.5f);
        input.setTextColor(Skin.TEXT);
        input.setHintTextColor(Skin.TEXT_DIM);
        input.setHint("ask the model");
        input.setBackground(null);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                if (a == EditorInfo.IME_ACTION_SEND
                        || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && e.getAction() == KeyEvent.ACTION_DOWN)) {
                    send(input.getText().toString());
                    return true;
                }
                return false;
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(this);
        clear.setText("X");
        clear.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        clear.setTextSize(12f);
        clear.setTextColor(Skin.MAGENTA);
        clear.setPadding((int) Skin.dp(10), (int) Skin.dp(6),
                (int) Skin.dp(10), (int) Skin.dp(6));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                buffer.clear();
                out.setText(buffer);
                if (agent != null) agent.reset();
                banner();
            }
        });
        row.addView(clear);
        return row;
    }

    private void banner() {
        append("SPACE OS :: NEURAL", Skin.PLASMA, true);
        if (!cfg.enabled()) {
            append("Link offline. No network calls are made in this state.",
                    Skin.TEXT_DIM, false);
            append("Choose a provider, set a model and key, then tap STATUS.",
                    Skin.TEXT_DIM, false);
        } else {
            append(cfg.preset() + " · " + cfg.model(), Skin.TEXT_DIM, false);
            append("Agent tools: "
                    + (cfg.shellAllowed() ? "shell " : "")
                    + (cfg.filesAllowed() ? "files " : "")
                    + (cfg.personalAllowed() ? "personal " : "")
                    + "telemetry apps sensors network", Skin.TEXT_DIM, false);
        }
        append("", Skin.TEXT, false);
    }

    private void send(String text) {
        if (text == null || text.trim().length() == 0) return;
        if (!cfg.enabled()) { toast("NEURAL LINK IS OFFLINE"); return; }
        if (busy) { toast("STILL WORKING"); return; }

        final String prompt = text.trim();
        input.setText("");
        append("> " + prompt, Skin.ION, true);
        keepTyping();
        busy = true;
        bar.invalidate();

        if (agent == null) agent = new Agent(this, cfg);

        new Thread(new Runnable() {
            @Override public void run() {
                agent.ask(prompt, new Agent.Trace() {
                    @Override public void onThought(final String t) {
                        line(t, Skin.TEXT, false);
                    }
                    @Override public void onToolCall(final String name, final String args) {
                        line("  ⟩ " + name + " " + args, Skin.MAGENTA, true);
                    }
                    @Override public void onProgress(final int step, final int limit) {
                        line("  · step " + step + " of " + limit, Skin.TEXT_DIM, false);
                    }
                    @Override public void onToolResult(final String name, final String result) {
                        String trimmed = result.length() > 1200
                                ? result.substring(0, 1200) + "\n  … (truncated in view)"
                                : result;
                        line(indent(trimmed), Skin.PLASMA_D, false);
                    }
                    @Override public void onAnswer(final String answer) {
                        line(answer, Skin.PLASMA, true);
                        line("", Skin.TEXT, false);
                        done();
                    }
                    @Override public void onFailure(final String message) {
                        line("[ FAULT ] " + message, Skin.FLARE, true);
                        line("", Skin.TEXT, false);
                        done();
                    }
                });
            }
        }, "spaceos-neural").start();
    }

    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String l : s.split("\n")) sb.append("    ").append(l).append('\n');
        return sb.toString().replaceAll("\n$", "");
    }

    private void done() {
        ui.post(new Runnable() {
            @Override public void run() { busy = false; bar.invalidate(); }
        });
    }

    private void line(final String text, final int color, final boolean bold) {
        ui.post(new Runnable() {
            @Override public void run() { append(text, color, bold); }
        });
    }

    private void append(String text, int color, boolean bold) {
        int start = buffer.length();
        buffer.append(text == null ? "" : text).append('\n');
        buffer.setSpan(new ForegroundColorSpan(color), start, buffer.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            buffer.setSpan(new StyleSpan(Typeface.BOLD), start, buffer.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (buffer.length() > 200000) buffer.delete(0, 60000);
        out.setText(buffer);
        ui.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    /** Keeps the caret in the prompt so you can type again immediately. */
    private void keepTyping() {
        if (input == null) return;
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

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // =====================================================================

    private class Bar extends View {
        private float phase;
        private final Handler h = new Handler(Looper.getMainLooper());
        private boolean running;

        Bar(Context c) { super(c); }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow(); running = true; postOnAnimation(anim);
        }
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow(); running = false; removeCallbacks(anim);
        }

        private final Runnable anim = new Runnable() {
            @Override public void run() {
                if (!running) return;
                phase += 1f; invalidate(); postOnAnimation(this);
            }
        };

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            c.drawColor(Skin.VOID);
            Skin.grid(c, w, hh, 26);
            Skin.stars(c, w, hh, phase, 20);

            float pad = Skin.dp(14), top = Skin.dp(28);

            Paint back = Skin.text(9, Skin.MAGENTA, true);
            back.setLetterSpacing(0.2f);
            c.drawText("< BRIDGE", pad, top, back);

            Paint t = Skin.text(19, Skin.PLASMA, true);
            t.setLetterSpacing(0.24f);
            c.drawText("NEURAL", pad, top + Skin.dp(24), t);

            Paint sub = Skin.text(8, Skin.TEXT_DIM, false);
            c.drawText(cfg.enabled() ? cfg.preset() + " · " + cfg.model()
                            : "link offline · no network calls",
                    pad, top + Skin.dp(37), sub);

            int lamp = busy ? Skin.SOLAR : cfg.enabled() ? Skin.ION : Skin.TEXT_DIM;
            String st = busy ? "WORKING" : cfg.enabled() ? "ONLINE" : "OFFLINE";
            Paint sp = Skin.text(8.5f, lamp, true);
            sp.setLetterSpacing(0.16f);
            Skin.textRight(c, st, w - pad, top, sp);

            float pulse = busy ? (0.5f + 0.5f * (float) Math.sin(phase * 0.25f)) : 1f;
            c.drawCircle(w - pad - Skin.dp(58), top - Skin.dp(3.5f), Skin.dp(3.5f),
                    Skin.fill(Skin.alpha(lamp, pulse)));

            String toggle = setupOpen ? "▲ HIDE SETUP" : "▼ SETUP";
            Paint tg = Skin.text(9, Skin.PLASMA, true);
            float tw = tg.measureText(toggle);
            toggleRect.set(w - pad - tw - Skin.dp(12), top + Skin.dp(14),
                    w - pad + Skin.dp(4), top + Skin.dp(34));
            Skin.panel(c, toggleRect, Skin.EDGE, Skin.alpha(Skin.PLASMA, 0.10f), 5);
            Skin.textRight(c, toggle, w - pad - Skin.dp(4), top + Skin.dp(28), tg);

            float ry = hh - Skin.dp(4);
            c.drawLine(pad, ry, w - pad, ry, Skin.stroke(1f, Skin.EDGE));
            float blip = pad + (phase * 6f % (w - pad * 2));
            c.drawRect(blip, ry - Skin.dp(1.5f), blip + Skin.dp(30), ry + Skin.dp(1.5f),
                    Skin.fill(Skin.PLASMA));

            Skin.scanlines(c, w, hh, phase);
        }

        private final RectF toggleRect = new RectF();

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            if (toggleRect.contains(e.getX(), e.getY())) {
                performHapticFeedback(1);
                setupOpen = !setupOpen;
                buildSetup();
                invalidate();
                return true;
            }
            if (e.getY() < Skin.dp(38)) {
                performHapticFeedback(1);
                finish();
            }
            return true;
        }
    }
}
