package com.spaceos.os;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SEEKER :: find a transmitter by walking toward it.
 *
 * WHAT THIS CANNOT DO. A phone cannot measure the DIRECTION a radio signal
 * arrived from. That needs Angle-of-Arrival — two or more antennas measuring
 * phase difference — which exists in the Bluetooth 5.1 spec but is not exposed
 * to Android apps, and does not exist at all for Wi-Fi on consumer handsets.
 * Any app claiming to point an arrow straight at your router is guessing.
 *
 * WHAT IT DOES INSTEAD. Signal strength falls off with distance, so as you
 * move, RSSI tells you whether you are getting closer. This records the
 * strongest reading seen on each compass heading and plots them, so after
 * turning on the spot and taking a few steps you can see which way is hotter.
 * That is how you actually find a transmitter in the dark, and it works.
 */
public class Seeker {

    /** One tracked transmitter. */
    public static class Target {
        public String name = "";
        public String detail = "";
        public int rssi = -127;
        public boolean wifi;
        public long seen;

        /** Estimated metres, from the log-distance path loss model. */
        public float metres() { return distanceFor(rssi, wifi); }
    }

    /** Peak RSSI per 15-degree compass sector — the polar plot. */
    public static final int SECTORS = 24;
    private final int[] peak = new int[SECTORS];
    private final long[] peakAt = new long[SECTORS];

    public Seeker() { reset(); }

    public void reset() {
        for (int i = 0; i < SECTORS; i++) { peak[i] = -127; peakAt[i] = 0; }
    }

    /** Feeds a reading taken while facing {@code headingDeg}. */
    public void record(float headingDeg, int rssi) {
        if (rssi == 0 || rssi < -110) return;
        int s = sectorFor(headingDeg);
        if (rssi > peak[s]) {
            peak[s] = rssi;
            peakAt[s] = System.currentTimeMillis();
        }
    }

    public static int sectorFor(float headingDeg) {
        float d = headingDeg % 360f;
        if (d < 0) d += 360f;
        return (int) (d / (360f / SECTORS)) % SECTORS;
    }

    public int peakAt(int sector) { return peak[sector]; }

    public boolean hasSector(int sector) { return peak[sector] > -127; }

    /** Compass bearing of the strongest sector, or -1 if nothing recorded. */
    public float bestBearing() {
        int best = -127, bestIdx = -1;
        for (int i = 0; i < SECTORS; i++) {
            if (peak[i] > best) { best = peak[i]; bestIdx = i; }
        }
        if (bestIdx < 0) return -1f;
        return bestIdx * (360f / SECTORS) + (360f / SECTORS) / 2f;
    }

    public int bestRssi() {
        int best = -127;
        for (int i = 0; i < SECTORS; i++) if (peak[i] > best) best = peak[i];
        return best;
    }

    /** How many sectors have a reading — how much of the sweep is covered. */
    public int coverage() {
        int n = 0;
        for (int i = 0; i < SECTORS; i++) if (peak[i] > -127) n++;
        return n;
    }

    // =====================================================================
    // DISTANCE
    // =====================================================================

    /**
     * Log-distance path loss. Rough — walls, bodies and antenna orientation
     * all move it — but the trend as you walk is what matters, not the number.
     */
    public static float distanceFor(int rssi, boolean wifi) {
        if (rssi == 0 || rssi < -110) return -1f;
        float txPower = wifi ? -45f : -59f;   // typical 1m reference
        float n = wifi ? 2.6f : 2.2f;         // path loss exponent indoors
        return (float) Math.pow(10.0, (txPower - rssi) / (10.0 * n));
    }

    public static String proximity(int rssi) {
        if (rssi >= -50) return "ON TOP OF IT";
        if (rssi >= -60) return "VERY CLOSE";
        if (rssi >= -70) return "CLOSE";
        if (rssi >= -80) return "NEARBY";
        if (rssi >= -90) return "FAR";
        return "VERY FAR";
    }

    public static int colourFor(int rssi) {
        if (rssi >= -55) return Skin.ION;
        if (rssi >= -70) return Skin.SOLAR;
        return Skin.FLARE;
    }

    // =====================================================================
    // SOURCES
    // =====================================================================

