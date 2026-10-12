package com.spaceos.os;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The agent's hands.
 *
 * Every tool is gated by {@link Config}: shell, file reads and personal data
 * are separately switchable, and personal data is OFF by default. A tool that
 * is switched off reports that plainly to the model rather than failing
 * silently, so the model can tell the user why it cannot help.
 *
 * Nothing here can exceed the app's own permissions. The agent is bounded by
 * the same sandbox as the rest of Space OS.
 */
public class Tools {

    /** Human-readable catalogue injected into the system prompt. */
    public static String catalogue(Config cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("telemetry {} — battery, memory, storage, cpu, thermal, uptime\n");
        sb.append("device {} — model, android version, board, abi, screen\n");
        sb.append("apps {} — list installed launchable apps\n");
        sb.append("launch {\"name\":\"...\"} — open an app by name\n");
        sb.append("sensors {} — list sensors and current readings\n");
        sb.append("network {} — connectivity, wifi, ip address\n");
        sb.append("torch {\"on\":true} — control the flashlight\n");
        sb.append("settings {\"which\":\"wifi|display|battery|home|apps|location\"} — open a settings screen\n");
        sb.append("open_url {\"url\":\"...\"} — open a link in the browser\n");
        sb.append("wait {\"ms\":900} — let a screen finish loading before reading it\n");
        if (cfg.filesAllowed()) {
            sb.append("ls {\"path\":\"/sdcard\"} — list a directory\n");
            sb.append("read {\"path\":\"...\"} — read a text file (max 8KB)\n");
        }
        if (cfg.shellAllowed()) {
            sb.append("shell {\"cmd\":\"...\"} — run an unprivileged Android shell command\n");
        }
        if (cfg.personalAllowed()) {
            sb.append("contacts {\"query\":\"...\"} — search contacts by name\n");
            sb.append("location {} — last known GPS position\n");
        }
        // Phone control state is ALWAYS reported, including when it is off.
        // Staying silent about it meant the model launched an app, found it
        // had no way to touch anything, and simply stopped — with no idea why
        // and nothing useful to tell the user.
        if (cfg.handsAllowed() && Hands.connected()) {
            sb.append("\nPHONE CONTROL — you can operate any app on screen:\n");
            sb.append("screen {} — read the current screen as an indexed element list\n");
            sb.append("tap {\"index\":3} or {\"text\":\"Send\"} or {\"x\":540,\"y\":1200}\n");
            sb.append("type {\"text\":\"...\"} — type into the focused input\n");
            sb.append("swipe {\"x1\":..,\"y1\":..,\"x2\":..,\"y2\":..,\"ms\":300}\n");
            sb.append("scroll {\"down\":true} — scroll the current list\n");
            sb.append("nav {\"action\":\"back|home|recents|notifications|quicksettings\"}\n");
            sb.append("\nAlways call screen first, then act on what you actually see. ");
            sb.append("After acting, call screen again to confirm it worked.\n");
        } else if (!cfg.handsAllowed()) {
            sb.append("\nPHONE CONTROL IS SWITCHED OFF. You can launch apps but you ")
              .append("cannot see or touch anything inside them. If the user asks for ")
              .append("something that needs it — messaging someone, tapping through an ")
              .append("app — launch what you can, then say plainly: \"turn on PHONE ")
              .append("CONTROL in NEURAL setup and I can work inside apps.\" ")
              .append("Do not pretend you tried.\n");
        } else {
            sb.append("\nPHONE CONTROL is enabled but the accessibility service is not ")
              .append("running. Tell the user to enable \"Space OS Hands\" in ")
              .append("Settings > Accessibility.\n");
        }
        return sb.toString();
    }

