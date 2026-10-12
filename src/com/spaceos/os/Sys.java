package com.spaceos.os;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every reading Space OS can take off the host device. Each probe fails soft:
 * a locked-down sensor returns a sentinel rather than throwing, so the HUD
 * keeps running on whatever the platform actually allows.
 */
public final class Sys {

    private Sys() {}

    // =====================================================================
    // POWER CELL
    // =====================================================================

    public static class Power {
        public int level = -1;          // percent
        public boolean charging;
        public boolean usb, ac, wireless;
        public float tempC = Float.NaN;
        public float volts = Float.NaN;
        public String health = "UNKNOWN";
        public String tech = "--";
    }

    public static Power power(Context ctx) {
        Power p = new Power();
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return p;
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (lvl >= 0 && scale > 0) p.level = Math.round(lvl * 100f / scale);

            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            p.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;

            int plug = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            p.usb = plug == BatteryManager.BATTERY_PLUGGED_USB;
            p.ac = plug == BatteryManager.BATTERY_PLUGGED_AC;
            p.wireless = plug == BatteryManager.BATTERY_PLUGGED_WIRELESS;

            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) p.tempC = t / 10f;
            int v = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) p.volts = v / 1000f;

            p.tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (p.tech == null) p.tech = "--";

            switch (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                case BatteryManager.BATTERY_HEALTH_GOOD: p.health = "NOMINAL"; break;
                case BatteryManager.BATTERY_HEALTH_OVERHEAT: p.health = "OVERHEAT"; break;
                case BatteryManager.BATTERY_HEALTH_DEAD: p.health = "DEAD CELL"; break;
                case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: p.health = "OVERVOLT"; break;
                case BatteryManager.BATTERY_HEALTH_COLD: p.health = "COLD"; break;
                default: p.health = "UNKNOWN";
            }
        } catch (Throwable ignored) {}
        return p;
    }

    // =====================================================================
    // MEMORY CORE
    // =====================================================================

    public static class Mem {
        public long total, avail, used;
        public boolean low;
        public float frac;              // 0..1 used
    }

    public static Mem mem(Context ctx) {
        Mem m = new Mem();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            m.total = mi.totalMem;
            m.avail = mi.availMem;
            m.used = m.total - m.avail;
            m.low = mi.lowMemory;
            if (m.total > 0) m.frac = m.used / (float) m.total;
        } catch (Throwable ignored) {}
        return m;
    }

    // =====================================================================
    // ARCHIVE VOLUME
    // =====================================================================

    public static class Store {
        public long total, free, used;
        public float frac;
    }

    public static Store store() {
        Store s = new Store();
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root == null || !root.exists()) root = Environment.getDataDirectory();
            StatFs fs = new StatFs(root.getAbsolutePath());
            long bs = fs.getBlockSizeLong();
            s.total = fs.getBlockCountLong() * bs;
            s.free = fs.getAvailableBlocksLong() * bs;
            s.used = s.total - s.free;
            if (s.total > 0) s.frac = s.used / (float) s.total;
        } catch (Throwable ignored) {}
        return s;
    }

    // =====================================================================
    // CORE ARRAY  (CPU)
    // =====================================================================

    public static int cores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** Current clock of a core in MHz, or -1 when the node is not readable. */
    public static int coreMhz(int core) {
        String v = readLine("/sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_cur_freq");
        if (v == null) return -1;
        try { return (int) (Long.parseLong(v.trim()) / 1000L); } catch (Throwable t) { return -1; }
    }

    public static int coreMaxMhz(int core) {
        String v = readLine("/sys/devices/system/cpu/cpu" + core + "/cpufreq/cpuinfo_max_freq");
        if (v == null) return -1;
        try { return (int) (Long.parseLong(v.trim()) / 1000L); } catch (Throwable t) { return -1; }
    }

    /**
     * Process load from the JVM's own thread pressure plus core clocks. Real
     * /proc/stat is walled off from apps since Android O, so this is a modelled
     * figure rather than a kernel one — honest name, honest number.
     */
    public static float loadEstimate() {
        int n = cores();
        float sum = 0; int seen = 0;
        for (int i = 0; i < n; i++) {
            int cur = coreMhz(i), max = coreMaxMhz(i);
            if (cur > 0 && max > 0) { sum += cur / (float) max; seen++; }
        }
        if (seen > 0) return Math.min(1f, sum / seen);
        // fallback: thread count as a rough proxy
        return Math.min(1f, Thread.activeCount() / 64f);
    }

    /** Any thermal zone we are allowed to read, in Celsius. */
    public static float thermalC() {
        for (int i = 0; i < 12; i++) {
            String v = readLine("/sys/class/thermal/thermal_zone" + i + "/temp");
            if (v == null) continue;
            try {
                float t = Float.parseFloat(v.trim());
                if (t > 1000) t /= 1000f;
                if (t > 10 && t < 150) return t;
            } catch (Throwable ignored) {}
        }
        return Float.NaN;
    }

    // =====================================================================
    // COMMS LINK
    // =====================================================================

    public static class Link {
        public String type = "OFFLINE";
        public String ssid = "--";
        public int rssi = 0;
        public int linkMbps = -1;
        public String ip = "--";
        public boolean online;
        public boolean metered;
    }

    @SuppressWarnings("deprecation")
    public static Link link(Context ctx) {
        Link l = new Link();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm.getActiveNetwork();
            if (net != null) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(net);
                if (nc != null) {
                    l.online = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    l.metered = !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) l.type = "WIFI";
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) l.type = "CELLULAR";
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) l.type = "ETHERNET";
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) l.type = "VPN";
                    else l.type = "LINKED";
                }
            }
        } catch (Throwable ignored) {}

        try {
            WifiManager wm = (WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    String s = wi.getSSID();
                    if (s != null) l.ssid = s.replace("\"", "");
                    l.rssi = wi.getRssi();
                    l.linkMbps = wi.getLinkSpeed();
                }
            }
        } catch (Throwable ignored) {}

        l.ip = ipv4();
        return l;
    }

    public static String ipv4() {
        try {
            List<NetworkInterface> ifs =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifs) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return "--";
    }

    // =====================================================================
    // HULL / IDENTITY
    // =====================================================================

    public static String hull() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String d = Build.MODEL == null ? "" : Build.MODEL;
        if (d.toLowerCase(Locale.US).startsWith(m.toLowerCase(Locale.US))) return d.toUpperCase(Locale.US);
        return (m + " " + d).trim().toUpperCase(Locale.US);
    }

    public static String androidRelease() {
        return "ANDROID " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT;
    }

    public static long uptimeMs() { return SystemClock.elapsedRealtime(); }

    public static int installedPackages(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getInstalledApplications(0).size();
        } catch (Throwable t) { return -1; }
    }

    // =====================================================================
    // FORMATTERS
    // =====================================================================

    public static String bytes(long b) {
        if (b <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0; double v = b;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    public static String duration(long ms) {
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long m = s / 60;    s %= 60;
        if (d > 0) return String.format(Locale.US, "%dd %02d:%02d:%02d", d, h, m, s);
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    public static String pct(float f) {
        if (Float.isNaN(f)) return "--%";
        return Math.round(f * 100) + "%";
    }

    /** Reads the first line of a sysfs/proc node, null when denied. */
    public static String readLine(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path), 128);
            return r.readLine();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }
}
