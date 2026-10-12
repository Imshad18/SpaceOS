package com.spaceos.os;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Space OS primary shell. Registered as HOME, so it can stand in as the whole
 * device surface; also launchable normally while you try it out.
 */
public class Bridge extends Activity implements HudView.OnModule, Voice.Host {

    /** POST runs once per process, not on every return to the home surface. */
    private static boolean booted = false;

    private static final int REQ_VOICE = 11;

    private HudView hud;
    private BootView boot;
    private FrameLayout root;
    private Voice voice;
    private VoiceView voiceView;
    private boolean torchOn = false;

    /**
     * Only permissions this APK actually declares. Asking for one that is not
     * in the manifest fails silently and leaves the user staring at a panel
     * that never works.
     */
    private static final String[] WANTED = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECORD_AUDIO,
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Skin.init(this);
        // Palette must land before any view is constructed — Skin's colours
        // are read at draw time, but backgrounds are set during inflation.
        Theme.apply(config().palette());
        Skin.scanlinesEnabled = config().scanlines();

        Skin.dressWindow(this, config().fullscreen() || config().notifBar());

        root = new FrameLayout(this);
        root.setBackgroundColor(Skin.VOID);

        hud = new HudView(this);
        hud.setOnModule(this);
        hud.setLayout(config().layout());
        hud.setBackdrop(config().backdrop());
        hud.setPaletteIndex(config().palette());
        hud.setFullscreen(config().fullscreen() || config().notifBar());
        hud.setClockFormat(config().clock24(), config().clockSeconds());
        hud.setWallpaper(config().wallpaperOn() && Wallpaper.isSet(this), config().wallpaperDim());
        root.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        if (!booted && config().bootSequence()) {
            boot = new BootView(this);
            boot.setOnDone(new BootView.OnDone() {
                @Override public void onBootComplete() { dismissBoot(); }
            });
            root.addView(boot, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            hud.setAlpha(0f);
        } else {
            booted = true;
        }

        setContentView(root);
        requestDeck();
        resolveDock();
        wakeBrain();

        // Bind the speech engine now, not on first tap. It takes a second or
        // two, and doing it lazily is why the first reply came back silent.
        voice = new Voice(this, this);
        voice.initTts();
    }

    /**
     * Hides Android's status bar so Space OS owns the whole screen.
     *
     * SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN alone only lays out UNDERNEATH the bar
     * — the bar stays visible on top. Actually hiding it needs
     * SYSTEM_UI_FLAG_FULLSCREEN, and IMMERSIVE_STICKY so a swipe from the top
     * still reveals it briefly and then hides it again.
     *
     * The navigation bar is deliberately left alone: hiding it breaks gesture
     * navigation on most devices.
     */
    /**
     * The bridge hides the system status bar whenever it is drawing its own.
     *
     * Otherwise you get both: Android's clock and battery in a strip along the
     * top, and Space OS's clock and battery in another strip immediately under
     * it, with the whole home screen pushed down by the height of a bar that
     * is saying the same things twice. Our bar then sits in the strip beside
     * the punch-hole where a status bar belongs, instead of below it.
     */
    private void applyImmersive() {
        Config c = config();
        Skin.dressWindow(this, c.fullscreen() || c.notifBar());
    }

    /**
     * Reads the real display-cutout inset and hands it to the HUD.
     *
     * Only available once the window has a decor view with insets attached,
     * so this runs on focus rather than in onCreate.
     */
    private void measureCutout() {
        if (hud == null || Build.VERSION.SDK_INT < 28) return;
        try {
            android.view.WindowInsets in =
                    getWindow().getDecorView().getRootWindowInsets();
            android.view.DisplayCutout cut = in == null ? null : in.getDisplayCutout();
            hud.setCutoutTop(cut == null ? 0f : cut.getSafeInsetTop());
        } catch (Throwable ignored) {}
    }

    /** Immersive is cleared by the system on focus change; re-assert it. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
            if (hud != null) hud.setFullscreen(config().fullscreen() || config().notifBar());
            measureCutout();
        }
    }

    /**
     * Re-reads every visual setting and pushes it into the view.
     *
     * These were only applied in onCreate, which never runs again once the
     * activity exists — so changing the clock format or the palette in CONFIG
     * and coming back here appeared to do nothing at all.
     */
    private void refreshFromConfig() {
        Config c = config();
        Theme.apply(c.palette());
        Skin.scanlinesEnabled = c.scanlines();
        if (hud != null) {
            hud.setLayout(c.layout());
            hud.setBackdrop(c.backdrop());
            hud.setPaletteIndex(c.palette());
            hud.setFullscreen(c.fullscreen() || c.notifBar());
            hud.setClockFormat(c.clock24(), c.clockSeconds());
            hud.setNeuralOnline(c.enabled());
            hud.setSwipeHint(c.swipeHint());
            hud.setWallpaper(c.wallpaperOn() && Wallpaper.isSet(Bridge.this), c.wallpaperDim());
        }
        if (root != null) root.setBackgroundColor(Skin.VOID);
        applyImmersive();
        measureCutout();
    }

    /** Corpus parsing and memory replay happen off the main thread. */
    private void wakeBrain() {
        Mind.buildAsync(this);
    }

    private Brain brain() { return Mind.brain(); }
    private Memory memory() { return Mind.memory(); }