    /**
     * Runs one tool and returns a plain-text result for the model to read.
     * Never throws: failures come back as text the model can reason about.
     */
    public static String run(Context ctx, Config cfg, String name, JSONObject args) {
        try {
            if (name.equals("telemetry")) return telemetry(ctx);
            if (name.equals("device")) return device(ctx);
            if (name.equals("apps")) return apps(ctx);
            if (name.equals("launch")) return launch(ctx, args.optString("name", ""));
            if (name.equals("sensors")) return sensors(ctx);
            if (name.equals("network")) return network(ctx);
            if (name.equals("torch")) return torch(ctx, args.optBoolean("on", true));
            if (name.equals("settings")) return settings(ctx, args.optString("which", ""));
            if (name.equals("open_url")) return openUrl(ctx, args.optString("url", ""));
            if (name.equals("wait")) {
                int ms = Math.max(100, Math.min(6000, args.optInt("ms", 900)));
                try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
                return "WAITED " + ms + "ms";
            }

            if (name.equals("ls")) {
                if (!cfg.filesAllowed()) return "DENIED: file access is switched off in setup.";
                return ls(args.optString("path", "/sdcard"));
            }
            if (name.equals("read")) {
                if (!cfg.filesAllowed()) return "DENIED: file access is switched off in setup.";
                return read(args.optString("path", ""));
            }
            if (name.equals("shell")) {
                if (!cfg.shellAllowed()) return "DENIED: shell access is switched off in setup.";
                return Shell.once(args.optString("cmd", ""), 12000);
            }
            if (name.equals("contacts")) {
                if (!cfg.personalAllowed()) return "DENIED: personal data access is off.";
                return contacts(ctx, args.optString("query", ""));
            }
            if (name.equals("location")) {
                if (!cfg.personalAllowed()) return "DENIED: personal data access is off.";
                return location(ctx);
            }
            // ---- phone control ------------------------------------------
            if (name.equals("screen") || name.equals("tap") || name.equals("type")
                    || name.equals("swipe") || name.equals("scroll") || name.equals("nav")) {
                if (!cfg.handsAllowed()) {
                    return "DENIED: phone control is switched off in NEURAL setup.";
                }
                Hands h = Hands.get();
                if (h == null) {
                    return "UNAVAILABLE: the accessibility service is not running. "
                            + "The user must enable \"Space OS Hands\" in "
                            + "Settings > Accessibility.";
                }
                if (name.equals("screen")) return h.readScreen();
                if (name.equals("type")) return h.typeText(args.optString("text", ""));
                if (name.equals("scroll")) return h.scroll(args.optBoolean("down", true));
                if (name.equals("nav")) return h.global(args.optString("action", ""));
                if (name.equals("swipe")) {
                    return h.swipe(args.optInt("x1"), args.optInt("y1"),
                            args.optInt("x2"), args.optInt("y2"),
                            args.optInt("ms", 300));
                }
                // tap: by index, by visible text, or by raw coordinate
                if (args.has("index")) return h.tapIndex(args.optInt("index", -1));
                if (args.has("text")) return h.tapText(args.optString("text", ""));
                if (args.has("x") && args.has("y")) {
                    return h.tapAt(args.optInt("x"), args.optInt("y"));
                }
                return "ERROR: tap needs index, text, or x and y";
            }

            return "UNKNOWN TOOL: " + name;
        } catch (Throwable t) {
            return "ERROR: " + t.getClass().getSimpleName() + " " + String.valueOf(t.getMessage());
        }
    }

    // =====================================================================

    private static String telemetry(Context ctx) {
        Sys.Power p = Sys.power(ctx);
        Sys.Mem m = Sys.mem(ctx);
        Sys.Store s = Sys.store();
        float th = Sys.thermalC();
        StringBuilder sb = new StringBuilder();
        sb.append("battery: ").append(p.level < 0 ? "unknown" : p.level + "%")
          .append(p.charging ? " (charging)" : " (discharging)")
          .append(Float.isNaN(p.tempC) ? "" : ", cell " + p.tempC + "C")
          .append(", health ").append(p.health).append('\n');
        sb.append("memory: ").append(Sys.bytes(m.used)).append(" used of ")
          .append(Sys.bytes(m.total)).append(", ").append(Sys.bytes(m.avail))
          .append(" free\n");
        sb.append("storage: ").append(Sys.bytes(s.used)).append(" used of ")
          .append(Sys.bytes(s.total)).append(", ").append(Sys.bytes(s.free))
          .append(" free\n");
        sb.append("cpu: ").append(Sys.cores()).append(" cores, load estimate ")
          .append(Math.round(Sys.loadEstimate() * 100)).append("%\n");
        sb.append("thermal: ").append(Float.isNaN(th) ? "sealed" : Math.round(th) + "C").append('\n');
        sb.append("uptime: ").append(Sys.duration(Sys.uptimeMs())).append('\n');
        return sb.toString();
    }