    /**
     * The connected access point, if any.
     *
     * This used to bail out on getNetworkId() == -1. That check is wrong on
     * anything modern: since Android 10 the network id is redacted for
     * ordinary apps and comes back as -1 even on a perfectly good connection,
     * so a connected phone reported NO TARGET. Whether Wi-Fi is actually the
     * live transport is a question for ConnectivityManager, and RSSI is
     * readable regardless.
     */
    @SuppressWarnings("deprecation")
    public static Target wifiTarget(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();

            // Is Wi-Fi genuinely the active transport right now?
            boolean onWifi = false;
            try {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager)
                                app.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network n = cm.getActiveNetwork();
                android.net.NetworkCapabilities caps =
                        n == null ? null : cm.getNetworkCapabilities(n);
                onWifi = caps != null && caps.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_WIFI);
            } catch (Throwable ignored) {}

            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;

            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;

            int rssi = info.getRssi();
            // A meaningless RSSI means there is genuinely nothing associated.
            if (!onWifi && (rssi == 0 || rssi <= -127)) return null;

            Target t = new Target();
            t.wifi = true;
            t.rssi = rssi;
            t.seen = System.currentTimeMillis();

            String ssid = info.getSSID();
            if (ssid != null) ssid = ssid.replace("\"", "").trim();
            boolean hidden = ssid == null || ssid.length() == 0
                    || ssid.equalsIgnoreCase("<unknown ssid>");
            // Without location permission Android redacts the SSID; the signal
            // is still trackable, so name it by what we do know.
            t.name = hidden ? "CONNECTED NETWORK" : ssid;

            String bssid = info.getBSSID();
            boolean bssidHidden = bssid == null || bssid.startsWith("02:00:00");

            StringBuilder d = new StringBuilder();
            if (!bssidHidden) d.append(bssid).append("  ·  ");
            d.append(info.getLinkSpeed()).append(" Mbps");
            if (hidden) d.append("  ·  ").append(ssidBlocker(app));
            t.detail = d.toString();
            return t;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Why the network name is being withheld, in words that name the fix.
     *
     * "grant location to see the name" was true and useless: there are three
     * different reasons Android redacts an SSID and they need three different
     * actions. Since Android 12 the permission dialog offers Precise or
     * Approximate, and Approximate is the one most people tap — it grants
     * COARSE only, which is not enough for a network name, so the permission
     * reads as granted in settings while the name stays hidden forever.
     */
    public static String ssidBlocker(Context c) {
        try {
            boolean fine = c.checkSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean coarse = c.checkSelfPermission(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;

            if (!fine) {
                return coarse ? "set location to PRECISE for the name"
                              : "grant location for the name";
            }
            if (!locationServicesOn(c)) return "turn location on for the name";
            return "name withheld by the system";
        } catch (Throwable t) {
            return "grant location for the name";
        }
    }

    /** The master location toggle, separate from the app's own permission. */
    public static boolean locationServicesOn(Context c) {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (android.os.Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) { return false; }
    }

    /** True when everything needed to read an SSID is in place. */
    public static boolean canReadSsid(Context c) {
        try {
            return c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && locationServicesOn(c);
        } catch (Throwable t) { return false; }
    }

    // ---- bluetooth ---------------------------------------------------------

    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private final Map<String, Target> seen = new HashMap<String, Target>();
    private volatile boolean scanning;

    public boolean isScanning() { return scanning; }

    /** Starts a low-latency LE scan. Needs the scan permission and location on. */
    public boolean startBluetooth(Context ctx) {
        if (scanning) return true;
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) return false;
            scanner = ad.getBluetoothLeScanner();
            if (scanner == null) return false;

            callback = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult r) {
                    if (r == null || r.getDevice() == null) return;
                    Target t = new Target();
                    t.wifi = false;
                    t.rssi = r.getRssi();
                    t.seen = System.currentTimeMillis();
                    BluetoothDevice d = r.getDevice();
                    String n = null;
                    try { n = d.getName(); } catch (Throwable ignored) {}
                    t.name = (n == null || n.length() == 0) ? d.getAddress() : n;
                    t.detail = d.getAddress();
                    synchronized (seen) { seen.put(d.getAddress(), t); }
                }
                @Override public void onScanFailed(int code) {
                    Journal.warn("seeker", "LE scan failed " + code);
                    scanning = false;
                }
            };

            ScanSettings s = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, s, callback);
            scanning = true;
            Journal.info("seeker", "bluetooth sweep started");
            return true;
        } catch (SecurityException se) {
            Journal.warn("seeker", "bluetooth scan permission missing");
            return false;
        } catch (Throwable t) {
            Journal.warn("seeker", "bluetooth: " + t.getMessage());
            return false;
        }
    }

    public void stopBluetooth() {
        try { if (scanner != null && callback != null) scanner.stopScan(callback); }
        catch (Throwable ignored) {}
        scanning = false;
    }

    /** Everything heard in the last few seconds, strongest first. */
    public List<Target> bluetoothTargets() {
        List<Target> out = new ArrayList<Target>();
        long cutoff = System.currentTimeMillis() - 12000;
        synchronized (seen) {
            for (Target t : seen.values()) if (t.seen > cutoff) out.add(t);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Target>() {
            @Override public int compare(Target a, Target b) { return b.rssi - a.rssi; }
        });
        return out;
    }

    public static String describe(Target t) {
        if (t == null) return "—";
        float m = t.metres();
        return String.format(Locale.US, "%d dBm  ·  ~%.1f m  ·  %s",
                t.rssi, m < 0 ? 0f : m, proximity(t.rssi));
    }
}