    /**
     * Builds the quick-launch dock by resolving well-known intents against the
     * host, so it lands on whatever the user has actually set as their dialer,
     * browser and camera rather than a hardcoded package list.
     */
    private void resolveDock() {
        new Thread(new Runnable() {
            @Override public void run() {
                final PackageManager pm = getPackageManager();
                String[] slots = config().dockSlots();
                List<HudView.Dock> built = new ArrayList<HudView.Dock>();
                for (String slot : slots) built.add(buildSlot(pm, slot));

                final List<HudView.Dock> finalDock = built;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (hud != null) {
                            hud.setDock(finalDock.toArray(new HudView.Dock[0]));
                        }
                    }
                });
            }
        }, "spaceos-dock").start();
    }

    /** Turns one stored slot token into a live dock entry. */
    private HudView.Dock buildSlot(PackageManager pm, String token) {
        if (token.equals("@dial")) return resolve(pm, "CALL", new Intent(Intent.ACTION_DIAL));
        if (token.equals("@msg"))  return resolve(pm, "MSG", appIntent(Intent.CATEGORY_APP_MESSAGING));
        if (token.equals("@web"))  return resolve(pm, "WEB", appIntent(Intent.CATEGORY_APP_BROWSER));
        if (token.equals("@cam"))  return resolve(pm, "CAM",
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        if (token.equals("@all")) {
            return new HudView.Dock("ALL", null, new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Bridge.this, Module.class);
                    i.putExtra(Module.EXTRA_ID, "apps");
                    safeStart(i);
                }
            });
        }
        if (token.equals("@empty")) {
            return new HudView.Dock("", null, new Runnable() {
                @Override public void run() { toast("HOLD TO ASSIGN AN APP"); }
            }).markEmpty();
        }

        // otherwise it is a package name
        try {
            final Intent launch = pm.getLaunchIntentForPackage(token);
            if (launch == null) return new HudView.Dock("?", null, null);
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(token, 0);
            String label = pm.getApplicationLabel(ai).toString();
            Drawable icon = pm.getApplicationIcon(ai);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return new HudView.Dock(label.toUpperCase(Locale.US), icon, new Runnable() {
                @Override public void run() { safeStart(launch); }
            });
        } catch (Throwable t) {
            return new HudView.Dock("?", null, null);
        }
    }

    /** Long-pressed a dock slot: assign an app, clear it, or restore defaults. */
    @Override public void onDockEdit(final int slot) {
        final List<String> tokens = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();

        tokens.add("@dial"); labels.add("Default dialer");
        tokens.add("@msg");  labels.add("Default messaging");
        tokens.add("@web");  labels.add("Default browser");
        tokens.add("@cam");  labels.add("Default camera");
        tokens.add("@all");  labels.add("App drawer");
        tokens.add("@empty"); labels.add("— clear this slot —");

        try {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
            java.util.Collections.sort(apps,
                    new ResolveInfo.DisplayNameComparator(pm));
            for (ResolveInfo ri : apps) {
                String pkg = ri.activityInfo.packageName;
                if (tokens.contains(pkg)) continue;
                tokens.add(pkg);
                labels.add(ri.loadLabel(pm).toString());
            }
        } catch (Throwable ignored) {}

        new android.app.AlertDialog.Builder(this)
                .setTitle("Slot " + (slot + 1))
                .setItems(labels.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        config().setDockSlot(slot, tokens.get(w));
                        resolveDock();
                        toast("SLOT " + (slot + 1) + " → " + labels.get(w));
                    }
                })
                .setNeutralButton("RESET ALL",
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        config().resetDock();
                        resolveDock();
                        toast("DOCK RESET");
                    }
                })
                .show();
    }

    private static Intent appIntent(String category) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(category);
        return i;
    }

    /** Resolves one dock slot; falls back to a labelled empty slot. */
    private HudView.Dock resolve(PackageManager pm, String label, final Intent intent) {
        try {
            ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            Drawable icon = null;
            if (ri != null && ri.activityInfo != null) {
                try { icon = ri.loadIcon(pm); } catch (Throwable ignored) {}
            }
            return new HudView.Dock(label, icon, new Runnable() {
                @Override public void run() { safeStart(intent); }
            });
        } catch (Throwable t) {
            return new HudView.Dock(label, null, new Runnable() {
                @Override public void run() { safeStart(intent); }
            });
        }
    }

    /** Cross-dissolve the POST out and let the bridge cascade in behind it. */
    private void dismissBoot() {
        booted = true;
        if (boot == null) return;
        final View b = boot;
        boot = null;
        hud.setAlpha(0f);
        hud.animate().alpha(1f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();
        b.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(240)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (root != null) root.removeView(b);
                    }
                }).start();
        hud.start();
    }

    @Override protected void onResume() {
        super.onResume();
        if (boot != null) boot.start();
        else hud.start();
        refreshFromConfig();
        refreshNotifications();
        Ears.watch(noteWatcher);

        // Hands-free: if enabled, the mic opens by itself whenever the bridge
        // is on screen. This is as close to always-on as an ordinary app can
        // get — Android blocks background microphone access outright.
        if (config().autoListen() && boot == null) {
            hud.postDelayed(new Runnable() {
                @Override public void run() { startAmbientListening(); }
            }, 700);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        Ears.unwatch(noteWatcher);
        hud.stop();
        if (boot != null) boot.stop();
        if (voice != null) { voice.stopListening(); voice.shutUp(); }
        if (voiceView != null) voiceView.stop();
        Mind.flush();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (voice != null) voice.release();
    }

    /** As a home screen, back must not drop the user out to nothing. */
    @Override public void onBackPressed() { /* held */ }

    // =====================================================================
    // PERMISSIONS
    // =====================================================================

    private void requestDeck() {
        List<String> ask = new ArrayList<String>();
        for (String p : WANTED) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        }
        // storage split at API 33
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(ask, "android.permission.READ_MEDIA_IMAGES");
        } else {
            addIfMissing(ask, Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (!ask.isEmpty()) {
            requestPermissions(ask.toArray(new String[0]), 7);
        }
    }

    private void addIfMissing(List<String> ask, String perm) {
        try {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) ask.add(perm);
        } catch (Throwable ignored) {}
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (hud != null) hud.invalidate();
    }

    // =====================================================================
    // MODULE DISPATCH
    // =====================================================================

    @Override public void onModule(String id, RectF from) {
        if (id.equals("torch")) { toggleTorch(); return; }
        if (id.equals("theme")) { showPresets(); return; }
        if (id.equals("brain")) Mind.buildAsync(this);
        if (id.equals("neural")) { safeStart(new Intent(this, Neural.class)); return; }
        if (id.equals("scope")) { safeStart(new Intent(this, Scope.class)); return; }
        if (id.equals("settings")) { safeStart(new Intent(Settings.ACTION_SETTINGS)); return; }

        Intent i;
        if (id.equals("shell")) {
            i = new Intent(this, Terminal.class);
        } else {
            i = new Intent(this, Module.class);
            i.putExtra(Module.EXTRA_ID, id);
        }

        // Grow the module out of the tile that launched it.
        try {
            ActivityOptions opts = ActivityOptions.makeScaleUpAnimation(
                    hud,
                    (int) from.left, (int) from.top,
                    (int) from.width(), (int) from.height());
            startActivity(i, opts.toBundle());
        } catch (Throwable t) {
            safeStart(i);
            overridePendingTransition(R.anim.warp_in, R.anim.warp_out);
        }
    }

    private void toggleTorch() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String target = null;
            for (String camId : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(camId);
                Boolean has = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (has != null && has) { target = camId; break; }
            }
            if (target == null) { toast("NO BEACON ON THIS HULL"); return; }
            torchOn = !torchOn;
            cm.setTorchMode(target, torchOn);
            toast(torchOn ? "BEACON ENGAGED" : "BEACON DARK");
        } catch (Throwable t) {
            torchOn = false;
            toast("BEACON FAULT: " + t.getClass().getSimpleName());
        }
    }

    void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("NO HANDLER FOR THAT VECTOR");
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    // VOICE
    // =====================================================================

    @Override public void onPresets() { showPresets(); }

    // =====================================================================
    // NOTIFICATIONS
    // =====================================================================

    private java.util.List<Ears.Note> notes = new java.util.ArrayList<Ears.Note>();

    /** Pulls the feed on a worker thread; icons are expensive to load. */
    private void refreshNotifications() {
        if (!config().notifBar()) {
            if (hud != null) hud.setNotes(null, false);
            return;
        }
        final boolean granted = Ears.accessGranted(this);
        if (hud != null) hud.setNotifAccess(granted);
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.List<Ears.Note> fresh = Ears.active(Bridge.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        notes = fresh;
                        if (hud != null) hud.setNotes(fresh, true);
                    }
                });
            }
        }, "spaceos-notes").start();
    }

    /**
     * Live feed while the bridge is on screen.
     *
     * Pulling once in onResume meant a launcher — the app you are looking at
     * when a notification arrives — was the one place that never showed it.
     */
    private final Ears.Watcher noteWatcher = new Ears.Watcher() {
        @Override public void onNotesChanged() { refreshNotifications(); }
    };

    /** Tapping an icon opens the notification, exactly as the shade would. */
    @Override public void onNotification(int index) {
        if (index < 0 || index >= notes.size()) {
            // Access not granted: the tap should fix that, not open an empty
            // list. This is the only route the strip offers when it is dark.
            if (!Ears.accessGranted(this)) {
                toast("TICK SPACE OS TO SEE NOTIFICATIONS");
                safeStart(new Intent(
                        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                return;
            }
            // strip itself — open the full list
            Intent i = new Intent(this, Module.class);
            i.putExtra(Module.EXTRA_ID, "notifs");
            safeStart(i);
            return;
        }
        // Show what it says before acting on it.
        //
        // Tapping an icon used to fire the notification's content intent
        // straight away, so a bare icon in a strip was the only thing you had
        // to go on — you had to open the app to find out what it wanted. A
        // notification is a message; read it first.
        peekNotification(notes.get(index));
    }

    private void peekNotification(final Ears.Note n) {
        String app = Ears.appName(this, n.pkg);
        // The title carries the notification's own headline; the app name is
        // context and belongs in the badge, not repeated as the heading.
        String heading = n.title.length() > 0 ? n.title : app;
        String body = n.text.length() > 0 ? n.text : "(no text)";

        Sheet s = new Sheet(this, heading).badge(app).body(body);
        s.action("OPEN", Skin.PLASMA, new Sheet.Action() {
            @Override public void run() { openNotification(n); }
        });
        if (n.clearable) {
            s.action("DISMISS", Skin.FLARE, new Sheet.Action() {
                @Override public void run() {
                    Ears.dismiss(n.key);
                    refreshNotifications();
                }
            });
        }
        s.action("CLOSE", Skin.TEXT_DIM, null);
        s.show();
    }

    private void openNotification(Ears.Note n) {
        if (n.open != null) {
            try {
                n.open.send();
                if (n.clearable) Ears.dismiss(n.key);
                return;
            } catch (Throwable t) {
                Journal.warn("ears", "could not open: " + t.getMessage());
            }
        }
        // no content intent — fall back to launching the app itself
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(n.pkg);
            if (launch != null) safeStart(launch);
            else toast(Ears.appName(this, n.pkg));
        } catch (Throwable ignored) {}
    }

    /**
     * Recents, via the accessibility service. A third-party launcher cannot
     * claim the system swipe-up-and-hold gesture, so this is the only route.
     */
    /** dir is -1 for a swipe up, +1 for a swipe down. */
    @Override public void onSwipe(int dir) {
        Config c = config();
        runGesture(dir < 0 ? c.swipeUp() : c.swipeDown());
    }

    private void runGesture(int action) {
        switch (action) {
            case Config.SWIPE_NONE:
                return;

            case Config.SWIPE_APPS:
                // Grow the drawer out of the APPS tile so the transition reads
                // as the lattice opening rather than a new screen appearing.
                openModuleFromTile("apps");
                return;

            case Config.SWIPE_SEARCH:
                openModuleFromTile("apps");
                return;

            case Config.SWIPE_RECENTS:
                onRecents();
                return;

            case Config.SWIPE_VOICE:
                onVoice();
                return;

            case Config.SWIPE_NOTIFS:
            case Config.SWIPE_QUICK: {
                boolean quick = action == Config.SWIPE_QUICK;
                // StatusBarManager first. It needs only EXPAND_STATUS_BAR,
                // which is a normal permission granted at install — no
                // accessibility service, no settings trip, no jargon at the
                // user about "hands". The accessibility route is the fallback
                // for phones where the hidden method has been removed.
                if (expandShade(quick)) return;

                Hands h = Hands.get();
                if (h == null) {
                    toast("CAN'T OPEN THE SHADE ON THIS PHONE");
                    Journal.warn("shade", "no StatusBarManager method and no "
                            + "accessibility service");
                    return;
                }
                h.global(quick ? "quick settings" : "notifications");
                return;
            }
        }
    }

    /**
     * Pulls down the shade through StatusBarManager.
     *
     * expandNotificationsPanel and expandSettingsPanel are @hide, so they are
     * only reachable reflectively — but the permission that guards them,
     * EXPAND_STATUS_BAR, is a normal one granted at install. That makes this
     * the route that costs the user nothing, as against an accessibility
     * service they have to go and enable through a restricted-settings warning.
     */
    private boolean expandShade(boolean quickSettings) {
        try {
            Object sb = getSystemService("statusbar");
            if (sb == null) return false;
            java.lang.reflect.Method m = sb.getClass().getMethod(
                    quickSettings ? "expandSettingsPanel" : "expandNotificationsPanel");
            m.invoke(sb);
            return true;
        } catch (Throwable t) {
            Journal.warn("shade", "StatusBarManager refused: " + t.getMessage());
            return false;
        }
    }

    /** Opens a module with the warp animation anchored on its own tile. */
    private void openModuleFromTile(String id) {
        RectF from = hud == null ? null : hud.tileFor(id);
        onModule(id, from);
    }

    @Override public void onRecents() {
        Hands h = Hands.get();
        if (h == null) {
            toast("ENABLE SPACE OS HANDS FOR RECENTS");
            return;
        }
        h.global("recents");
    }

    @Override public void onVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 9);
            toast("MIC ACCESS REQUIRED");
            return;
        }

        if (voice == null) {
            voice = new Voice(this, this);
            voice.initTts();
        }

        // No inline engine on this device — hand off to the system picker
        // rather than showing a listening screen that can never hear anything.
        if (!voice.available()) {
            try {
                startActivityForResult(Voice.pickerIntent(), REQ_VOICE);
            } catch (Throwable t) {
                toast("NO VOICE ENGINE ON THIS HULL");
            }
            return;
        }

        voice.setContinuous(config().continuousListening());
        // Tapping the core IS the invitation — the first thing you say after
        // it does not need the wake word.
        openFloorUntil = System.currentTimeMillis() + FOLLOW_UP_WINDOW_MS;
        showVoiceOverlay();
        voice.listen();
    }

    // =====================================================================
    // PRESETS
    // =====================================================================

    /** Slides the preset picker in over the bridge; changes apply live. */
    void showPresets() {
        if (presetView == null) {
            presetView = new PresetView(this);
            presetView.setOnPick(new PresetView.OnPick() {
                @Override public void onPick(int layout, int palette, int backdrop) {
                    config().setPreset(layout, palette, backdrop);
                    Theme.apply(palette);
                    hud.setLayout(layout);
                    hud.setBackdrop(backdrop);
                    hud.setPaletteIndex(palette);
                    root.setBackgroundColor(Skin.VOID);
                    // dressWindow, not a raw setNavigationBarColor — it also
                    // flips the light/dark system-bar icon flags, which is what
                    // switching between PAPER and PLASMA actually needs.
                    Skin.dressWindow(Bridge.this, config().fullscreen());
                    hud.invalidate();
                    presetView.invalidate();
                }
                @Override public void onCustomKnob(int which, int value) {
                    Config c = config();
                    c.setCustomKnob(which, value);   // also re-derives the row
                    if (c.palette() == Theme.CUSTOM) {
                        Theme.apply(Theme.CUSTOM);
                        root.setBackgroundColor(Skin.VOID);
                        Skin.dressWindow(Bridge.this, c.fullscreen());
                        hud.invalidate();
                        presetView.invalidate();
                    }
                }
                @Override public void onClose() { hidePresets(); }
            });
            root.addView(presetView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        presetView.setSelection(config().layout(), config().palette(), config().backdrop());
        Config pc = config();
        presetView.setCustomKnobs(new int[]{pc.customAccent(), pc.customSat(),
                pc.customBgHue(), pc.customLight(), pc.customContrast()});
        presetView.setVisibility(View.VISIBLE);
        presetView.setAlpha(0f);
        presetView.animate().alpha(1f).setDuration(180).start();
        presetView.start();
    }

    private void hidePresets() {
        if (presetView == null) return;
        final PresetView v = presetView;
        v.animate().alpha(0f).setDuration(160)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        v.stop();
                        v.setVisibility(View.GONE);
                    }
                }).start();
    }

    private PresetView presetView;

    /**
     * Hands-free listening with no UI in the way.
     *
     * Auto-listen used to throw the full listening overlay over the whole home
     * screen the moment you opened it, which is interference, not assistance.
     * The mic opens silently, the HUD shows a small live indicator, and the
     * overlay only appears once something is actually addressed to it.
     */
    private void startAmbientListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;
        if (voiceView != null && voiceView.getVisibility() == View.VISIBLE) return;

        // THE MICROPHONE IS A SINGLE RESOURCE.
        //
        // The island runs its own SpeechRecognizer, and this activity was
        // opening a second one on top of it. Two recognisers fighting over one
        // mic means whichever loses gets ERROR_RECOGNIZER_BUSY, so neither
        // reliably hears anything — which is exactly why speaking did nothing
        // and the only thing that ever worked was tapping.
        //
        // The island wins: it works in every app, not just here.
        if (Island.isRunning() && config().autoListen()) {
            if (hud != null) hud.setListening(true);   // the pill is holding it
            return;
        }

        if (voice == null) { voice = new Voice(this, this); voice.initTts(); }
        if (!voice.available()) return;

        ambient = true;
        openFloorUntil = 0;                 // ambient speech must earn attention
        voice.setContinuous(true);
        voice.listen();
        if (hud != null) hud.setListening(true);
    }

    /** True while listening without the overlay shown. */
    private boolean ambient;

    private void showVoiceOverlay() {
        if (voiceView == null) {
            voiceView = new VoiceView(this);
            voiceView.setOnDismiss(new VoiceView.OnDismiss() {
                @Override public void onDismiss() { hideVoiceOverlay(); }
            });
            root.addView(voiceView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        voiceView.reset();
        voiceView.setVisibility(View.VISIBLE);
        voiceView.start();
    }

    private void hideVoiceOverlay() {
        if (voice != null) voice.standDown();
        if (voiceView == null) return;
        final VoiceView v = voiceView;
        v.animate().alpha(0f).setDuration(180)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        v.stop();
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                    }
                }).start();
    }

    @Override public void onPartial(String text) {
        if (voiceView != null) voiceView.setTranscript(text);
    }

    @Override public void onLevel(float level) {
        if (voiceView != null) voiceView.setLevel(level);
    }

    @Override public void onVoiceState(int state, String message) {
        if (voiceView != null) voiceView.setState(state, message);
        if (state == Voice.ERROR) {
            // let the fault sit on screen briefly, then clear
            hud.postDelayed(new Runnable() {
                @Override public void run() { hideVoiceOverlay(); }
            }, 1800);
        }
    }

    @Override public void onHeard(String text) {
        final String lower = text == null ? "" : text.toLowerCase(Locale.US).trim();
        boolean continuous = config().continuousListening();

        // You just tapped the microphone, or you spoke moments after a reply.
        // In either case you are obviously talking TO it, so the name is not
        // required. Only genuinely ambient speech later on needs addressing.
        boolean invited = System.currentTimeMillis() < openFloorUntil;
        int mode = config().addressingMode();

        if (continuous && !invited && mode != Config.ADDRESS_ALWAYS) {
            boolean addressed;
            if (mode == Config.ADDRESS_NAME) {
                addressed = isAddressed(lower);
            } else {
                // SMART: the strongest evidence a phrase was meant for us is
                // that we could actually carry it out. Route it silently first,
                // then let the classifier weigh that alongside the wording.
                boolean actionable = probeActionable(lower);
                float conf = brain() == null ? 0f : brain().confidence(lower);
                Addressing.Verdict v = Addressing.judge(
                        lower, config().assistantName(), actionable, conf);
                addressed = v.addressed;
            }

            if (!addressed) {
                // Someone talking near the phone, not to it. Never put that on
                // screen — it is useless and it is a privacy problem.
                if (voiceView != null && !ambient) {
                    voiceView.setTranscript("");
                    voiceView.setState(Voice.LISTENING, "listening");
                }
                if (voice != null) voice.resumeListening();
                return;
            }

        }

        // Addressed at last — now the overlay has earned its place on screen.
        if (ambient) {
            ambient = false;
            if (hud != null) hud.setListening(false);
            showVoiceOverlay();
        }

        if (voiceView != null) {
            voiceView.setIgnored(false);
            voiceView.setTranscript(text);
        }
        openFloorUntil = 0;   // consumed; the next turn re-opens it after the reply

        // LOCAL FIRST. Simple commands are handled on-device, instantly, with
        // no network call and no quota spent. Only what the local layer cannot
        // do goes to the model.
        Quick.Result quick = Quick.run(this, config(), text);
        if (quick != null) {
            Journal.info("quick", text + " -> " + quick.text);
            deliver(quick.text, continuous, quick.speak);
            return;
        }

        if (config().enabled()) {
            askAgent(text);
            return;
        }

        String reply = route(text);
        deliver(reply, continuous);
    }

    /**
     * Speaks a reply, then either keeps listening or closes the overlay.
     * After speaking, the floor stays open briefly so a natural follow-up
     * ("and the storage?") works without saying the name again.
     */
    private void deliver(String reply, boolean continuous) {
        deliver(reply, continuous, true);
    }

    private void deliver(String reply, boolean continuous, boolean speak) {
        if (voiceView != null) voiceView.setReply(reply);
        if (voice != null) {
            // Silent results still have to hand the mic back, or the
            // continuous loop stops dead on the first launch command.
            if (speak) voice.say(reply);
            else voice.resumeListening();
        }
        openFloorUntil = System.currentTimeMillis() + FOLLOW_UP_WINDOW_MS;
        if (!continuous) {
            hud.postDelayed(new Runnable() {
                @Override public void run() { hideVoiceOverlay(); }
            }, 2600);
        }
    }

    /** How long after a reply a follow-up needs no wake word. */
    private static final long FOLLOW_UP_WINDOW_MS = 9000;

    /** Wall-clock deadline until which speech is assumed to be addressed to us. */
    private long openFloorUntil;

    // =====================================================================
    // NEURAL LINK
    // =====================================================================

    /** Runs the agent loop off the main thread, tracing each step to the UI. */
    private void askAgent(final String text) {
        if (agent == null) agent = new Agent(this, config());
        if (voiceView != null) voiceView.setState(Voice.THINKING, "neural link");

        new Thread(new Runnable() {
            @Override public void run() {
                agent.ask(text, new Agent.Trace() {
                    @Override public void onThought(final String t) {
                        post(new Runnable() { @Override public void run() {
                            if (voiceView != null) voiceView.setReply(t); } });
                    }
                    @Override public void onToolCall(final String name, String args) {
                        post(new Runnable() { @Override public void run() {
                            if (voiceView != null) {
                                voiceView.setState(Voice.THINKING, "running " + name);
                            } } });
                    }
                    @Override public void onToolResult(String name, String result) { }
                    @Override public void onProgress(final int step, final int limit) {
                        post(new Runnable() { @Override public void run() {
                            if (voiceView != null) {
                                voiceView.setState(Voice.THINKING,
                                        "step " + step + " of " + limit);
                            } } });
                    }
                    @Override public void onAnswer(final String answer) {
                        post(new Runnable() { @Override public void run() {
                            deliver(answer, config().continuousListening()); } });
                    }
                    @Override public void onFailure(final String message) {
                        post(new Runnable() { @Override public void run() {
                            // A dead link must not mean a dead phone. Quota
                            // exhausted, network down, key expired — fall back
                            // to the local layer so it can still open apps.
                            String local = Quick.tryLocal(Bridge.this, config(), text);
                            if (local != null) {
                                Journal.warn("agent", "fell back to local: " + message);
                                deliver(local, config().continuousListening());
                                return;
                            }
                            deliver(humanFault(message), config().continuousListening());
                        } });
                    }
                });
            }
        }, "spaceos-agent").start();
    }

    /** Turns a provider's raw error into something worth hearing aloud. */
    static String humanFault(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.US);
        if (s.contains("429") || s.contains("quota") || s.contains("rate limit")) {
            return "Your AI quota is used up. I can still open apps and read "
                    + "the phone, but I cannot think until it resets.";
        }
        if (s.contains("401") || s.contains("403") || s.contains("api key")
                || s.contains("unauthor")) {
            return "The API key was rejected. Check it in Neural setup.";
        }
        if (s.contains("404") || s.contains("no longer available")) {
            return "That model is gone. Tap MODEL in Neural to pick a current one.";
        }
        if (s.contains("timeout") || s.contains("timed out")) {
            return "The model timed out.";
        }
        if (s.contains("unable to resolve") || s.contains("network")
                || s.contains("connect")) {
            return "No connection to the model.";
        }
        return "Neural link fault. " + raw;
    }

    private void post(Runnable r) { runOnUiThread(r); }

    private Agent agent;

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_VOICE && result == RESULT_OK) {
            List<String> hits = Voice.resultsFrom(data);
            if (!hits.isEmpty()) {
                String reply = route(hits.get(0));
                toast(reply);
                if (voice != null) voice.say(reply);
            }
        }
    }

    /**
     * Maps a spoken phrase onto an action and returns what to say back.
     * Ordered most-specific first: "open camera" must beat the bare "camera".
     */
    private String route(String raw) {
        String reply = routeInner(raw);
        try {
            if (memory() != null) memory().record(raw, reply);
            if (brain() != null) brain().learn(raw);
        } catch (Throwable ignored) {}
        return reply;
    }

    private String routeInner(String raw) {
        if (raw == null) return "I did not catch that.";
        String s = raw.toLowerCase(Locale.US).trim();

        // --- wake word ----------------------------------------------------
        s = stripWakeWord(s);
        if (s.length() == 0) {
            return fill(pickOne(
                    "Standing by.", "Here. What do you need?",
                    "Listening.", "Go ahead.", "At your service."));
        }

        // --- explicit memory control --------------------------------------
        String memReply = memoryCommand(s);
        if (memReply != null) return memReply;

        String hard = hardCommand(s, raw);
        if (hard != null) return hard;

        // --- conversation -------------------------------------------------
        if (brain() != null) {
            String corpus = brain().respond(s);
            if (corpus != null) return fill(corpus);

            // nothing matched: fall back to the chain trained on this user
            String made = brain().generate(s, 12);
            if (made != null) {
                return "I am not certain. Working from what you have told me: " + made;
            }
        }

        return "I have no vector for \"" + raw + "\".";
    }

    /** Remembering, recalling and forgetting — spoken, not tapped. */
    private String memoryCommand(String s) {
        if (memory() == null) return null;

        // "my name is ..." / "call me ..."
        String name = after(s, "my name is ", "call me ", "i am called ");
        if (name != null && name.length() < 40) {
            memory().remember("name", name);
            return "Noted. I will call you " + name + ".";
        }

        // "remember that X" / "remember X"
        String fact = after(s, "remember that ", "remember ");
        if (fact != null && fact.length() > 2 && !fact.startsWith("me")) {
            memory().remember("note:" + System.currentTimeMillis(), fact);
            return "Stored.";
        }

        if (has(s, "what do you know about me", "what do you remember",
                "what have you learned")) {
            String n = memory().recall("name");
            StringBuilder sb = new StringBuilder();
            if (n != null) sb.append("Your name is ").append(n).append(". ");
            sb.append("We have exchanged ").append(memory().exchangeCount())
              .append(memory().exchangeCount() == 1 ? " message" : " messages")
              .append(". I have learned ").append(memory().vocabularySize())
              .append(" distinct words from you");
            List<String> top = memory().topWords(3);
            if (!top.isEmpty()) {
                sb.append(". You say \"").append(top.get(0)).append("\" most often");
            }
            sb.append('.');
            return sb.toString();
        }

        if (has(s, "forget everything", "wipe memory", "clear memory", "forget me")) {
            if (wipeArmed) {
                memory().wipe();
                wipeArmed = false;
                return "Memory wiped. We start clean.";
            }
            wipeArmed = true;
            return "That erases everything I know about you. Say it again to confirm.";
        }
        wipeArmed = false;

        if (has(s, "what is my name", "who am i", "do you know my name")) {
            String n = memory().recall("name");
            return n == null
                    ? "You have not told me. Say: my name is, and then your name."
                    : "You are " + n + ".";
        }
        return null;
    }

    private boolean wipeArmed = false;

    /**
     * Whether a phrase actually addressed the assistant. Matched against the
     * configured name, so renaming it to "Friday" or "Computer" works.
     */
    boolean isAddressed(String lower) {
        if (lower == null) return false;
        String n = config().assistantName();
        // word-boundary containment, so "jarvis" hits but "jarvised" does not
        int idx = lower.indexOf(n);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetter(lower.charAt(idx - 1));
            int end = idx + n.length();
            boolean rightOk = end >= lower.length() || !Character.isLetter(lower.charAt(end));
            if (leftOk && rightOk) return true;
            idx = lower.indexOf(n, idx + 1);
        }
        return false;
    }

    /** Removes the name and any lead-in around it. */
    private String stripWakeWord(String s) {
        String n = config().assistantName();
        for (String lead : new String[]{"hey " + n, "ok " + n, "okay " + n,
                "hi " + n, "hello " + n, n}) {
            if (s.equals(lead)) return "";
            if (s.startsWith(lead + " ") || s.startsWith(lead + ",")) {
                return s.substring(lead.length()).replaceFirst("^[,\\s]+", "").trim();
            }
        }
        if (s.endsWith(" " + n)) return s.substring(0, s.length() - n.length() - 1).trim();
        if (s.endsWith(", " + n)) return s.substring(0, s.length() - n.length() - 2).trim();
        return s;
    }

    Config config() {
        if (cfg == null) cfg = Config.get(this);
        return cfg;
    }

    private Config cfg;

    private String pickOne(String... options) {
        return options[(int) (Math.random() * options.length)];
    }

    /**
     * Substitutes live telemetry into a corpus response. Kept out of the
     * corpus itself so the text file stays readable and editable.
     */
    private String fill(String template) {
        if (template == null || template.indexOf('{') < 0) return template;
        String out = template;
        try {
            if (out.contains("{timeofday}")) {
                out = out.replace("{timeofday}",
                        HudView.greeting().toLowerCase(Locale.US).replace("good ", ""));
            }
            if (out.contains("{battery}")) {
                Sys.Power p = Sys.power(this);
                out = out.replace("{battery}",
                        p.level < 0 ? "unknown" : p.level + " percent");
            }
            if (out.contains("{mem}")) {
                Sys.Mem m = Sys.mem(this);
                out = out.replace("{mem}", Math.round(m.frac * 100) + " percent used");
            }
            if (out.contains("{storage}")) {
                Sys.Store st = Sys.store();
                out = out.replace("{storage}", Sys.bytes(st.free) + " free");
            }
            if (out.contains("{uptime}")) {
                out = out.replace("{uptime}", Sys.duration(Sys.uptimeMs()));
            }
            if (out.contains("{time}")) {
                out = out.replace("{time}",
                        new SimpleDateFormat("h:mm a", Locale.US).format(new Date()));
            }
            if (out.contains("{date}")) {
                out = out.replace("{date}",
                        new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date()));
            }
            if (out.contains("{hull}")) out = out.replace("{hull}", Sys.hull());
            if (out.contains("{cores}")) out = out.replace("{cores}", String.valueOf(Sys.cores()));
            if (out.contains("{link}")) out = out.replace("{link}", Sys.link(this).type);
            if (out.contains("{apps}")) {
                out = out.replace("{apps}", String.valueOf(Sys.installedPackages(this)));
            }
            if (out.contains("{name}")) {
                String n = memory() == null ? null : memory().recall("name");
                out = out.replace("{name}", n == null ? "commander" : n);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** The deterministic command layer: device actions, not conversation. */
    private String hardCommand(final String s, String raw) {

        // --- direct app launch -------------------------------------------
        String appQuery = after(s, "open ", "launch ", "start ");
        if (appQuery != null) {
            // "open messages in instagram" is a task, not an app name. Fuzzy
            // matching it against the app list picked the Messages app and
            // looked like the assistant had simply ignored the request.
            // Without the neural link there is nothing here that can carry out
            // a multi-step instruction, so say so instead of guessing.
            if (isCompoundTask(appQuery)) {
                return config().enabled()
                        ? null      // let the agent handle it
                        : "That needs the AI link. Open NEURAL and connect a model, "
                          + "then I can work inside apps rather than just launching them.";
            }
            String launched = launchAppByName(appQuery);
            if (launched != null) return "Opening " + launched + ".";
            // fall through: it may be a module name rather than an app
        }

        // --- beacon -------------------------------------------------------
        if (has(s, "torch", "flashlight", "beacon", "light")) {
            boolean wantOff = has(s, "off", "kill", "stop", "dark");
            if (torchOn == !wantOff) {
                return "Beacon already " + (torchOn ? "engaged" : "dark") + ".";
            }
            toggleTorch();
            return torchOn ? "Beacon engaged." : "Beacon dark.";
        }

        // --- telemetry questions -----------------------------------------
        if (has(s, "battery", "power", "charge", "reactor")) {
            Sys.Power p = Sys.power(this);
            String st = p.charging ? ", charging" : "";
            if (has(s, "open", "show", "panel")) openModule("power");
            return "Power cell at " + (p.level < 0 ? "unknown" : p.level + " percent") + st + ".";
        }
        if (has(s, "memory", "ram")) {
            Sys.Mem m = Sys.mem(this);
            return "Memory core: " + Sys.bytes(m.used) + " of " + Sys.bytes(m.total)
                    + " in use, " + Sys.bytes(m.avail) + " free.";
        }
        if (has(s, "storage", "disk", "space", "archive")) {
            if (has(s, "open", "show", "browse", "file")) { openModule("files"); return "Opening archive."; }
            Sys.Store st = Sys.store();
            return "Archive: " + Sys.bytes(st.free) + " free of " + Sys.bytes(st.total) + ".";
        }
        if (has(s, "wifi", "wi fi", "network", "internet", "uplink", "signal", "connection")) {
            Sys.Link l = Sys.link(this);
            if (has(s, "open", "show", "settings")) { openModule("network"); return "Opening uplink."; }
            return l.online
                    ? "Uplink online over " + l.type.toLowerCase(Locale.US)
                      + (l.ssid.equals("--") ? "" : ", network " + l.ssid) + "."
                    : "No uplink. We are dark.";
        }
        if (has(s, "time", "clock", "what time")) {
            return "It is " + new SimpleDateFormat("h:mm a", Locale.US).format(new Date()) + ".";
        }
        if (has(s, "date", "today", "what day")) {
            return "Today is "
                    + new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date()) + ".";
        }
        if (has(s, "uptime", "how long")) {
            return "Hull uptime " + Sys.duration(Sys.uptimeMs()) + ".";
        }

        // --- module vectors -----------------------------------------------
        if (has(s, "terminal", "shell", "console", "command line")) {
            safeStart(new Intent(this, Terminal.class));
            return "Shell online.";
        }
        if (has(s, "sensor", "inertial", "gyro", "accelerometer")) { openModule("sensors"); return "Inertial deck."; }
        if (has(s, "location", "where am i", "gps", "position", "navigation")) { openModule("location"); return "Navigation."; }
        if (has(s, "contact", "call log", "message", "sms", "comms")) { openModule("comms"); return "Comms array."; }
        if (has(s, "photo", "image", "picture", "gallery", "optics")) { openModule("media"); return "Optics bank."; }
        if (has(s, "package", "installed", "registry")) { openModule("packages"); return "Registry."; }
        if (has(s, "process", "task")) { openModule("proc"); return "Process table."; }
        if (has(s, "clipboard", "clip")) { openModule("clip"); return "Clipboard."; }
        if (has(s, "display", "screen", "refresh")) { openModule("display"); return "Display."; }
        if (has(s, "app", "lattice", "drawer")) { openModule("apps"); return "App lattice."; }
        if (has(s, "setting", "config")) { safeStart(new Intent(Settings.ACTION_SETTINGS)); return "Host settings."; }
        if (has(s, "system", "device", "core", "build", "hull")) { openModule("system"); return "Core diagnostics."; }

        // --- dial ----------------------------------------------------------
        String callee = after(s, "call ", "dial ", "phone ");
        if (callee != null) {
            Intent i = new Intent(Intent.ACTION_DIAL);
            safeStart(i);
            return "Opening dialer for " + callee + ".";
        }

        if (has(s, "status report", "system status", "full report", "sitrep")) {
            Sys.Power p = Sys.power(this);
            Sys.Mem m = Sys.mem(this);
            Sys.Link l = Sys.link(this);
            return "All systems nominal. Power " + (p.level < 0 ? "unknown" : p.level + " percent")
                    + ", memory " + Math.round(m.frac * 100) + " percent used, uplink "
                    + (l.online ? l.type.toLowerCase(Locale.US) : "dark") + ".";
        }

        // "open X" was explicit, so if we got here the app genuinely is not
        // installed. Say so rather than dropping into small talk.
        if (appQuery != null) return "I cannot find an app called " + appQuery + ".";

        return null;   // not a command — let the conversation engine answer
    }

    /**
     * Would the command layer do something real with this phrase? Checked
     * without side effects — this must never launch anything, because at this
     * point we have not decided the phrase was even meant for us.
     */
    private boolean probeActionable(String s) {
        if (s == null || s.length() == 0) return false;
        if (after(s, "open ", "launch ", "start ") != null) return true;
        return has(s,
                "battery", "power cell", "charge", "reactor",
                "wifi", "wi fi", "network", "uplink", "internet", "signal",
                "memory", "ram", "storage", "disk", "archive",
                "torch", "flashlight", "beacon",
                "terminal", "shell", "console",
                "sensor", "location", "where am i", "gps",
                "contact", "call log", "clipboard", "display", "screen",
                "package", "installed", "process",
                "uptime", "what time", "what day", "status report",
                "settings", "config");
    }

    /**
     * True when the phrase after "open" describes doing something inside an
     * app rather than naming one. Prepositions and conjunctions are the tell:
     * no app is called "messages in instagram".
     */
    private static boolean isCompoundTask(String q) {
        if (q == null) return false;
        String p = " " + q.trim() + " ";
        if (p.contains(" in ") || p.contains(" on ") || p.contains(" from ")
                || p.contains(" to ") || p.contains(" and ") || p.contains(" then ")
                || p.contains(" with ") || p.contains(" for ")) {
            return true;
        }
        return q.trim().split("\\s+").length > 3;
    }

    private void openModule(String id) {
        Intent i = new Intent(this, Module.class);
        i.putExtra(Module.EXTRA_ID, id);
        safeStart(i);
    }

    /** Returns the phrase following any of the given lead-ins, else null. */
    private static String after(String s, String... leads) {
        for (String lead : leads) {
            int idx = s.indexOf(lead);
            if (idx >= 0) {
                String rest = s.substring(idx + lead.length()).trim();
                if (rest.length() > 0) return rest;
            }
        }
        return null;
    }

    private static boolean has(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    /**
     * Best-effort app match: exact label first, then prefix, then substring.
     * Returns the label actually launched, or null if nothing matched.
     */
    private String launchAppByName(String query) {
        try {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> all = pm.queryIntentActivities(main, 0);

            ResolveInfo exact = null, prefix = null, contains = null;
            for (ResolveInfo ri : all) {
                String label = ri.loadLabel(pm).toString().toLowerCase(Locale.US);
                if (label.equals(query)) { exact = ri; break; }
                if (prefix == null && label.startsWith(query)) prefix = ri;
                if (contains == null && (label.contains(query) || query.contains(label))) contains = ri;
            }
            ResolveInfo pick = exact != null ? exact : prefix != null ? prefix : contains;
            if (pick == null) return null;
            if (memory() != null) memory().noteAppLaunch(pick.loadLabel(pm).toString());

            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
            return pick.loadLabel(pm).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Offer to install Space OS as the device home surface. */
    void offerTakeover() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Throwable t) {
            safeStart(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    Uri self() { return Uri.parse("package:" + getPackageName()); }
}