    private static String device(Context ctx) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return "model: " + Sys.hull() + "\n"
                + "platform: " + Sys.androidRelease() + "\n"
                + "board: " + android.os.Build.BOARD + "\n"
                + "hardware: " + android.os.Build.HARDWARE + "\n"
                + "abi: " + Arrays.toString(android.os.Build.SUPPORTED_ABIS) + "\n"
                + "screen: " + dm.widthPixels + "x" + dm.heightPixels
                + " @ " + dm.densityDpi + "dpi\n"
                + "packages: " + Sys.installedPackages(ctx) + "\n";
    }

    private static String apps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(main, 0);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ResolveInfo ri : all) {
            if (n++ > 200) { sb.append("... (truncated)\n"); break; }
            sb.append(ri.loadLabel(pm)).append(" [")
              .append(ri.activityInfo.packageName).append("]\n");
        }
        return sb.length() == 0 ? "no launchable apps found" : sb.toString();
    }

    private static String launch(Context ctx, String query) {
        if (query.length() == 0) return "ERROR: name is required";
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        String q = query.toLowerCase(Locale.US);

        ResolveInfo exact = null, partial = null;
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.US);
            if (label.equals(q)) { exact = ri; break; }
            if (partial == null && (label.contains(q) || q.contains(label))) partial = ri;
        }
        ResolveInfo pick = exact != null ? exact : partial;
        if (pick == null) return "NOT FOUND: no app matching \"" + query + "\"";

        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ctx.startActivity(i);
        return "LAUNCHED: " + pick.loadLabel(pm);
    }

    private static String sensors(Context ctx) {
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return "no sensor manager";
        StringBuilder sb = new StringBuilder();
        for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            sb.append(s.getName()).append(" — ").append(s.getVendor())
              .append(", range ").append(s.getMaximumRange())
              .append(", ").append(s.getPower()).append("mA\n");
        }
        sb.append("\n(live values are on the SENSORS panel; this is the inventory)\n");
        return sb.toString();
    }

    private static String network(Context ctx) {
        Sys.Link l = Sys.link(ctx);
        return "transport: " + l.type + "\n"
                + "online: " + l.online + "\n"
                + "metered: " + l.metered + "\n"
                + "ssid: " + l.ssid + "\n"
                + "signal: " + (l.rssi == 0 ? "unknown" : l.rssi + " dBm") + "\n"
                + "link rate: " + (l.linkMbps < 0 ? "unknown" : l.linkMbps + " Mbps") + "\n"
                + "ip: " + l.ip + "\n";
    }

    private static String torch(Context ctx, boolean on) {
        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager)
                            ctx.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean has = cm.getCameraCharacteristics(id).get(
                        android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (has != null && has) {
                    cm.setTorchMode(id, on);
                    return "TORCH " + (on ? "ON" : "OFF");
                }
            }
            return "no flash on this device";
        } catch (Throwable t) {
            return "ERROR: " + t.getMessage();
        }
    }

    private static String settings(Context ctx, String which) {
        String action;
        String w = which.toLowerCase(Locale.US);
        if (w.contains("wifi")) action = Settings.ACTION_WIFI_SETTINGS;
        else if (w.contains("display") || w.contains("screen")) action = Settings.ACTION_DISPLAY_SETTINGS;
        else if (w.contains("batt") || w.contains("power")) action = Intent.ACTION_POWER_USAGE_SUMMARY;
        else if (w.contains("home") || w.contains("launcher")) action = Settings.ACTION_HOME_SETTINGS;
        else if (w.contains("app")) action = Settings.ACTION_APPLICATION_SETTINGS;
        else if (w.contains("location") || w.contains("gps")) action = Settings.ACTION_LOCATION_SOURCE_SETTINGS;
        else action = Settings.ACTION_SETTINGS;

        Intent i = new Intent(action);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return "OPENED: " + action;
    }

    private static String openUrl(Context ctx, String url) {
        if (url.length() == 0) return "ERROR: url required";
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return "OPENED: " + url;
    }

    private static String ls(String path) {
        File dir = new File(path);
        if (!dir.exists()) return "NOT FOUND: " + path;
        if (!dir.isDirectory()) return path + " is a file, " + Sys.bytes(dir.length());
        File[] kids = dir.listFiles();
        if (kids == null) return "DENIED: cannot read " + path;
        StringBuilder sb = new StringBuilder(path + ":\n");
        int n = 0;
        for (File f : kids) {
            if (n++ > 300) { sb.append("... (truncated)\n"); break; }
            sb.append(f.isDirectory() ? "d " : "- ")
              .append(f.getName())
              .append(f.isDirectory() ? "/" : "  " + Sys.bytes(f.length()))
              .append('\n');
        }
        return sb.toString();
    }

    private static String read(String path) {
        if (path.length() == 0) return "ERROR: path required";
        File f = new File(path);
        if (!f.isFile()) return "NOT FOUND: " + path;
        if (!f.canRead()) return "DENIED: cannot read " + path;
        if (f.length() > 8192 * 8) {
            return "TOO LARGE: " + Sys.bytes(f.length()) + " — read a smaller file";
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && sb.length() < 8192) {
                sb.append(line).append('\n');
            }
            if (sb.length() >= 8192) sb.append("... (truncated at 8KB)\n");
            return sb.toString();
        } catch (Throwable t) {
            return "ERROR: " + t.getMessage();
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    private static String contacts(Context ctx, String query) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts.DISPLAY_NAME},
                    null, null,
                    ContactsContract.Contacts.DISPLAY_NAME + " ASC");
            if (c == null) return "DENIED: contacts unavailable";
            StringBuilder sb = new StringBuilder();
            String q = query.toLowerCase(Locale.US);
            int n = 0;
            while (c.moveToNext() && n < 80) {
                String name = c.getString(0);
                if (name == null) continue;
                if (q.length() > 0 && !name.toLowerCase(Locale.US).contains(q)) continue;
                sb.append(name).append('\n');
                n++;
            }
            return sb.length() == 0 ? "no matching contacts" : sb.toString();
        } catch (Throwable t) {
            return "DENIED: " + t.getClass().getSimpleName();
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
    }

    private static String location(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            if (best == null) return "no cached position";
            return String.format(Locale.US,
                    "lat %.6f, lon %.6f, accuracy %.0fm, altitude %.0fm, fix age %s",
                    best.getLatitude(), best.getLongitude(), best.getAccuracy(),
                    best.getAltitude(),
                    Sys.duration(System.currentTimeMillis() - best.getTime()));
        } catch (SecurityException se) {
            return "DENIED: location permission not granted";
        } catch (Throwable t) {
            return "ERROR: " + t.getMessage();
        }
    }
}
