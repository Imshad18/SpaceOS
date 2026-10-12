package com.spaceos.os;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A single subsystem console. One activity serves every module. */
public class Module extends Activity {

    // Swipe in from either edge to go back, the way the system gesture does.
    private EdgeBack edgeBack;

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (edgeBack == null) edgeBack = new EdgeBack(this);
        if (edgeBack.onDispatch(e)) return true;
        return super.dispatchTouchEvent(e);
    }

    public static final String EXTRA_ID = "module";

    private String id = "system";
    private String title = "CORE";
    private final List<Row> rows = new ArrayList<Row>();
    private ListView list;
    private GridView grid;
    private Header header;
    private RowAdapter adapter;
    private GridAdapter gridAdapter;

    /** Layout preference, remembered for the life of the process. */
    private static boolean gridMode = true;

    // files module state
    private File cwd;

    // sensors module state
    private SensorManager sensorManager;
    private final Map<String, float[]> sensorValues = new HashMap<String, float[]>();
    private final List<Sensor> sensorList = new ArrayList<Sensor>();
    private SensorEventListener sensorListener;
    private final Handler refresh = new Handler(Looper.getMainLooper());

    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US);

    // =====================================================================

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Skin.init(this);
        id = getIntent().getStringExtra(EXTRA_ID);
        if (id == null) id = "system";

        // On a cold start Skin still holds the compile-time palette, so the
        // active one has to be applied before anything is measured or drawn.
        Theme.apply(Config.get(this).palette());
        Skin.dressWindow(this, Config.get(this).fullscreen());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.VOID);

        header = new Header(this);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) Skin.dp(96)));

        adapter = new RowAdapter();

        // Grid view, used by the icon-heavy modules.
        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setCacheColorHint(0);
        grid.setBackgroundColor(0x00000000);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        grid.setVerticalScrollBarEnabled(false);
        grid.setPadding((int) Skin.dp(8), (int) Skin.dp(8),
                (int) Skin.dp(8), (int) Skin.dp(8));
        grid.setClipToPadding(false);
        gridAdapter = new GridAdapter();
        grid.setAdapter(gridAdapter);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(0);
        list.setBackgroundColor(0x00000000);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p,
                                              View v, int pos, long lid) {
                Row r = shown.get(pos);
                if (r.action != null) {
                    v.performHapticFeedback(1);
                    r.action.run();
                }
            }
        });
        if (searchable()) {
            root.addView(searchRow(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        // adjustResize is inert behind a fullscreen layout; lift it ourselves.
        Keyboard.liftAboveIme(root);
        applyLayoutMode();
        load();
    }

    /** True when this module can meaningfully be shown as an icon grid. */
    private boolean gridCapable() {
        return id.equals("apps") || id.equals("packages");
    }

    private boolean gridOn() { return gridCapable() && gridMode; }

    private void applyLayoutMode() {
        boolean g = gridOn();
        grid.setVisibility(g ? View.VISIBLE : View.GONE);
        list.setVisibility(g ? View.GONE : View.VISIBLE);
    }

    /** Flip between icon grid and detail list; remembered for the process. */
    private void toggleLayout() {
        if (!gridCapable()) return;
        gridMode = !gridMode;
        applyLayoutMode();
        firstPaint = System.currentTimeMillis();
        gridAdapter.notifyDataSetChanged();
        adapter.notifyDataSetChanged();
        header.invalidate();
    }

    @Override protected void onResume() {
        super.onResume();
        header.start();
        if (id.equals("sensors")) startSensors();
    }

    @Override protected void onPause() {
        super.onPause();
        header.stop();
        stopSensors();
    }

    @Override public void onBackPressed() {
        // Inside the archive, back climbs the tree before it leaves the module.
        if (id.equals("files") && cwd != null) {
            File parent = cwd.getParentFile();
            if (parent != null && parent.canRead()) {
                cwd = parent;
                refresh();
                return;
            }
        }
        super.onBackPressed();
    }

    /** Collapse back toward the bridge whichever way the module is dismissed. */
    @Override public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fall_in, R.anim.fall_out);
    }

    // =====================================================================
    // LOADERS
    // =====================================================================

    /**
     * Scans a subsystem on a worker thread and swaps the result in when it is
     * ready. Enumerating packages or walking a contacts cursor takes long
     * enough to stall the window animation, which is exactly the moment the
     * user is looking at it.
     */
    /**
     * Rebuilds the rows in place: same scroll position, no arrival cascade.
     *
     * Every settings row used to reset firstPaint and call load(). Resetting
     * firstPaint replays the staggered row fade-in for the whole panel, and
     * load() rebuilds the adapter from scratch so the list snaps back to the
     * top. Together that meant flipping anything below the fold threw the
     * whole page at you again and lost your place — which is exactly what it
     * looked like: a full refresh on every tap.
     */
    private void refresh() {
        // Asynchronous modules have no cheap rebuild; they keep the old path.
        if (!SYNC_MODULES.contains(id)) {
            firstPaint = System.currentTimeMillis();
            load();
            return;
        }

        int first = 0, offset = 0;
        if (list != null) {
            first = list.getFirstVisiblePosition();
            View top = list.getChildAt(0);
            offset = (top == null) ? 0 : top.getTop() - list.getPaddingTop();
        }

        rows.clear();
        rows.addAll(build());
        applyFilter();                 // this notifies the adapter

        // A rebuild can drop rows that only exist while something is on, so
        // the remembered index is not necessarily still in range.
        if (list != null && first > 0 && first < shown.size()) {
            list.setSelectionFromTop(first, offset);
        }
        if (header != null) header.invalidate();
    }

    private void load() {
        if (SYNC_MODULES.contains(id)) {      // cheap enough to build inline
            rows.clear();
            rows.addAll(build());
            if (firstPaint == 0) firstPaint = System.currentTimeMillis();
            applyFilter();
            header.invalidate();
            return;
        }

        final int token = ++loadToken;
        rows.clear();
        rows.add(new Row("SCANNING SUBSYSTEM", "···")
                .sub("standby").accent(Skin.PLASMA));
        applyFilter();
        header.invalidate();

        new Thread(new Runnable() {
            @Override public void run() {
                final List<Row> built = build();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (token != loadToken) return;   // superseded by a newer scan
                        rows.clear();
                        rows.addAll(built);
                        firstPaint = System.currentTimeMillis();
                        applyFilter();
                        list.setSelectionAfterHeaderView();
                        header.invalidate();
                        // Rows are up; now fill in the icons behind them.
                        if ("apps".equals(id) || "packages".equals(id)) hydrateIcons(id);
                    }
                });
            }
        }, "spaceos-scan-" + id).start();
    }

    /** Modules light enough to build on the main thread without a stall. */
    /**
     * The whole contact registry, with numbers, each row opening its own card.
     *
     * Three things were wrong here. It stopped at 300 without saying so, which
     * is why most of the list was missing. Every row carried the sub-line
     * "contact" and no number. And tapping any of them fired a VIEW at the
     * Contacts collection URI rather than that contact's own row, so all 300
     * opened the same screen — the contacts app's front page.
     *
     * Numbers come from one extra pass over the Phone table rather than a
     * per-contact lookup: a query each for a few thousand contacts is a few
     * thousand round trips, and this runs while the panel says SCANNING.
     */
    private void loadContacts(List<Row> out, ContentResolver cr) {
        out.add(new Row("— CONTACT REGISTRY —").accent(Skin.EDGE));

        // contact id -> first number we see for it
        final java.util.HashMap<Long, String> numbers = new java.util.HashMap<Long, String>();
        try {
            Cursor p = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                            ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null, null);
            if (p != null) {
                while (p.moveToNext()) {
                    long cid = p.getLong(0);
                    String num = p.getString(1);
                    if (num != null && !numbers.containsKey(cid)) numbers.put(cid, num);
                }
                p.close();
            }
        } catch (Throwable ignored) {
            // no phone table (a tablet with no telephony) — names still list
        }

        int shown = 0, total = 0;
        try {
            Cursor c = cr.query(ContactsContract.Contacts.CONTENT_URI,
                    new String[]{
                            ContactsContract.Contacts._ID,
                            ContactsContract.Contacts.DISPLAY_NAME,
                            ContactsContract.Contacts.LOOKUP_KEY},
                    null, null,
                    ContactsContract.Contacts.DISPLAY_NAME + " ASC");
            if (c == null) {
                out.add(new Row("CONTACT REGISTRY SEALED", "DENIED")
                        .sub("grant Contacts permission").accent(Skin.FLARE));
                return;
            }
            while (c.moveToNext()) {
                final long cid = c.getLong(0);
                final String name = c.getString(1);
                final String lookup = c.getString(2);
                if (name == null || name.trim().length() == 0) continue;
                total++;
                if (shown >= MAX_CONTACTS) continue;
                shown++;

                String num = numbers.get(cid);
                out.add(new Row(name)
                        .sub(num == null ? "no number" : num)
                        .accent(num == null ? Skin.TEXT_DIM : Skin.PLASMA)
                        .on(new Runnable() { @Override public void run() {
                            openContact(cid, lookup);
                        } }));
            }
            c.close();
        } catch (Throwable t) {
            out.add(new Row("CONTACT REGISTRY SEALED", "DENIED")
                    .sub("grant Contacts permission").accent(Skin.FLARE));
            return;
        }

        if (total == 0) {
            out.add(new Row("NO CONTACTS", "EMPTY")
                    .sub("nothing in the registry").accent(Skin.TEXT_DIM));
        } else if (total > shown) {
            // Say it out loud rather than truncating in silence.
            out.add(new Row("REGISTRY TRUNCATED", (total - shown) + " MORE")
                    .sub("showing " + shown + " of " + total + " — search to narrow")
                    .accent(Skin.SOLAR));
        }
    }

    /**
     * Runs the chosen unlock route right here, unlocked, and says what came
     * back.
     *
     * Diagnosing a lock screen by locking the phone is a terrible loop: the
     * evidence is on the other side of the thing that is broken. This exercises
     * the same two calls the lock face makes and puts the answer on screen.
     */
    private void testUnlock() {
        final Config cfg = Config.get(this);
        StringBuilder sb = new StringBuilder();
        sb.append("route ").append(cfg.lockStyleLabel())
          .append(" · biometrics ").append(Biometrics.status(this));
        Journal.info("lock", "TEST: " + sb);

        // Always raise the scan if the phone will give us one, whatever route
        // is currently selected. Gating this on the route meant the test
        // silently skipped the interesting half whenever the route was set to
        // anything else — which is every phone that has not been through the
        // biometric route yet, i.e. exactly the ones being diagnosed.
        if (Build.VERSION.SDK_INT >= 28
                && Biometrics.grade(this) != Biometrics.NONE) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder b =
                        new android.hardware.biometrics.BiometricPrompt.Builder(this)
                                .setTitle("SPACE OS")
                                .setSubtitle("unlock test");
                int mask = Biometrics.allowedMask(this);
                if (Build.VERSION.SDK_INT >= 30 && mask != 0) b.setAllowedAuthenticators(mask);
                else b.setDeviceCredentialAllowed(true);
                if (Build.VERSION.SDK_INT >= 29) b.setConfirmationRequired(false);

                b.build().authenticate(new android.os.CancellationSignal(), getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(
                                    android.hardware.biometrics.BiometricPrompt
                                            .AuthenticationResult r) {
                                toast("SCAN ACCEPTED — this route works");
                                Journal.info("lock", "TEST: biometric accepted");
                            }
                            @Override public void onAuthenticationError(int c, CharSequence m) {
                                toast("SCAN FAILED: " + m);
                                Journal.warn("lock", "TEST: biometric error " + c + " " + m);
                            }
                        });
                return;
            } catch (Throwable t) {
                toast("NO PROMPT: " + t.getMessage());
                Journal.warn("lock", "TEST: prompt threw " + t);
                return;
            }
        }

        // No biometric is on offer at all. Say WHY rather than quietly running
        // the other half of the test — "where is the face unlock" deserves an
        // answer, not a dismissed keyguard.
        if (Biometrics.grade(this) == Biometrics.NONE) {
            showBiometricReport();
            return;
        }

        // The other two routes both end in requestDismissKeyguard. Unlocked,
        // it should report success immediately; if it errors here it will
        // error on the lock screen too, and that is the double swipe.
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) { toast("NO KEYGUARD SERVICE"); return; }
            if (Build.VERSION.SDK_INT < 26) { toast("ANDROID TOO OLD FOR DISMISS"); return; }
            km.requestDismissKeyguard(this,
                    new android.app.KeyguardManager.KeyguardDismissCallback() {
                        @Override public void onDismissSucceeded() {
                            toast("DISMISS OK — one screen on the lock face");
                            Journal.info("lock", "TEST: dismiss succeeded");
                        }
                        @Override public void onDismissCancelled() {
                            toast("DISMISS CANCELLED");
                            Journal.info("lock", "TEST: dismiss cancelled");
                        }
                        @Override public void onDismissError() {
                            toast("DISMISS REFUSED — this phone forces the stock lock");
                            Journal.warn("lock", "TEST: dismiss REFUSED — "
                                    + "the second swipe is this phone's own lock screen");
                        }
                    });
        } catch (Throwable t) {
            toast("DISMISS THREW: " + t.getMessage());
            Journal.warn("lock", "TEST: dismiss threw " + t);
        }
    }

    // =====================================================================
    // DEFAULT LAUNCHER
    // =====================================================================

    private static final int REQ_HOME_ROLE = 42;

    /** Whether the home button currently lands on Space OS. */
    private boolean isDefaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = getPackageManager().resolveActivity(
                    home, PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null
                    && getPackageName().equals(r.activityInfo.packageName);
        } catch (Throwable t) { return false; }
    }

    /** The launcher that currently holds it, for the subtitle. */
    private String currentHomeName() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            PackageManager pm = getPackageManager();
            ResolveInfo r = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (r == null || r.activityInfo == null) return "no launcher set";
            // The chooser itself resolves here when nothing is default yet.
            if ("android".equals(r.activityInfo.packageName)) return "not chosen yet";
            return r.loadLabel(pm).toString();
        } catch (Throwable t) { return "unknown"; }
    }

    /**
     * Asks to become the home app.
     *
     * RoleManager is the direct route on Android 10 and up: one dialog, yes or
     * no, and you land back here. Below that there is no such API — the only
     * thing an app may do is open the Home settings screen and let the user
     * pick, which is why the fallback explains itself with a toast rather than
     * dumping you on a settings page with no idea why.
     */
    private void requestHomeRole() {
        if (isDefaultHome()) { toast("ALREADY THE HOME APP"); return; }

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.app.role.RoleManager rm =
                        getSystemService(android.app.role.RoleManager.class);
                if (rm != null
                        && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(
                            android.app.role.RoleManager.ROLE_HOME), REQ_HOME_ROLE);
                    return;
                }
            } catch (Throwable t) {
                Journal.warn("home", "role request failed: " + t.getMessage());
            }
        }
        toast("PICK SPACE OS FROM THE LIST");
        openHomeSettings();
    }

    private void openHomeSettings() {
        try {
            go(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Throwable t) {
            try { go(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Throwable ignored) { toast("SETTINGS UNREACHABLE"); }
        }
    }

    private static final int REQ_WALLPAPER = 43;

    private void pickWallpaper() {
        try {
            // OPEN_DOCUMENT rather than PICK: it returns a URI we are allowed
            // to read immediately, which is all that is needed since the image
            // is copied straight away and the grant is never relied on again.
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_WALLPAPER);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Intent.ACTION_PICK,
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                startActivityForResult(i, REQ_WALLPAPER);
            } catch (Throwable t2) { toast("NO GALLERY APP"); }
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_HOME_ROLE) {
            toast(isDefaultHome() ? "SPACE OS IS NOW HOME" : "LEFT AS IT WAS");
            refresh();
            return;
        }
        if (req == REQ_WALLPAPER) {
            Uri src = data == null ? null : data.getData();
            if (result != RESULT_OK || src == null) return;
            toast("COPYING…");
            final Uri finalSrc = src;
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = Wallpaper.adopt(Module.this, finalSrc);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (ok) {
                                Config.get(Module.this).setWallpaperOn(true);
                                toast("WALLPAPER SET");
                            } else {
                                toast("COULD NOT READ THAT IMAGE");
                            }
                            refresh();
                        }
                    });
                }
            }, "spaceos-wallpaper").start();
        }
    }

    /** The full sensor report, on screen, with a way straight to the settings. */
    private void showBiometricReport() {
        final String text = Biometrics.report(this);
        Journal.info("lock", "BIOMETRIC REPORT\n" + text);
        new Sheet(this, "BIOMETRIC SENSORS")
                .badge("lock screen")
                .body(text)
                .action("ENROL", Skin.PLASMA, new Sheet.Action() {
                    @Override public void run() {
                        go(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                    }
                })
                .action("CLOSE", Skin.TEXT_DIM, null)
                .show();
    }

    /** How many contact rows the list will hold before it says so. */
    private static final int MAX_CONTACTS = 2000;

    /** Opens one contact's own card, not the contacts app's front page. */
    private void openContact(long id, String lookupKey) {
        try {
            Uri uri = (lookupKey != null)
                    ? ContactsContract.Contacts.getLookupUri(id, lookupKey)
                    : Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI,
                            String.valueOf(id));
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            go(i);
        } catch (Throwable t) {
            toast("CONTACT UNREACHABLE");
        }
    }

    private static final List<String> SYNC_MODULES = Arrays.asList(
            "sensors", "network", "power", "location", "system", "files",
            "clip", "display", "config", "notifs");

    private int loadToken = 0;
    long firstPaint = 0;

    /** Runs the loader for the active module into a fresh list. */
    private List<Row> build() {
        List<Row> target = new ArrayList<Row>();
        out = target;
        try {
            if (id.equals("apps")) loadApps();
            else if (id.equals("files")) loadFiles();
            else if (id.equals("comms")) loadComms();
            else if (id.equals("media")) loadMedia();
            else if (id.equals("sensors")) loadSensors();
            else if (id.equals("network")) loadNetwork();
            else if (id.equals("power")) loadPower();
            else if (id.equals("packages")) loadPackages();
            else if (id.equals("location")) loadLocation();
            else if (id.equals("proc")) loadProc();
            else if (id.equals("clip")) loadClip();
            else if (id.equals("display")) loadDisplay();
            else if (id.equals("brain")) loadBrain();
            else if (id.equals("journal")) loadJournal();
            else if (id.equals("notifs")) loadNotifs();
            else if (id.equals("config")) loadConfig();
            else loadSystem();
        } catch (Throwable t) {
            target.add(new Row("SUBSYSTEM FAULT", "HALT")
                    .sub(t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()))
                    .accent(Skin.FLARE));
        }
        return target;
    }

    /** Append target for the loaders; only ever touched by {@link #build()}. */
    private List<Row> out = new ArrayList<Row>();

    // ---- APPS --------------------------------------------------------------

    /** One launcher entry, with its label and icon resolved exactly once. */
    private static class App {
        String label, pkg, cls;
        android.graphics.drawable.Drawable icon;
    }

    /**
     * The app list, built once per process.
     *
     * Rebuilding it took most of a second every single time the lattice
     * opened, for two reasons. The sort called loadLabel() from inside the
     * comparator — and loadLabel goes to the package manager and parses the
     * app's resources, so a list of 150 apps did roughly 2,200 of those
     * instead of 150. Then every icon was decoded up front, on the main
     * thread, before a single row could be shown.
     *
     * Now labels are resolved once and sorted as plain strings, icons load in
     * the background after the list is already on screen, and the whole thing
     * is cached until the set of installed apps actually changes.
     */
    private static volatile List<App> appCache;
    private static volatile int appCacheSize = -1;

    private void loadApps() {
        title = "APP LATTICE";
        final PackageManager pm = getPackageManager();

        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);

        List<App> apps = appCache;
        // Cheap staleness check: an install or an uninstall changes the count.
        if (apps == null || appCacheSize != found.size()) {
            apps = new ArrayList<App>(found.size());
            for (ResolveInfo ri : found) {
                App a = new App();
                a.pkg = ri.activityInfo.packageName;
                a.cls = ri.activityInfo.name;
                try { a.label = ri.loadLabel(pm).toString(); }
                catch (Throwable t) { a.label = a.pkg; }
                apps.add(a);
            }
            java.util.Collections.sort(apps, new Comparator<App>() {
                @Override public int compare(App a, App b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
            appCache = apps;
            appCacheSize = found.size();
        }

        final List<App> list = apps;
        for (final App a : list) {
            Row r = new Row(a.label).sub(a.pkg);
            android.graphics.drawable.Drawable cached = ICONS.get(a.pkg);
            if (cached != null) r.icon(cached);
            r.on(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_LAUNCHER);
                    i.setClassName(a.pkg, a.cls);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    go(i);
                }
            });
            out.add(r);
        }

    }

    /**
     * Decodes package icons after the list is already on screen.
     *
     * Every row whose sub-line is a package name gets its icon filled in from
     * a worker thread. Decoding a few hundred launcher icons is not fast on
     * any phone, but it does not have to happen before the first row appears —
     * which is what it used to do, on the main thread, with nothing drawn.
     *
     * Batched rather than notifying per icon: several hundred full adapter
     * refreshes cost more than the decoding.
     */
    /**
     * Decoded icons, kept for the life of the process.
     *
     * Without this the background pass runs again on every visit, so the
     * lattice was quick but the icons still faded in each time. They are
     * Drawables held by package name — a few hundred of them, which is what
     * every launcher keeps in memory anyway.
     */
    private static final java.util.Map<String, android.graphics.drawable.Drawable>
            ICONS = new java.util.concurrent.ConcurrentHashMap<String,
                    android.graphics.drawable.Drawable>();

    private void hydrateIcons(final String forModule) {
        final PackageManager pm = getPackageManager();
        final List<Row> snapshot = new ArrayList<Row>(rows);
        new Thread(new Runnable() {
            @Override public void run() {
                int since = 0;
                for (Row r : snapshot) {
                    if (r.icon != null || r.sub == null || r.sub.indexOf('.') < 0) continue;
                    android.graphics.drawable.Drawable d = ICONS.get(r.sub);
                    if (d == null) {
                        try { d = pm.getApplicationIcon(r.sub); }
                        catch (Throwable ignored) { continue; }
                        if (d != null) ICONS.put(r.sub, d);
                    }
                    r.icon = d;
                    if (++since >= 16) { since = 0; postIconRefresh(forModule); }
                }
                postIconRefresh(forModule);
            }
        }, "spaceos-icons").start();
    }

    private void postIconRefresh(final String forModule) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!forModule.equals(id)) return;   // user moved on
                adapter.notifyDataSetChanged();
                gridAdapter.notifyDataSetChanged();
            }
        });
    }

    // ---- ARCHIVE (files) ---------------------------------------------------

    private void loadFiles() {
        title = "ARCHIVE";
        if (cwd == null) cwd = Environment.getExternalStorageDirectory();
        if (cwd == null) cwd = new File("/");

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            out.add(new Row("GRANT FULL ARCHIVE ACCESS", "REQUIRED")
                    .sub("Android restricts file reads until this is authorised")
                    .accent(Skin.SOLAR)
                    .on(new Runnable() {
                        @Override public void run() {
                            Intent i = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            go(i);
                        }
                    }));
        }

        final File parent = cwd.getParentFile();
        if (parent != null && parent.canRead()) {
            out.add(new Row("..", "UP").sub(parent.getAbsolutePath())
                    .accent(Skin.MAGENTA)
                    .on(new Runnable() {
                        @Override public void run() { cwd = parent; load(); }
                    }));
        }

        File[] kids = cwd.listFiles();
        if (kids == null) {
            out.add(new Row("SECTOR UNREADABLE", "DENIED")
                    .sub(cwd.getAbsolutePath()).accent(Skin.FLARE));
            return;
        }
        Arrays.sort(kids, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (final File f : kids) {
            Row r;
            if (f.isDirectory()) {
                String[] inner = f.list();
                r = new Row(f.getName() + "/")
                        .value(inner == null ? "--" : inner.length + " ITEMS")
                        .accent(Skin.PLASMA)
                        .on(new Runnable() {
                            @Override public void run() { cwd = f; load(); }
                        });
            } else {
                r = new Row(f.getName())
                        .value(Sys.bytes(f.length()))
                        .sub(STAMP.format(new Date(f.lastModified())))
                        .accent(Skin.ION)
                        .on(new Runnable() {
                            @Override public void run() { openFile(f); }
                        });
            }
            out.add(r);
        }
        if (kids.length == 0) out.add(new Row("SECTOR EMPTY", "0").accent(Skin.TEXT_DIM));
    }

    private void openFile(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            String n = f.getName().toLowerCase(Locale.US);
            String mime = "*/*";
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".gif") || n.endsWith(".webp")) mime = "image/*";
            else if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm")) mime = "video/*";
            else if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".ogg")) mime = "audio/*";
            else if (n.endsWith(".pdf")) mime = "application/pdf";
            else if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) mime = "text/plain";
            i.setDataAndType(Uri.fromFile(f), mime);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            toast("NO READER FOR THIS FORMAT");
        }
    }

    // ---- COMMS -------------------------------------------------------------

    private void loadComms() {
        title = "COMMS ARRAY";

        out.add(new Row("OPEN DIALER", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Intent.ACTION_DIAL)); } }));
        out.add(new Row("OPEN MESSAGES", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_APP_MESSAGING);
                    go(i); } }));

        ContentResolver cr = getContentResolver();
        loadContacts(out, cr);

        try {
            Cursor c = cr.query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                            CallLog.Calls.DATE, CallLog.Calls.DURATION},
                    null, null, CallLog.Calls.DATE + " DESC");
            if (c != null) {
                out.add(new Row("— TRANSMISSION LOG —").accent(Skin.EDGE));
                int n = 0;
                while (c.moveToNext() && n < 60) {
                    n++;
                    String num = c.getString(0);
                    int type = c.getInt(1);
                    long when = c.getLong(2);
                    long dur = c.getLong(3);
                    String kind = type == CallLog.Calls.INCOMING_TYPE ? "IN"
                            : type == CallLog.Calls.OUTGOING_TYPE ? "OUT"
                            : type == CallLog.Calls.MISSED_TYPE ? "MISSED" : "OTHER";
                    out.add(new Row(num == null ? "UNKNOWN" : num)
                            .value(kind)
                            .sub(STAMP.format(new Date(when)) + "  ·  " + dur + "s")
                            .accent(type == CallLog.Calls.MISSED_TYPE ? Skin.FLARE : Skin.ION));
                }
                c.close();
            }
        } catch (Throwable t) {
            out.add(new Row("TRANSMISSION LOG SEALED", "DENIED")
                    .sub("grant Call Log permission").accent(Skin.FLARE));
        }

        try {
            Cursor c = cr.query(Telephony.Sms.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                    null, null, Telephony.Sms.DATE + " DESC");
            if (c != null) {
                out.add(new Row("— MESSAGE BUFFER —").accent(Skin.EDGE));
                int n = 0;
                while (c.moveToNext() && n < 60) {
                    n++;
                    String addr = c.getString(0);
                    String body = c.getString(1);
                    long when = c.getLong(2);
                    out.add(new Row(addr == null ? "UNKNOWN" : addr)
                            .sub(body == null ? "" : body.replace('\n', ' '))
                            .value(STAMP.format(new Date(when)).substring(5))
                            .accent(Skin.MAGENTA));
                }
                c.close();
            }
        } catch (Throwable t) {
            out.add(new Row("MESSAGE BUFFER SEALED", "DENIED")
                    .sub("grant SMS permission").accent(Skin.FLARE));
        }
    }

    // ---- OPTICS (media) ----------------------------------------------------

    private void loadMedia() {
        title = "OPTICS BANK";
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED};
        try {
            Cursor c = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c == null) { out.add(new Row("BANK OFFLINE", "NULL").accent(Skin.FLARE)); return; }
            int n = 0;
            while (c.moveToNext() && n < 400) {
                n++;
                final long imgId = c.getLong(0);
                String name = c.getString(1);
                long size = c.getLong(2);
                long added = c.getLong(3) * 1000L;
                out.add(new Row(name == null ? "IMG_" + imgId : name)
                        .value(Sys.bytes(size))
                        .sub(STAMP.format(new Date(added)))
                        .accent(Skin.ION)
                        .on(new Runnable() { @Override public void run() {
                            Uri u = Uri.withAppendedPath(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    String.valueOf(imgId));
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(u, "image/*");
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            go(i); } }));
            }
            c.close();
            if (n == 0) out.add(new Row("NO IMAGES INDEXED", "0").accent(Skin.TEXT_DIM));
        } catch (Throwable t) {
            out.add(new Row("OPTICS BANK SEALED", "DENIED")
                    .sub("grant media permission").accent(Skin.FLARE));
        }
    }

    // ---- SENSORS -----------------------------------------------------------

    private void loadSensors() {
        title = "INERTIAL DECK";
        if (sensorManager == null)
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorList.isEmpty())
            sensorList.addAll(sensorManager.getSensorList(Sensor.TYPE_ALL));

        for (Sensor s : sensorList) {
            float[] v = sensorValues.get(s.getName());
            String read = "STANDBY";
            if (v != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(3, v.length); i++) {
                    if (i > 0) sb.append("  ");
                    sb.append(String.format(Locale.US, "%+.2f", v[i]));
                }
                read = sb.toString();
            }
            out.add(new Row(s.getName())
                    .value(read)
                    .sub(s.getVendor() + "  ·  range " + s.getMaximumRange()
                            + "  ·  " + s.getPower() + "mA")
                    .accent(v != null ? Skin.ION : Skin.TEXT_DIM));
        }
        if (sensorList.isEmpty())
            out.add(new Row("NO SENSORS DETECTED", "0").accent(Skin.FLARE));
    }

    private void startSensors() {
        if (sensorManager == null)
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensorListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                sensorValues.put(e.sensor.getName(), e.values.clone());
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        for (Sensor s : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            try {
                sensorManager.registerListener(sensorListener, s,
                        SensorManager.SENSOR_DELAY_UI);
            } catch (Throwable ignored) {}
        }
        refresh.post(sensorRefresh);
    }

    private final Runnable sensorRefresh = new Runnable() {
        @Override public void run() {
            if (!id.equals("sensors")) return;
            load();
            refresh.postDelayed(this, 400);
        }
    };

    private void stopSensors() {
        refresh.removeCallbacks(sensorRefresh);
        if (sensorManager != null && sensorListener != null) {
            try { sensorManager.unregisterListener(sensorListener); } catch (Throwable ignored) {}
        }
    }

    // ---- UPLINK ------------------------------------------------------------

    private void loadNetwork() {
        title = "UPLINK";
        Sys.Link l = Sys.link(this);
        out.add(new Row("TRANSPORT", l.type).accent(l.online ? Skin.ION : Skin.FLARE));
        out.add(new Row("ROUTE", l.online ? "ESTABLISHED" : "NONE")
                .accent(l.online ? Skin.ION : Skin.FLARE));
        out.add(new Row("METERED", l.metered ? "YES" : "NO")
                .accent(l.metered ? Skin.SOLAR : Skin.ION));
        out.add(new Row("LOCAL ADDRESS", l.ip));
        out.add(new Row("SSID", l.ssid));
        out.add(new Row("SIGNAL", l.rssi == 0 ? "--" : l.rssi + " dBm")
                .gauge(l.rssi == 0 ? -1 : Math.max(0f, Math.min(1f, (l.rssi + 100) / 60f))));
        out.add(new Row("LINK RATE", l.linkMbps < 0 ? "--" : l.linkMbps + " Mbps"));

        out.add(new Row("— INTERFACES —").accent(Skin.EDGE));
        try {
            for (java.net.NetworkInterface ni :
                    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                StringBuilder addrs = new StringBuilder();
                for (java.net.InetAddress a :
                        java.util.Collections.list(ni.getInetAddresses())) {
                    if (addrs.length() > 0) addrs.append("  ");
                    addrs.append(a.getHostAddress());
                }
                out.add(new Row(ni.getName())
                        .value(ni.isUp() ? "UP" : "DOWN")
                        .sub(addrs.length() == 0 ? "no address" : addrs.toString())
                        .accent(ni.isUp() ? Skin.ION : Skin.TEXT_DIM));
            }
        } catch (Throwable ignored) {}

        out.add(new Row("OPEN WIFI SETTINGS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_WIFI_SETTINGS)); } }));
        out.add(new Row("OPEN DATA SETTINGS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } }));
    }

    // ---- REACTOR (power) ---------------------------------------------------

    private void loadPower() {
        title = "REACTOR";
        Sys.Power p = Sys.power(this);
        out.add(new Row("CHARGE", p.level < 0 ? "--" : p.level + "%")
                .gauge(p.level < 0 ? -1 : p.level / 100f)
                .accent(Skin.level(p.level < 0 ? 0 : 1f - p.level / 100f)));
        out.add(new Row("STATE", p.charging ? "CHARGING" : "DISCHARGING")
                .accent(p.charging ? Skin.ION : Skin.SOLAR));
        out.add(new Row("SOURCE", p.ac ? "AC" : p.usb ? "USB" : p.wireless ? "INDUCTION" : "CELL"));
        out.add(new Row("HEALTH", p.health)
                .accent(p.health.equals("NOMINAL") ? Skin.ION : Skin.FLARE));
        out.add(new Row("CELL TEMP", Float.isNaN(p.tempC) ? "--" : p.tempC + " C")
                .accent(!Float.isNaN(p.tempC) && p.tempC > 42 ? Skin.FLARE : Skin.ION));
        out.add(new Row("POTENTIAL", Float.isNaN(p.volts) ? "--" : p.volts + " V"));
        out.add(new Row("CHEMISTRY", p.tech));
        float th = Sys.thermalC();
        out.add(new Row("HULL THERMAL", Float.isNaN(th) ? "SEALED" : Math.round(th) + " C")
                .accent(Float.isNaN(th) ? Skin.TEXT_DIM : Skin.level(th / 90f)));

        out.add(new Row("— CORE ARRAY —").accent(Skin.EDGE));
        int n = Sys.cores();
        for (int i = 0; i < n; i++) {
            int cur = Sys.coreMhz(i), max = Sys.coreMaxMhz(i);
            Row r = new Row("CORE " + String.format(Locale.US, "%02d", i));
            if (cur > 0 && max > 0) {
                r.value(cur + " / " + max + " MHz").gauge(cur / (float) max);
            } else {
                r.value("SEALED").accent(Skin.TEXT_DIM);
            }
            out.add(r);
        }

        out.add(new Row("OPEN BATTERY SETTINGS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); } }));
    }

    // ---- REGISTRY (packages) ----------------------------------------------

    private void loadPackages() {
        title = "REGISTRY";
        final PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);

        // Resolve every label once, then sort the strings.
        //
        // DisplayNameComparator calls getApplicationLabel from inside the
        // comparison, so sorting five hundred packages asked the package
        // manager for a label about nine thousand times. Doing it once each
        // and sorting on the result is the same order with two orders of
        // magnitude less work.
        final java.util.HashMap<String, String> labels =
                new java.util.HashMap<String, String>(apps.size() * 2);
        for (ApplicationInfo ai : apps) {
            String l;
            try { l = pm.getApplicationLabel(ai).toString(); }
            catch (Throwable t) { l = ai.packageName; }
            labels.put(ai.packageName, l);
        }
        java.util.Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                String la = labels.get(a.packageName), lb = labels.get(b.packageName);
                return (la == null ? "" : la).compareToIgnoreCase(lb == null ? "" : lb);
            }
        });

        int sys = 0, user = 0;
        for (ApplicationInfo ai : apps) {
            boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (isSystem) sys++; else user++;
        }
        out.add(new Row("TOTAL PACKAGES", String.valueOf(apps.size())).accent(Skin.PLASMA));
        out.add(new Row("USER", String.valueOf(user)).accent(Skin.ION));
        out.add(new Row("SYSTEM", String.valueOf(sys)).accent(Skin.SOLAR));
        out.add(new Row("— MANIFEST —").accent(Skin.EDGE));

        for (ApplicationInfo ai : apps) {
            final String pkg = ai.packageName;
            boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String label = labels.get(pkg);
            if (label == null) label = pkg;
            android.graphics.drawable.Drawable cachedIcon = ICONS.get(pkg);
            Row r = new Row(label)
                    .sub(pkg)
                    .value(isSystem ? "SYS" : "USER")
                    .accent(isSystem ? Skin.SOLAR : Skin.ION)
                    .on(new Runnable() { @Override public void run() {
                        Intent i = new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + pkg));
                        go(i); } });
            if (cachedIcon != null) r.icon(cachedIcon);   // else hydrateIcons fills it
            out.add(r);
        }
    }

    // ---- NAV (location) ----------------------------------------------------

    private void loadLocation() {
        title = "NAVIGATION";
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean gps = false, net = false;
        try { gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
        try { net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Throwable ignored) {}
        out.add(new Row("GPS PROVIDER", gps ? "ONLINE" : "OFFLINE")
                .accent(gps ? Skin.ION : Skin.FLARE));
        out.add(new Row("NETWORK PROVIDER", net ? "ONLINE" : "OFFLINE")
                .accent(net ? Skin.ION : Skin.FLARE));

        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
        } catch (SecurityException se) {
            out.add(new Row("POSITION SEALED", "DENIED")
                    .sub("grant Location permission").accent(Skin.FLARE));
        } catch (Throwable ignored) {}

        if (best != null) {
            out.add(new Row("LATITUDE", String.format(Locale.US, "%.6f", best.getLatitude())));
            out.add(new Row("LONGITUDE", String.format(Locale.US, "%.6f", best.getLongitude())));
            out.add(new Row("ALTITUDE", String.format(Locale.US, "%.1f m", best.getAltitude())));
            out.add(new Row("ACCURACY", String.format(Locale.US, "%.1f m", best.getAccuracy())));
            out.add(new Row("VELOCITY", String.format(Locale.US, "%.2f m/s", best.getSpeed())));
            out.add(new Row("BEARING", String.format(Locale.US, "%.1f deg", best.getBearing())));
            out.add(new Row("SOURCE", String.valueOf(best.getProvider()).toUpperCase(Locale.US)));
            out.add(new Row("FIX AGE", Sys.duration(System.currentTimeMillis() - best.getTime())));
            final Location f = best;
            out.add(new Row("PLOT ON MAP", "VECTOR").accent(Skin.MAGENTA)
                    .on(new Runnable() { @Override public void run() {
                        go(new Intent(Intent.ACTION_VIEW, Uri.parse(
                                "geo:" + f.getLatitude() + "," + f.getLongitude()
                                        + "?q=" + f.getLatitude() + "," + f.getLongitude()))); } }));
        } else {
            out.add(new Row("NO FIX CACHED", "--")
                    .sub("open a maps app once to seed a position").accent(Skin.SOLAR));
        }

        out.add(new Row("OPEN LOCATION SETTINGS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } }));
    }

    // ---- PROCS -------------------------------------------------------------

    /**
     * Reads the process table straight off /proc. Since Android O the kernel
     * hides other apps' entries behind hidepid, so on a modern device this
     * shows this app's own processes plus whatever the platform still exposes —
     * which is the honest answer, not a padded list.
     */
    private void loadProc() {
        title = "PROCESS TABLE";
        File procRoot = new File("/proc");
        File[] entries = procRoot.listFiles();
        int visible = 0;

        if (entries != null) {
            Arrays.sort(entries, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    try {
                        return Integer.compare(Integer.parseInt(a.getName()),
                                Integer.parseInt(b.getName()));
                    } catch (Throwable t) { return 0; }
                }
            });
            for (File f : entries) {
                String pid = f.getName();
                if (!isNumeric(pid)) continue;
                String cmdline = Sys.readLine("/proc/" + pid + "/cmdline");
                String status = Sys.readLine("/proc/" + pid + "/comm");
                if (cmdline == null && status == null) continue;
                String name = (cmdline != null && cmdline.trim().length() > 0)
                        ? cmdline.replace('\0', ' ').trim()
                        : status;
                visible++;
                out.add(new Row(name == null ? "pid " + pid : name)
                        .value("PID " + pid)
                        .sub(readProcMem(pid))
                        .accent(pid.equals(String.valueOf(android.os.Process.myPid()))
                                ? Skin.ION : Skin.PLASMA));
            }
        }

        List<Row> head = new ArrayList<Row>();
        head.add(new Row("VISIBLE PROCESSES", String.valueOf(visible)).accent(Skin.PLASMA));
        head.add(new Row("THIS PROCESS", "PID " + android.os.Process.myPid()).accent(Skin.ION));
        head.add(new Row("UID", String.valueOf(android.os.Process.myUid())));
        head.add(new Row("JVM THREADS", String.valueOf(Thread.activeCount())));
        if (visible <= 3) {
            head.add(new Row("KERNEL HIDES OTHER PIDS", "hidepid")
                    .sub("expected on Android 8+ without root — use SHELL for ps -A")
                    .accent(Skin.SOLAR));
        }
        head.add(new Row("— /PROC —").accent(Skin.EDGE));
        out.addAll(0, head);
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static String readProcMem(String pid) {
        String s = Sys.readLine("/proc/" + pid + "/statm");
        if (s == null) return "";
        try {
            String[] parts = s.trim().split("\\s+");
            long pages = Long.parseLong(parts[1]);          // resident pages
            return "rss " + Sys.bytes(pages * 4096L);
        } catch (Throwable t) { return ""; }
    }

    // ---- CLIP --------------------------------------------------------------

    private void loadClip() {
        title = "CLIPBOARD";
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                out.add(new Row("BUFFER EMPTY", "0").accent(Skin.TEXT_DIM));
                return;
            }
            android.content.ClipData clip = cm.getPrimaryClip();
            if (clip == null) {
                out.add(new Row("BUFFER EMPTY", "0").accent(Skin.TEXT_DIM));
                return;
            }
            out.add(new Row("ITEMS", String.valueOf(clip.getItemCount())).accent(Skin.PLASMA));
            for (int i = 0; i < clip.getItemCount(); i++) {
                android.content.ClipData.Item it = clip.getItemAt(i);
                CharSequence text = it.coerceToText(this);
                final String value = text == null ? "" : text.toString();
                out.add(new Row("SLOT " + String.format(Locale.US, "%02d", i))
                        .sub(value)
                        .value(value.length() + " CH")
                        .accent(Skin.ION));
            }
            out.add(new Row("CLEAR BUFFER", "PURGE").accent(Skin.FLARE)
                    .on(new Runnable() { @Override public void run() {
                        try {
                            android.content.ClipboardManager c2 =
                                    (android.content.ClipboardManager)
                                            getSystemService(Context.CLIPBOARD_SERVICE);
                            c2.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
                            toast("BUFFER PURGED");
                            refresh();
                        } catch (Throwable t) { toast("PURGE REFUSED"); }
                    } }));
        } catch (Throwable t) {
            // Android 10+ blocks background clipboard reads outright.
            out.add(new Row("BUFFER SEALED", "DENIED")
                    .sub("Android restricts clipboard reads to focused apps")
                    .accent(Skin.FLARE));
        }
    }

    // ---- DISPLAY -----------------------------------------------------------

    private void loadDisplay() {
        title = "DISPLAY";
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        out.add(new Row("RESOLUTION", dm.widthPixels + " x " + dm.heightPixels)
                .accent(Skin.PLASMA));
        out.add(new Row("DENSITY", dm.densityDpi + " dpi"));
        out.add(new Row("SCALE FACTOR", String.format(Locale.US, "%.2fx", dm.density)));
        out.add(new Row("FONT SCALE", String.format(Locale.US, "%.2fx", dm.scaledDensity)));
        out.add(new Row("LOGICAL SIZE",
                Math.round(dm.widthPixels / dm.density) + " x "
                        + Math.round(dm.heightPixels / dm.density) + " dp"));

        try {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            out.add(new Row("REFRESH RATE",
                    String.format(Locale.US, "%.1f Hz", d.getRefreshRate()))
                    .accent(d.getRefreshRate() > 80 ? Skin.ION : Skin.SOLAR));
            out.add(new Row("ROTATION", (d.getRotation() * 90) + " deg"));
            if (Build.VERSION.SDK_INT >= 23) {
                android.view.Display.Mode[] modes = d.getSupportedModes();
                out.add(new Row("— SUPPORTED MODES —").accent(Skin.EDGE));
                for (android.view.Display.Mode mo : modes) {
                    out.add(new Row(mo.getPhysicalWidth() + " x " + mo.getPhysicalHeight())
                            .value(String.format(Locale.US, "%.1f Hz", mo.getRefreshRate()))
                            .accent(mo.getModeId() == d.getMode().getModeId()
                                    ? Skin.ION : Skin.TEXT_DIM));
                }
            }
        } catch (Throwable ignored) {}

        out.add(new Row("— CONTROL —").accent(Skin.EDGE));
        out.add(new Row("DISPLAY SETTINGS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); } }));
    }

    // ---- MEMORY (the conversation engine) ---------------------------------

    /**
     * Everything Jarvis knows, laid out plainly. If it learned something you
     * did not intend it to keep, this is where you see it and wipe it.
     */
    private void loadBrain() {
        title = "MEMORY";
        Mind.build(this);                 // already on a worker thread here
        Brain b = Mind.brain();
        Memory m = Mind.memory();

        if (b == null || m == null) {
            out.add(new Row("ENGINE OFFLINE", "FAULT")
                    .sub("corpus failed to load").accent(Skin.FLARE));
            return;
        }

        out.add(new Row("— ENGINE —").accent(Skin.EDGE));
        out.add(new Row("MODEL", "RETRIEVAL + BIGRAM")
                .sub("IDF-weighted matching, not a neural network")
                .accent(Skin.PLASMA));
        out.add(new Row("CORPUS ENTRIES", String.valueOf(b.entryCount())));
        out.add(new Row("RESPONSES", String.valueOf(b.responseCount())));
        out.add(new Row("CHAIN NODES", String.valueOf(b.chainSize()))
                .sub("words the generator can continue from")
                .accent(b.chainSize() >= 25 ? Skin.ION : Skin.SOLAR));

        out.add(new Row("— LEARNED FROM YOU —").accent(Skin.EDGE));
        out.add(new Row("EXCHANGES", String.valueOf(m.exchangeCount())).accent(Skin.ION));
        out.add(new Row("DISTINCT WORDS", String.valueOf(m.vocabularySize())).accent(Skin.ION));

        List<String> top = m.topWords(12);
        if (!top.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String w : top) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(w);
            }
            out.add(new Row("MOST USED").sub(sb.toString()).accent(Skin.PLASMA));
        }

        Map<String, String> facts = m.allFacts();
        if (!facts.isEmpty()) {
            out.add(new Row("— FACTS —").accent(Skin.EDGE));
            for (Map.Entry<String, String> e : facts.entrySet()) {
                String k = e.getKey();
                out.add(new Row(k.startsWith("note:") ? "NOTE" : k.toUpperCase(Locale.US))
                        .sub(e.getValue())
                        .accent(Skin.MAGENTA));
            }
        }

        List<String[]> recent = m.recentHistory(60);
        if (!recent.isEmpty()) {
            out.add(new Row("— TRANSCRIPT —").accent(Skin.EDGE));
            for (int i = recent.size() - 1; i >= 0; i--) {
                String[] h = recent.get(i);
                out.add(new Row(h[0]).sub(h[1]).accent(Skin.TEXT_DIM));
            }
        }

        out.add(new Row("— CONTROL —").accent(Skin.EDGE));
        out.add(new Row("USER CORPUS", "/sdcard/SpaceOS/corpus.txt")
                .sub("drop a file here to add your own lines, no rebuild needed")
                .accent(Skin.SOLAR));
        out.add(new Row("WIPE MEMORY", "PURGE")
                .sub("erases transcript, facts, vocabulary — cannot be undone")
                .accent(Skin.FLARE)
                .on(new Runnable() { @Override public void run() { confirmWipe(); } }));
    }

    private boolean wipePrimed = false;

    private void confirmWipe() {
        Memory m = Mind.memory();
        if (m == null) return;
        if (!wipePrimed) {
            wipePrimed = true;
            toast("TAP AGAIN TO CONFIRM PURGE");
            return;
        }
        wipePrimed = false;
        m.wipe();
        m.flush();
        toast("MEMORY PURGED");
        refresh();
    }

    // ---- JOURNAL (flight recorder) ----------------------------------------

    /** Every AI exchange, tool call and fault, newest first. */
    private void loadJournal() {
        title = "JOURNAL";
        Journal.attach(this);

        out.add(new Row("ENTRIES", String.valueOf(Journal.size()))
                .sub("in memory · " + Sys.bytes(Journal.fileSize()) + " on disk")
                .accent(Skin.PLASMA));
        out.add(new Row("FILE").sub(Journal.path()).accent(Skin.TEXT_DIM));
        out.add(new Row("CLEAR JOURNAL", "WIPE")
                .sub("removes every recorded line")
                .accent(Skin.FLARE)
                .on(new Runnable() { @Override public void run() {
                    Journal.clear();
                    toast("JOURNAL CLEARED");
                    refresh();
                } }));

        List<Journal.Entry> entries = Journal.snapshot();
        if (entries.isEmpty()) {
            out.add(new Row("NOTHING RECORDED YET", "0")
                    .sub("talk to the model and it will show up here")
                    .accent(Skin.TEXT_DIM));
            return;
        }

        out.add(new Row("— RECORD —").accent(Skin.EDGE));
        for (Journal.Entry e : entries) {
            int colour = e.level.equals(Journal.ERROR) ? Skin.FLARE
                    : e.level.equals(Journal.WARN) ? Skin.SOLAR
                    : e.level.equals(Journal.TOOL) ? Skin.MAGENTA
                    : e.level.equals(Journal.CALL) ? Skin.ION
                    : Skin.PLASMA_D;
            out.add(new Row(e.tag)
                    .value(e.level.trim())
                    .sub(e.stamp() + "  " + e.message)
                    .accent(colour));
        }
    }

    // ---- CONFIG ------------------------------------------------------------

    /**
     * How the OS itself looks and behaves.
     *
     * These lived inside NEURAL, which is the AI console — the wrong home for
     * "hide the status bar". Appearance and surface settings belong to the OS,
     * not to the assistant.
     */
    // ---- CONFIG ------------------------------------------------------------

    /** A settings row that flips a boolean and redraws. */
    private Row toggle(String label, boolean on, String subOn, String subOff,
                       int colourOn, final Runnable flip) {
        return new Row(label, on ? "ON" : "OFF")
                .sub(on ? subOn : subOff)
                .accent(on ? colourOn : Skin.TEXT_DIM)
                .on(new Runnable() { @Override public void run() {
                    flip.run();
                    refresh();
                } });
    }

    /**
     * Everything that shapes the OS, grouped by what it actually affects.
     *
     * These were scattered across the AI console, which is the wrong home for
     * "hide the status bar" or "24-hour clock".
     */
    private void loadConfig() {
        title = "CONFIG";
        final Config cfg = Config.get(this);

        // ================= HOME =================
        // First thing in the panel, because it is the setting that decides
        // whether Space OS is your phone or just an app on it.
        out.add(new Row("— HOME —").accent(Skin.EDGE));

        boolean isHome = isDefaultHome();
        out.add(new Row("SET SPACE OS AS HOME", isHome ? "ACTIVE" : "NOT SET")
                .sub(isHome ? "the home button lands here"
                            : "currently " + currentHomeName())
                .accent(isHome ? Skin.ION : Skin.SOLAR)
                .on(new Runnable() { @Override public void run() {
                    requestHomeRole(); } }));

        if (isHome) {
            out.add(new Row("HAND HOME BACK", "CHOOSE")
                    .sub("pick a different launcher")
                    .accent(Skin.TEXT_DIM)
                    .on(new Runnable() { @Override public void run() {
                        openHomeSettings(); } }));
        }

        // ================= APPEARANCE =================
        out.add(new Row("— APPEARANCE —").accent(Skin.EDGE));

        boolean hasPaper = Wallpaper.isSet(this);
        out.add(new Row("WALLPAPER", hasPaper ? (cfg.wallpaperOn() ? "ON" : "OFF") : "NONE")
                .sub(hasPaper ? "your picture behind every layout"
                              : "choose a picture from the gallery")
                .accent(hasPaper && cfg.wallpaperOn() ? Skin.ION : Skin.TEXT_DIM)
                .on(new Runnable() { @Override public void run() {
                    pickWallpaper(); } }));

        if (hasPaper) {
            out.add(toggle("USE WALLPAPER", cfg.wallpaperOn(),
                    "drawn behind the instrument", "palette background only", Skin.ION,
                    new Runnable() { @Override public void run() {
                        cfg.setWallpaperOn(!cfg.wallpaperOn()); } }));

            out.add(toggle("ALSO ON LOCK SCREEN", cfg.wallpaperOnLock(),
                    "same picture behind the lock face", "bridge only", Skin.PLASMA,
                    new Runnable() { @Override public void run() {
                        cfg.setWallpaperOnLock(!cfg.wallpaperOnLock()); } }));

            out.add(new Row("DIM", cfg.wallpaperDim() + "%")
                    .sub("darken the picture so the readout stays legible")
                    .accent(Skin.PLASMA)
                    .on(new Runnable() { @Override public void run() {
                        int d = cfg.wallpaperDim() + 15;
                        cfg.setWallpaperDim(d > 90 ? 0 : d);
                        refresh(); } }));

            out.add(new Row("REMOVE WALLPAPER", "CLEAR")
                    .sub("delete the copy and go back to the palette")
                    .accent(Skin.FLARE)
                    .on(new Runnable() { @Override public void run() {
                        Wallpaper.remove(Module.this);
                        toast("WALLPAPER CLEARED");
                        refresh(); } }));
        }

        out.add(new Row("THEME & LAYOUT",
                Theme.LAYOUT_NAMES[cfg.layout()] + " / " + Theme.PALETTE_NAMES[cfg.palette()])
                .sub(Theme.LAYOUT_NAMES.length + " layouts · "
                        + Theme.PALETTE_NAMES.length + " palettes · "
                        + Theme.BACKDROP_NAMES.length + " backdrops")
                .accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() { finish(); } }));

        out.add(toggle("FULLSCREEN", cfg.fullscreen(),
                "Space OS owns the whole screen, cutout included",
                "Android's status bar stays visible", Skin.ION,
                new Runnable() { @Override public void run() {
                    cfg.setFullscreen(!cfg.fullscreen()); } }));

        out.add(toggle("SCANLINES", cfg.scanlines(),
                "faint CRT lines over everything",
                "clean, no overlay", Skin.PLASMA,
                new Runnable() { @Override public void run() {
                    cfg.setScanlines(!cfg.scanlines());
                    Skin.scanlinesEnabled = cfg.scanlines(); } }));

        out.add(toggle("BOOT SEQUENCE", cfg.bootSequence(),
                "POST animation on cold start",
                "straight to the bridge", Skin.PLASMA,
                new Runnable() { @Override public void run() {
                    cfg.setBootSequence(!cfg.bootSequence()); } }));

        // ================= CLOCK =================
        out.add(new Row("— CLOCK —").accent(Skin.EDGE));

        out.add(new Row("FORMAT", cfg.clock24() ? "24 HOUR" : "12 HOUR")
                .sub(cfg.clock24() ? "21:43" : "9:43 PM")
                .accent(Skin.PLASMA)
                .on(new Runnable() { @Override public void run() {
                    cfg.setClock24(!cfg.clock24());
                    refresh(); } }));

        out.add(toggle("SECONDS", cfg.clockSeconds(),
                "shown small beside the time",
                "hours and minutes only", Skin.PLASMA,
                new Runnable() { @Override public void run() {
                    cfg.setClockSeconds(!cfg.clockSeconds()); } }));

        // ================= LOCK SCREEN =================
        out.add(new Row("— LOCK SCREEN —").accent(Skin.EDGE));

        out.add(toggle("SHOW SPACE OS LOCK SCREEN", cfg.lockFace(),
                "our design over the stock one",
                "stock lock screen only", Skin.ION,
                new Runnable() { @Override public void run() {
                    cfg.setLockFace(!cfg.lockFace());
                    syncService(); } }));


        out.add(new Row("LOCK SCREEN DESIGN", cfg.lockFaceStyleName())
                .sub(Config.LOCKFACE_DESC[cfg.lockFaceStyle()])
                .accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    cfg.setLockFaceStyle(cfg.lockFaceStyle() + 1);
                    refresh(); } }));

        out.add(new Row("FACE & FINGERPRINT", Biometrics.status(this))
                .sub("tap for the full sensor report")
                .accent(Biometrics.grade(this) == Biometrics.STRONG ? Skin.ION
                        : Biometrics.grade(this) == Biometrics.WEAK ? Skin.SOLAR
                        : Skin.FLARE)
                .on(new Runnable() { @Override public void run() {
                    showBiometricReport(); } }));

        out.add(toggle("SCAN AS SCREEN WAKES", cfg.scanOnWake(),
                "no swipe needed at all",
                "wait for a swipe first", Skin.ION,
                new Runnable() { @Override public void run() {
                    cfg.setScanOnWake(!cfg.scanOnWake()); } }));

        // No app can replace the keyguard on a device that has a credential
        // set — one system screen is unavoidable. All the user gets to pick is
        // which one, and each way costs something.
        boolean secured = true;
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    getSystemService(Context.KEYGUARD_SERVICE);
            secured = km != null && Build.VERSION.SDK_INT >= 23 && km.isDeviceSecure();
        } catch (Throwable ignored) {}

        if (!secured) {
            out.add(new Row("UNLOCK", "SEAMLESS")
                    .sub("no PIN set — the face is your only lock screen")
                    .accent(Skin.ION)
                    .on(new Runnable() { @Override public void run() {
                        go(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } }));
        } else {
            String unlockSub;
            int unlockCol;
            switch (cfg.lockStyle()) {
                case Config.LOCK_BIOMETRIC:
                    unlockSub = "most phones still ask for the PIN after";
                    unlockCol = Skin.FLARE; break;
                case Config.LOCK_SYSTEM:
                    unlockSub = "stock lock screen · costs a second swipe";
                    unlockCol = Skin.SOLAR; break;
                default:
                    unlockSub = "straight to the PIN pad · one swipe";
                    unlockCol = Skin.ION;
            }
            out.add(new Row("UNLOCK WITH", cfg.lockStyleLabel())
                    .sub(unlockSub).accent(unlockCol)
                    .on(new Runnable() { @Override public void run() {
                        cfg.setLockStyle((cfg.lockStyle() + 1) % 3);
                        refresh(); } }));

            out.add(new Row("TEST UNLOCK", "RUN")
                    .sub("try the current route now and report what happens")
                    .accent(Skin.MAGENTA)
                    .on(new Runnable() { @Override public void run() {
                        testUnlock(); } }));

        }

        // ================= NOTIFICATIONS =================
        out.add(new Row("— NOTIFICATIONS —").accent(Skin.EDGE));

        out.add(new Row("NOTIFICATION BAR",
                !cfg.notifBar() ? "OFF" : Ears.connected() ? "LIVE" : "NEEDS ACCESS")
                .sub(!cfg.notifBar() ? "no strip on the bridge"
                        : Ears.connected()
                            ? "drawn from the notification feed"
                            : "tap to grant Notification access")
                .accent(!cfg.notifBar() ? Skin.TEXT_DIM
                        : Ears.connected() ? Skin.ION : Skin.SOLAR)
                .on(new Runnable() { @Override public void run() {
                    if (cfg.notifBar() && !Ears.connected()) {
                        go(new Intent(
                            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                        toast("ENABLE SPACE OS NOTIFICATIONS");
                        return;
                    }
                    cfg.setNotifBar(!cfg.notifBar());
                    refresh(); } }));

        out.add(new Row("VIEW ALERTS", "PANEL").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    Intent i = new Intent(Module.this, Module.class);
                    i.putExtra(EXTRA_ID, "notifs");
                    go(i); } }));

        // ================= VOICE =================
        out.add(new Row("— VOICE —").accent(Skin.EDGE));

        out.add(new Row("ASSISTANT NAME", Config.displayName(cfg.assistantName()))
                .sub("the wake word it answers to")
                .accent(Skin.PLASMA)
                .on(new Runnable() { @Override public void run() { renameAssistant(); } }));

        out.add(new Row("ADDRESSING", cfg.addressingLabel())
                .sub(cfg.addressingMode() == Config.ADDRESS_ALWAYS
                        ? "acts on every phrase it hears"
                        : cfg.addressingMode() == Config.ADDRESS_NAME
                            ? "the name is required every time"
                            : "works out whether you meant it; name optional")
                .accent(cfg.addressingMode() == Config.ADDRESS_ALWAYS
                        ? Skin.FLARE : Skin.ION)
                .on(new Runnable() { @Override public void run() {
                    cfg.setAddressingMode((cfg.addressingMode() + 1) % 3);
                    refresh(); } }));

        out.add(toggle("ALWAYS LISTENING", cfg.continuousListening(),
                "the mic stays open between commands",
                "one command per activation", Skin.ION,
                new Runnable() { @Override public void run() {
                    cfg.setContinuousListening(!cfg.continuousListening());
                    syncService(); } }));

        out.add(toggle("HANDS FREE", cfg.autoListen(),
                "the mic opens by itself — nothing to tap",
                "tap the core or the island to speak", Skin.SOLAR,
                new Runnable() { @Override public void run() {
                    cfg.setAutoListen(!cfg.autoListen());
                    syncService(); } }));

        out.add(new Row("FLOATING ISLAND",
                !cfg.islandEnabled() ? "OFF" : Island.isRunning() ? "FLOATING" : "NEEDS OVERLAY")
                .sub(!cfg.islandEnabled()
                        ? "a pill on top of every app — tap it and speak"
                        : Island.isRunning()
                            ? "drag it anywhere; talk from inside any app"
                            : "grant Display over other apps")
                .accent(!cfg.islandEnabled() ? Skin.TEXT_DIM
                        : Island.isRunning() ? Skin.ION : Skin.SOLAR)
                .on(new Runnable() { @Override public void run() { toggleIsland(cfg); } }));

        // ================= BACKGROUND =================
        // The permanent notification in the shade has no setting of its own,
        // because Android will not let a foreground service hide one. What it
        // needs is a place that says why it is there and a single way to stop
        // it, rather than three unrelated switches in three sections.
        out.add(new Row("— BACKGROUND —").accent(Skin.EDGE));

        boolean svc = cfg.islandEnabled() || cfg.lockFace() || cfg.gestures();
        StringBuilder why = new StringBuilder();
        if (cfg.islandEnabled()) why.append("floating pill");
        if (cfg.lockFace()) {
            if (why.length() > 0) why.append(" · ");
            why.append("lock screen");
        }
        if (cfg.gestures()) {
            if (why.length() > 0) why.append(" · ");
            why.append("touchless scroll");
        }

        out.add(new Row("BACKGROUND SERVICE", svc ? "RUNNING" : "STOPPED")
                .sub(svc ? "held open by: " + why
                         : "no permanent notification in the shade")
                .accent(svc ? Skin.SOLAR : Skin.TEXT_DIM)
                .on(new Runnable() { @Override public void run() {
                    if (!Config.get(Module.this).islandEnabled()
                            && !Config.get(Module.this).lockFace()
                            && !Config.get(Module.this).gestures()) {
                        toast("NOTHING IS RUNNING");
                        return;
                    }
                    // One tap that actually removes the notification, by
                    // switching off everything that requires it.
                    cfg.setIslandEnabled(false);
                    cfg.setLockFace(false);
                    cfg.setGestures(false);
                    syncService();
                    toast("BACKGROUND SERVICE STOPPED");
                    refresh(); } }));

        // ================= GESTURES =================
        out.add(new Row("— GESTURES —").accent(Skin.EDGE));

        out.add(new Row("SWIPE UP", Config.swipeName(cfg.swipeUp()))
                .sub("from empty space · hold for recents")
                .accent(cfg.swipeUp() == Config.SWIPE_NONE ? Skin.TEXT_DIM : Skin.ION)
                .on(new Runnable() { @Override public void run() {
                    cfg.setSwipeUp(cfg.swipeUp() + 1);
                    refresh(); } }));

        out.add(new Row("SWIPE DOWN", Config.swipeName(cfg.swipeDown()))
                .sub("drag down from empty space on the bridge")
                .accent(cfg.swipeDown() == Config.SWIPE_NONE ? Skin.TEXT_DIM : Skin.ION)
                .on(new Runnable() { @Override public void run() {
                    cfg.setSwipeDown(cfg.swipeDown() + 1);
                    refresh(); } }));

        out.add(toggle("EDGE BACK", cfg.edgeBack(),
                "swipe in from either side to go back, on any screen",
                "back only from the system gesture or key", Skin.ION,
                new Runnable() { @Override public void run() {
                    cfg.setEdgeBack(!cfg.edgeBack()); } }));

        out.add(toggle("SWIPE HINT", cfg.swipeHint(),
                "a handle above the dock showing the bridge swipes up",
                "no handle", Skin.PLASMA,
                new Runnable() { @Override public void run() {
                    cfg.setSwipeHint(!cfg.swipeHint()); } }));


        // ================= INPUT =================
        out.add(new Row("— INPUT —").accent(Skin.EDGE));

        out.add(toggle("TOUCHLESS SCROLL", cfg.gestures(),
                "wave a hand at the front camera to scroll",
                "camera motion tracking — costs battery", Skin.SOLAR,
                new Runnable() { @Override public void run() {
                    cfg.setGestures(!cfg.gestures());
                    syncService(); } }));

        out.add(toggle("HAPTICS", cfg.haptics(),
                "a tick on every press",
                "silent", Skin.PLASMA,
                new Runnable() { @Override public void run() {
                    cfg.setHaptics(!cfg.haptics()); } }));

        // ================= AGENT =================
        out.add(new Row("— AGENT —").accent(Skin.EDGE));

        out.add(new Row("NEURAL LINK", cfg.enabled() ? "ONLINE" : "OFFLINE")
                .sub(cfg.enabled() ? cfg.preset() + " · " + cfg.model()
                        : "no model connected — no network calls are made")
                .accent(cfg.enabled() ? Skin.ION : Skin.TEXT_DIM)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Module.this, Neural.class)); } }));

        out.add(new Row("STEP LIMIT", cfg.maxRoundsLabel())
                .sub("tool steps one request may take before it pauses")
                .accent(cfg.maxRounds() >= 150 ? Skin.SOLAR : Skin.PLASMA)
                .on(new Runnable() { @Override public void run() {
                    cfg.cycleMaxRounds();
                    refresh(); } }));

        out.add(toggle("KEEP AWAKE", cfg.keepAwake(),
                "long runs survive the screen sleeping",
                "runs stop when the phone dozes", Skin.ION,
                new Runnable() { @Override public void run() {
                    cfg.setKeepAwake(!cfg.keepAwake()); } }));

        out.add(toggle("SHELL ACCESS", cfg.shellAllowed(),
                "the model may run shell commands here",
                "no shell for the model", Skin.SOLAR,
                new Runnable() { @Override public void run() {
                    cfg.setShellAllowed(!cfg.shellAllowed()); } }));

        out.add(toggle("FILE ACCESS", cfg.filesAllowed(),
                "the model may list and read files",
                "no file access for the model", Skin.SOLAR,
                new Runnable() { @Override public void run() {
                    cfg.setFilesAllowed(!cfg.filesAllowed()); } }));

        out.add(new Row("PHONE CONTROL",
                !cfg.handsAllowed() ? "BLOCKED" : Hands.connected() ? "LIVE" : "NEEDS SERVICE")
                .sub(!cfg.handsAllowed()
                        ? "the model cannot see or touch other apps"
                        : Hands.connected()
                            ? "the model can read any screen and tap anything"
                            : "enable Space OS Hands in Accessibility")
                .accent(!cfg.handsAllowed() ? Skin.TEXT_DIM
                        : Hands.connected() ? Skin.FLARE : Skin.SOLAR)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Module.this, Neural.class));
                    toast("PHONE CONTROL LIVES IN NEURAL"); } }));

        out.add(toggle("PERSONAL DATA", cfg.personalAllowed(),
                "contacts and GPS are exposed to the model",
                "contacts and GPS withheld", Skin.FLARE,
                new Runnable() { @Override public void run() {
                    cfg.setPersonalAllowed(!cfg.personalAllowed()); } }));

        // ================= DIAGNOSTICS =================
        out.add(new Row("— DIAGNOSTICS —").accent(Skin.EDGE));

        out.add(new Row("JOURNAL", String.valueOf(Journal.size()))
                .sub("every AI exchange, tool call and fault")
                .accent(Skin.PLASMA)
                .on(new Runnable() { @Override public void run() {
                    Intent i = new Intent(Module.this, Module.class);
                    i.putExtra(EXTRA_ID, "journal");
                    go(i); } }));

        out.add(new Row("MEMORY", Mind.memory() == null ? "—"
                : Mind.memory().exchangeCount() + " EXCHANGES")
                .sub("what the assistant has learned about you")
                .accent(Skin.PLASMA)
                .on(new Runnable() { @Override public void run() {
                    Intent i = new Intent(Module.this, Module.class);
                    i.putExtra(EXTRA_ID, "brain");
                    go(i); } }));

        // ================= SYSTEM =================
        out.add(new Row("— SYSTEM —").accent(Skin.EDGE));

        out.add(new Row("SET AS HOME APP", "VECTOR")
                .sub("make Space OS the launcher")
                .accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_HOME_SETTINGS)); } }));

        out.add(new Row("APP PERMISSIONS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))); } }));

        out.add(new Row("ANDROID SETTINGS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_SETTINGS)); } }));

        out.add(new Row("VERSION", "1.1.0")
                .sub(Sys.hull() + "  ·  " + Sys.androidRelease())
                .accent(Skin.TEXT_DIM));
    }

    private void renameAssistant() {
        final Config cfg = Config.get(this);
        final android.widget.EditText field = new android.widget.EditText(this);
        field.setTypeface(android.graphics.Typeface.MONOSPACE);
        field.setTextColor(Skin.TEXT);
        field.setText(cfg.assistantName());
        field.setSingleLine(true);
        int p = (int) Skin.dp(18);
        field.setPadding(p, p, p, p);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Assistant name")
                .setView(field)
                .setPositiveButton("SET", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        cfg.setAssistantName(field.getText().toString());
                        toast("I will answer to "
                                + Config.displayName(cfg.assistantName()));
                        refresh();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void toggleIsland(Config cfg) {
        if (cfg.islandEnabled()) {
            cfg.setIslandEnabled(false);
            syncService();
            refresh();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            cfg.setIslandEnabled(true);
            toast("GRANT DISPLAY OVER OTHER APPS");
            try {
                go(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {}
            return;
        }
        cfg.setIslandEnabled(true);
        syncService();
        refresh();
    }

    /**
     * Starts or refreshes the background service. It hosts the island, the
     * lock-screen watch and the gesture camera, so any of those changing has
     * to reach it.
     */
    private void syncService() {
        Config cfg = Config.get(this);
        boolean needed = cfg.islandEnabled() || cfg.lockFace() || cfg.gestures();
        try {
            Intent i = new Intent(this, Island.class);
            if (needed) {
                i.setAction(Island.ACTION_SYNC);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } else {
                stopService(i);
            }
        } catch (Throwable t) {
            Journal.warn("config", "service sync failed: " + t.getMessage());
        }
    }

    // ---- NOTIFICATIONS -----------------------------------------------------

    private void loadNotifs() {
        title = "ALERTS";

        if (!Ears.connected()) {
            out.add(new Row("NOTIFICATION ACCESS NEEDED", "GRANT")
                    .sub("Space OS cannot read Android's status bar — it needs the "
                            + "notification feed instead")
                    .accent(Skin.SOLAR)
                    .on(new Runnable() { @Override public void run() {
                        try {
                            go(new Intent(
                                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                            toast("ENABLE SPACE OS NOTIFICATIONS");
                        } catch (Throwable t) { toast("CANNOT OPEN SETTINGS"); }
                    } }));
            return;
        }

        List<Ears.Note> notes = Ears.active(this);
        out.add(new Row("ACTIVE", String.valueOf(notes.size())).accent(Skin.PLASMA));
        if (!notes.isEmpty()) {
            out.add(new Row("DISMISS ALL", "CLEAR").accent(Skin.FLARE)
                    .on(new Runnable() { @Override public void run() {
                        Ears.dismissAll();
                        refresh();
                    } }));
        }

        out.add(new Row("— FEED —").accent(Skin.EDGE));
        for (final Ears.Note n : notes) {
            Row r = new Row(n.title.length() > 0 ? n.title : Ears.appName(this, n.pkg))
                    .sub(n.text.length() > 0
                            ? n.text
                            : Ears.appName(this, n.pkg))
                    .value(STAMP.format(new Date(n.when)).substring(11))
                    .accent(Skin.ION)
                    .icon(n.icon)
                    .on(new Runnable() { @Override public void run() {
                        try {
                            if (n.open != null) { n.open.send(); return; }
                            Intent l = getPackageManager()
                                    .getLaunchIntentForPackage(n.pkg);
                            if (l != null) go(l);
                        } catch (Throwable t) {
                            toast("COULD NOT OPEN");
                        }
                    } });
            out.add(r);
        }
        if (notes.isEmpty()) {
            out.add(new Row("NOTHING PENDING", "0").accent(Skin.TEXT_DIM));
        }
    }

    // ---- CORE (system) -----------------------------------------------------

    private void loadSystem() {
        title = "CORE";
        Sys.Mem m = Sys.mem(this);
        Sys.Store s = Sys.store();

        out.add(new Row("HULL", Sys.hull()).accent(Skin.PLASMA));
        out.add(new Row("PLATFORM", Sys.androidRelease()));
        out.add(new Row("BOARD", String.valueOf(Build.BOARD)));
        out.add(new Row("HARDWARE", String.valueOf(Build.HARDWARE)));
        out.add(new Row("DEVICE", String.valueOf(Build.DEVICE)));
        out.add(new Row("PRODUCT", String.valueOf(Build.PRODUCT)));
        out.add(new Row("BOOTLOADER", String.valueOf(Build.BOOTLOADER)));
        out.add(new Row("FINGERPRINT").sub(String.valueOf(Build.FINGERPRINT)).accent(Skin.TEXT_DIM));
        out.add(new Row("ABI", Arrays.toString(Build.SUPPORTED_ABIS)));
        out.add(new Row("KERNEL").sub(String.valueOf(System.getProperty("os.version")))
                .accent(Skin.TEXT_DIM));
        out.add(new Row("UPTIME", Sys.duration(Sys.uptimeMs())).accent(Skin.ION));

        out.add(new Row("— RESOURCES —").accent(Skin.EDGE));
        out.add(new Row("MEMORY", Sys.bytes(m.used) + " / " + Sys.bytes(m.total))
                .gauge(m.frac));
        out.add(new Row("MEMORY FREE", Sys.bytes(m.avail))
                .accent(m.low ? Skin.FLARE : Skin.ION));
        out.add(new Row("ARCHIVE", Sys.bytes(s.used) + " / " + Sys.bytes(s.total))
                .gauge(s.frac));
        out.add(new Row("ARCHIVE FREE", Sys.bytes(s.free)).accent(Skin.ION));
        out.add(new Row("CORE COUNT", String.valueOf(Sys.cores())));
        out.add(new Row("PACKAGES", String.valueOf(Sys.installedPackages(this))));

        out.add(new Row("— CONTROL —").accent(Skin.EDGE));
        out.add(new Row("CLIPBOARD", "VECTOR").accent(Skin.MAGENTA)
                .sub("moved here to make room for THEME on the lattice")
                .on(new Runnable() { @Override public void run() {
                    Intent i = new Intent(Module.this, Module.class);
                    i.putExtra(EXTRA_ID, "clip");
                    go(i); } }));
        out.add(new Row("SET SPACE OS AS HOME", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_HOME_SETTINGS)); } }));
        out.add(new Row("DEVELOPER OPTIONS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); } }));
        out.add(new Row("APP PERMISSIONS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))); } }));
        out.add(new Row("HOST SETTINGS", "VECTOR").accent(Skin.MAGENTA)
                .on(new Runnable() { @Override public void run() {
                    go(new Intent(Settings.ACTION_SETTINGS)); } }));
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private void go(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("NO HANDLER FOR THAT VECTOR");
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // =====================================================================
    // ADAPTER + HEADER
    // =====================================================================

    /** Grid backing: skips separator rows, which have no action and no icon. */
    private class GridAdapter extends BaseAdapter {
        private final List<Row> cells = new ArrayList<Row>();

        void rebuild() {
            cells.clear();
            for (Row r : shown) if (r.action != null) cells.add(r);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return cells.size(); }
        @Override public Object getItem(int i) { return cells.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            AppTileView v = convert instanceof AppTileView
                    ? (AppTileView) convert : new AppTileView(Module.this);
            v.bind(cells.get(pos));

            long since = System.currentTimeMillis() - firstPaint;
            if (since < 300 && pos < 20) {
                v.setAlpha(0f);
                v.setScaleX(0.85f);
                v.setScaleY(0.85f);
                v.animate().cancel();
                v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(pos * 16L)
                        .setDuration(240)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else {
                v.animate().cancel();
                v.setAlpha(1f); v.setScaleX(1f); v.setScaleY(1f);
            }
            return v;
        }
    }

    // =====================================================================
    // SEARCH
    // =====================================================================

    /** Rows actually on screen — the filter result, or everything. */
    private final List<Row> shown = new ArrayList<Row>();
    private String query = "";

    /** Panels where a filter earns its keyboard. */
    private boolean searchable() {
        return id.equals("comms") || id.equals("apps") || id.equals("packages")
                || id.equals("media") || id.equals("files") || id.equals("journal")
                || id.equals("sensors") || id.equals("proc");
    }

    /**
     * Filters on title and subtitle. Separator rows are dropped while a query
     * is active — a section heading with nothing under it is just noise.
     */
    private void applyFilter() {
        shown.clear();
        if (query.length() == 0) {
            shown.addAll(rows);
        } else {
            String q = query.toLowerCase(Locale.US);
            for (Row r : rows) {
                if (r.action == null && r.title.startsWith("—")) continue;
                String hay = (r.title + " " + r.sub + " " + r.value).toLowerCase(Locale.US);
                if (hay.contains(q)) shown.add(r);
            }
        }
        adapter.notifyDataSetChanged();
        gridAdapter.rebuild();
        if (header != null) header.invalidate();
    }

    private View searchRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Skin.VOID_2);
        row.setPadding((int) Skin.dp(14), (int) Skin.dp(6),
                (int) Skin.dp(10), (int) Skin.dp(6));

        TextView glyph = new TextView(this);
        glyph.setText("⌕");
        glyph.setTypeface(android.graphics.Typeface.MONOSPACE);
        glyph.setTextSize(16f);
        glyph.setTextColor(Skin.PLASMA);
        glyph.setPadding(0, 0, (int) Skin.dp(9), 0);
        row.addView(glyph);

        final EditText field = new EditText(this);
        field.setTypeface(android.graphics.Typeface.MONOSPACE);
        field.setTextSize(12.5f);
        field.setTextColor(Skin.TEXT);
        field.setHintTextColor(Skin.TEXT_DIM);
        field.setHint("filter " + title.toLowerCase(Locale.US));
        field.setBackground(null);
        field.setSingleLine(true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s == null ? "" : s.toString().trim();
                applyFilter();
            }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });
        row.addView(field, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(this);
        clear.setText("X");
        clear.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
        clear.setTextSize(12f);
        clear.setTextColor(Skin.MAGENTA);
        clear.setPadding((int) Skin.dp(10), (int) Skin.dp(6),
                (int) Skin.dp(10), (int) Skin.dp(6));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { field.setText(""); }
        });
        row.addView(clear);
        return row;
    }

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean isEnabled(int i) { return shown.get(i).action != null; }
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            RowView v = convert instanceof RowView ? (RowView) convert : new RowView(Module.this);
            v.bind(shown.get(pos), pos);

            // Rows cascade in behind the window animation on the first paint
            // only; scrolling afterwards stays instant.
            long since = System.currentTimeMillis() - firstPaint;
            if (since < 260 && pos < 14) {
                v.setAlpha(0f);
                v.setTranslationY(Skin.dp(22));
                v.animate().cancel();
                v.animate().alpha(1f).translationY(0f)
                        .setStartDelay(pos * 22L)
                        .setDuration(220)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else {
                v.animate().cancel();
                v.setAlpha(1f);
                v.setTranslationY(0f);
            }
            return v;
        }
    }

    /** Animated module header — keeps the bridge language on every screen. */
    private class Header extends View {
        private float phase;
        private boolean running;
        private final Handler h = new Handler(Looper.getMainLooper());

        Header(Context c) { super(c); }

        void start() { if (!running) { running = true; h.post(anim); } }
        void stop() { running = false; h.removeCallbacks(anim); }

        private final Runnable anim = new Runnable() {
            @Override public void run() {
                if (!running) return;
                phase += 1f;
                invalidate();
                h.postDelayed(this, 60);
            }
        };

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            c.drawColor(Skin.VOID);
            Skin.grid(c, w, hh, 26);
            Skin.stars(c, w, hh, phase, 24);

            float pad = Skin.dp(14);
            float top = Skin.dp(30);

            Paint back = Skin.text(9, Skin.MAGENTA, true);
            back.setLetterSpacing(0.2f);
            c.drawText("< BRIDGE", pad, top + Skin.dp(2), back);

            Paint t = Skin.text(20, Skin.PLASMA, true);
            t.setLetterSpacing(0.2f);
            c.drawText(title, pad, top + Skin.dp(27), t);

            Paint sub = Skin.text(8, Skin.TEXT_DIM, false);
            sub.setLetterSpacing(0.16f);
            String meta = rows.size() + " RECORDS";
            if (id.equals("files") && cwd != null) meta = cwd.getAbsolutePath();
            c.drawText(meta, pad, top + Skin.dp(40), sub);

            Skin.textRight(c, "SPACE OS", w - pad, top + Skin.dp(2),
                    Skin.text(8, Skin.TEXT_DIM, false));

            // layout toggle, only where a grid makes sense
            if (gridCapable()) {
                String label = gridOn() ? "▤ LIST" : "▦ GRID";
                Paint tp = Skin.text(9.5f, Skin.PLASMA, true);
                tp.setLetterSpacing(0.14f);
                float tw = tp.measureText(label);
                toggleRect.set(w - pad - tw - Skin.dp(14),
                        top + Skin.dp(14),
                        w - pad + Skin.dp(4),
                        top + Skin.dp(36));
                Skin.panel(c, toggleRect, Skin.EDGE, Skin.alpha(Skin.PLASMA, 0.10f), 5);
                Skin.textRight(c, label, w - pad - Skin.dp(5),
                        top + Skin.dp(29), tp);
            } else {
                toggleRect.setEmpty();
            }

            // live rule
            float ry = hh - Skin.dp(6);
            c.drawLine(pad, ry, w - pad, ry, Skin.stroke(1f, Skin.EDGE));
            float blip = pad + (phase * 6f % (w - pad * 2));
            c.drawRect(blip, ry - Skin.dp(1.5f), blip + Skin.dp(30), ry + Skin.dp(1.5f),
                    Skin.fill(Skin.PLASMA));

            Skin.brackets(c, new RectF(pad * 0.5f, top - Skin.dp(12),
                    w - pad * 0.5f, hh - Skin.dp(12)), Skin.EDGE, 10, 1f);

            Skin.scanlines(c, w, hh, phase);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;

            if (!toggleRect.isEmpty() && toggleRect.contains(e.getX(), e.getY())) {
                performHapticFeedback(1);
                toggleLayout();
                return true;
            }
            if (e.getY() < Skin.dp(48)) {
                performHapticFeedback(1);
                finish();
            }
            return true;
        }

        private final RectF toggleRect = new RectF();
    }
}
