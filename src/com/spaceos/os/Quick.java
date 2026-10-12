package com.spaceos.os;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * QUICK :: the local command layer, tried before the network.
 *
 * "Open Instagram" does not need a language model. Routing it through one made
 * every simple command wait on a remote round trip, cost quota, and fail
 * outright when the quota ran out — a 429 meant the phone would not even open
 * an app any more.
 *
 * So: this runs first, on-device, instantly. Anything it recognises never
 * touches the network. Anything it does not returns null and escalates to the
 * agent. It is also the fallback when the agent fails, so a dead link degrades
 * to a working launcher rather than to nothing.
 */
public final class Quick {

    private Quick() {}

    /**
     * A local result. Some actions are their own confirmation — when Facebook
     * fills the screen, saying "Opening Facebook" out loud is just noise on
     * top of the thing you already asked for. Those are shown, not spoken.
     */
    public static class Result {
        public final String text;
        public final boolean speak;
        Result(String text, boolean speak) { this.text = text; this.speak = speak; }
    }

    private static Result silent(String s) { return new Result(s, false); }
    private static Result spoken(String s) { return new Result(s, true); }

    /** Returns what to do, or null if this needs the model. */
    public static Result run(Context ctx, Config cfg, String raw) {
        String s = tryLocal(ctx, cfg, raw);
        if (s == null) return null;
        // Anything that visibly changed the screen speaks for itself.
        boolean visible = s.startsWith("Opening ") || s.equals("Recents.")
                || s.equals("Home.") || s.equals("Back.") || s.equals("Scrolled.");
        return visible ? silent(s) : spoken(s);
    }

    /** Returns what to say, or null if this needs the model. */
    public static String tryLocal(Context ctx, Config cfg, String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.US).trim();
        if (s.length() == 0) return null;

        s = stripName(s, cfg.assistantName());
        if (s.length() == 0) return "Standing by.";

        // ---- launch an app ------------------------------------------------
        // "open the terminal" names a module, not an installed app. Try apps
        // first, but fall THROUGH on a miss — returning null here meant the
        // module checks below were never reached.
        String app = after(s, "open ", "launch ", "start ", "run ");
        if (app != null && !isCompound(app)) {
            String launched = launch(ctx, app);
            if (launched != null) return "Opening " + launched + ".";
        }

        // ---- beacon --------------------------------------------------------
        if (has(s, "torch", "flashlight", "beacon")) {
            boolean off = has(s, "off", "stop", "kill", "dark");
            String r = Tools.run(ctx, cfg, "torch",
                    json("on", !off));
            return r.startsWith("TORCH") ? (off ? "Beacon dark." : "Beacon engaged.") : null;
        }

        // ---- telemetry, answered from local sensors ------------------------
        if (has(s, "battery", "power cell", "charge level", "how much charge")) {
            Sys.Power p = Sys.power(ctx);
            return "Power cell at " + (p.level < 0 ? "unknown" : p.level + " percent")
                    + (p.charging ? ", charging." : ".");
        }
        if (has(s, "memory", " ram")) {
            Sys.Mem m = Sys.mem(ctx);
            return "Memory: " + Sys.bytes(m.avail) + " free of " + Sys.bytes(m.total) + ".";
        }
        if (has(s, "storage", "disk space", "free space")) {
            Sys.Store st = Sys.store();
            return "Archive: " + Sys.bytes(st.free) + " free of " + Sys.bytes(st.total) + ".";
        }
        if (has(s, "wifi", "wi fi", "uplink", "network status", "am i online")) {
            Sys.Link l = Sys.link(ctx);
            return l.online
                    ? "Online over " + l.type.toLowerCase(Locale.US)
                      + (l.ssid.equals("--") ? "." : ", " + l.ssid + ".")
                    : "No uplink.";
        }
        if (has(s, "what time", "the time", "clock")) {
            return "It is " + new SimpleDateFormat("h:mm a", Locale.US)
                    .format(new Date()) + ".";
        }
        if (has(s, "what day", "what date", "today's date")) {
            return "Today is " + new SimpleDateFormat("EEEE, MMMM d", Locale.US)
                    .format(new Date()) + ".";
        }
        if (has(s, "uptime")) {
            return "Uptime " + Sys.duration(Sys.uptimeMs()) + ".";
        }

