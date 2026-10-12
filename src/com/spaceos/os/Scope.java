package com.spaceos.os;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.List;

/**
 * SCOPE :: the seeker screen.
 *
 * Cycles between the connected Wi-Fi access point and whatever Bluetooth
 * devices are currently audible, feeding whichever is selected into the
 * compass plot.
 */
public class Scope extends Activity implements SeekerView.Host {

    // Swipe in from either edge to go back, the way the system gesture does.
    private EdgeBack edgeBack;

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (edgeBack == null) edgeBack = new EdgeBack(this);
        if (edgeBack.onDispatch(e)) return true;
        return super.dispatchTouchEvent(e);
    }

    private SeekerView view;
    private final Seeker seeker = new Seeker();
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** -1 is the Wi-Fi AP; 0.. index into the Bluetooth list. */
    private int targetIndex = -1;
    private Seeker.Target cached;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Skin.init(this);
        Theme.apply(Config.get(this).palette());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Skin.dressWindow(this, Config.get(this).fullscreen());

        view = new SeekerView(this);
        view.setHost(this);
        setContentView(view);

        requestScanPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        view.start();
        seeker.startBluetooth(this);
        ui.post(refresh);
        offerSsidFix();
    }

    private boolean offeredFix;

    /**
     * Gets the network name un-redacted, or explains exactly what is stopping
     * it.
     *
     * Asking for ACCESS_FINE_LOCATION is not the same as getting it. Since
     * Android 12 the permission dialog offers Precise or Approximate, and
     * Approximate grants COARSE only — enough for the permission to read as
     * granted, not enough for a network name. Worse, once that choice is made
     * requestPermissions stops showing the dialog at all, so asking again in
     * onCreate does nothing and the name is hidden forever with no way back
     * from inside the app. The only route out is App Info, and the user has to
     * be told that; there is nothing to discover on this screen otherwise.
     */
    private void offerSsidFix() {
        if (offeredFix) return;
        if (Seeker.canReadSsid(this)) return;
        offeredFix = true;

        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!fine) {
            // A rationale being offered means the system will still show the
            // dialog, so ask properly rather than sending them to settings.
            if (shouldShowRequestPermissionRationale(
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION}, 31);
                return;
            }
            ask("NETWORK NAME HIDDEN",
                    "Android only reveals Wi-Fi names to apps with PRECISE "
                            + "location. Space OS has Approximate, which is not "
                            + "enough, and the system will not ask again.\n\n"
                            + "Permissions › Location › tap USE PRECISE LOCATION.",
                    "APP INFO", new Runnable() { @Override public void run() {
                        openAppInfo(); } });
            return;
        }

        if (!Seeker.locationServicesOn(this)) {
            ask("NETWORK NAME HIDDEN",
                    "Location is switched off on this phone. The permission is "
                            + "granted, but with the master toggle off Android "
                            + "still withholds Wi-Fi names.",
                    "LOCATION SETTINGS", new Runnable() { @Override public void run() {
                        try {
                            startActivity(new Intent(
                                android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        } catch (Throwable ignored) {}
                    } });
        }
    }

    private void ask(String title, String body, String action, final Runnable go) {
        new Sheet(this, title)
                .badge("seeker")
                .body(body)
                .action(action, Skin.PLASMA, new Sheet.Action() {
                    @Override public void run() { go.run(); }
                })
                .action("NOT NOW", Skin.TEXT_DIM, null)
                .show();
    }

    private void openAppInfo() {
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    @Override protected void onPause() {
        super.onPause();
        view.stop();
        seeker.stopBluetooth();
        ui.removeCallbacks(refresh);
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fall_in, R.anim.fall_out);
    }

    /**
     * Bluetooth scanning needs location on Android — the OS treats nearby
     * radios as location data, which is fair, because they are.
     */
    private void requestScanPermissions() {
        java.util.List<String> ask = new java.util.ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ask.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (checkSelfPermission("android.permission.BLUETOOTH_SCAN")
                        != PackageManager.PERMISSION_GRANTED) {
                    ask.add("android.permission.BLUETOOTH_SCAN");
                }
                if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                        != PackageManager.PERMISSION_GRANTED) {
                    ask.add("android.permission.BLUETOOTH_CONNECT");
                }
            } catch (Throwable ignored) {}
        }
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 31);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        seeker.startBluetooth(this);
        // A fresh grant can change what the name resolves to; allow one more
        // prompt if precise was still declined.
        offeredFix = false;
        offerSsidFix();
    }

    /** Re-reads the live RSSI a couple of times a second. */
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            cached = resolveTarget();
            ui.postDelayed(this, 400);
        }
    };

    private Seeker.Target resolveTarget() {
        if (targetIndex < 0) return Seeker.wifiTarget(Scope.this);
        List<Seeker.Target> bt = seeker.bluetoothTargets();
        if (bt.isEmpty()) return null;
        return bt.get(Math.min(targetIndex, bt.size() - 1));
    }

    // ---- SeekerView.Host ----------------------------------------------------

    @Override public Seeker.Target current() { return cached; }

    @Override public Seeker seeker() { return seeker; }

    @Override public void onReset() {
        seeker.reset();
        Toast.makeText(this, "SWEEP CLEARED", Toast.LENGTH_SHORT).show();
    }

    @Override public void onSwitchTarget() {
        List<Seeker.Target> bt = seeker.bluetoothTargets();
        // wifi -> each bluetooth device -> back to wifi
        if (targetIndex < 0) {
            if (bt.isEmpty()) {
                Toast.makeText(this, seeker.isScanning()
                        ? "NO BLUETOOTH DEVICES HEARD YET"
                        : "BLUETOOTH OFF OR PERMISSION MISSING",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            targetIndex = 0;
        } else if (targetIndex + 1 < bt.size()) {
            targetIndex++;
        } else {
            targetIndex = -1;
        }
        seeker.reset();
        cached = resolveTarget();
        Toast.makeText(this, cached == null ? "NO TARGET" : cached.name,
                Toast.LENGTH_SHORT).show();
    }
}