        // ---- module vectors -------------------------------------------------
        String module = moduleFor(s);
        if (module != null) {
            openModule(ctx, module);
            return "Opening " + module + ".";
        }
        if (has(s, "settings", "system settings")) {
            go(ctx, new Intent(Settings.ACTION_SETTINGS));
            return "Host settings.";
        }
        if (has(s, "recents", "recent apps")) {
            Hands h = Hands.get();
            if (h != null) { h.global("recents"); return "Recents."; }
            return "Enable Space OS Hands for that.";
        }
        if (has(s, "go home", "home screen")) {
            Hands h = Hands.get();
            if (h != null) { h.global("home"); return "Home."; }
            return null;
        }
        if (has(s, "go back")) {
            Hands h = Hands.get();
            if (h != null) { h.global("back"); return "Back."; }
            return null;
        }
        if (has(s, "scroll down", "scroll up")) {
            Hands h = Hands.get();
            if (h != null) return h.scroll(!has(s, "scroll up")).startsWith("SCROLLED")
                    ? "Scrolled." : "Nothing to scroll.";
            return "Enable Space OS Hands for that.";
        }

        return null;    // not something we can do locally
    }

    // =====================================================================

    private static String moduleFor(String s) {
        if (has(s, "terminal", "shell", "console")) return "shell";
        if (has(s, "sensor", "gyro", "accelerometer")) return "sensors";
        if (has(s, "contacts", "call log", "comms")) return "comms";
        if (has(s, "photos", "gallery", "optics")) return "media";
        if (has(s, "files", "archive")) return "files";
        if (has(s, "location", "where am i", "gps", "navigation")) return "location";
        if (has(s, "packages", "registry", "installed apps")) return "packages";
        if (has(s, "processes", "process table")) return "proc";
        if (has(s, "clipboard")) return "clip";
        if (has(s, "display", "refresh rate")) return "display";
        if (has(s, "logs", "journal")) return "journal";
        if (has(s, "app drawer", "all apps", "app lattice")) return "apps";
        return null;
    }

    private static void openModule(Context ctx, String id) {
        Intent i = new Intent(ctx, Module.class);
        i.putExtra(Module.EXTRA_ID, id);
        go(ctx, i);
    }

    private static void go(Context ctx, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    /** Exact label first, then prefix, then substring. */
    private static String launch(Context ctx, String query) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> all = pm.queryIntentActivities(main, 0);

            ResolveInfo exact = null, prefix = null, contains = null;
            for (ResolveInfo ri : all) {
                String label = ri.loadLabel(pm).toString().toLowerCase(Locale.US);
                if (label.equals(query)) { exact = ri; break; }
                if (prefix == null && label.startsWith(query)) prefix = ri;
                if (contains == null && label.contains(query)) contains = ri;
            }
            ResolveInfo pick = exact != null ? exact : prefix != null ? prefix : contains;
            if (pick == null) return null;

            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(launch);
            return pick.loadLabel(pm).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** "messages in instagram" is a task, not an app name. */
    private static boolean isCompound(String q) {
        String p = " " + q.trim() + " ";
        if (p.contains(" in ") || p.contains(" on ") || p.contains(" from ")
                || p.contains(" to ") || p.contains(" and ") || p.contains(" then ")
                || p.contains(" with ") || p.contains(" for ")) return true;
        return q.trim().split("\\s+").length > 3;
    }

    private static String stripName(String s, String name) {
        for (String lead : new String[]{"hey " + name, "ok " + name, "okay " + name,
                "hi " + name, name}) {
            if (s.equals(lead)) return "";
            if (s.startsWith(lead + " ") || s.startsWith(lead + ",")) {
                return s.substring(lead.length()).replaceFirst("^[,\\s]+", "").trim();
            }
        }
        if (s.endsWith(" " + name)) return s.substring(0, s.length() - name.length() - 1).trim();
        return s;
    }

    private static String after(String s, String... leads) {
        for (String lead : leads) {
            int i = s.indexOf(lead);
            if (i >= 0) {
                String rest = s.substring(i + lead.length()).trim();
                if (rest.length() > 0) return rest;
            }
        }
        return null;
    }

    private static boolean has(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    private static JSONObject json(String k, boolean v) {
        try { return new JSONObject().put(k, v); }
        catch (Throwable t) { return new JSONObject(); }
    }
}
