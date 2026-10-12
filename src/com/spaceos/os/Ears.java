package com.spaceos.os;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * EARS :: the notification feed.
 *
 * Android will not let an app read the status bar, so Space OS cannot mirror
 * it. What it can do — with the user's explicit "Notification access" grant —
 * is subscribe to the same stream the status bar is built from, and draw its
 * own bar from that.
 *
 * Dormant until the grant is given, and revocable in Settings at any time.
 */
public class Ears extends NotificationListenerService {

    private static volatile Ears instance;

    public static boolean connected() { return instance != null; }

    /** One notification, flattened into what the bar actually needs. */
    public static class Note {
        public String key;
        public String pkg;
        public String title = "";
        public String text = "";
        public long when;
        public Drawable icon;
        public boolean clearable;
        public android.app.PendingIntent open;
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Journal.info("ears", "notification feed connected");
        announce();
    }

    @Override public void onListenerDisconnected() {
        instance = null;
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // =====================================================================
    // LIVE FEED
    // =====================================================================

    /**
     * Anyone who wants to know the moment the feed changes.
     *
     * The bridge used to pull the list once, in onResume, and never again. Sit
     * on the home screen — which is where a launcher spends its life — and a
     * notification arriving would not appear until you left and came back. The
     * bar was not broken so much as frozen.
     */
    public interface Watcher { void onNotesChanged(); }

    private static final java.util.concurrent.CopyOnWriteArrayList<Watcher> watchers =
            new java.util.concurrent.CopyOnWriteArrayList<Watcher>();

    public static void watch(Watcher w) { if (w != null) watchers.addIfAbsent(w); }

    public static void unwatch(Watcher w) { watchers.remove(w); }

    private static final android.os.Handler main =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static void announce() {
        if (watchers.isEmpty()) return;
        main.post(new Runnable() {
            @Override public void run() {
                for (Watcher w : watchers) {
                    try { w.onNotesChanged(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { announce(); }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) { announce(); }

    // =====================================================================

    /**
     * Everything currently showing, newest first. Ongoing system noise — the
     * "charging" line, our own island notification — is filtered out, because
     * a bar full of things you cannot act on is worse than an empty one.
     */
    public static List<Note> active(Context ctx) {
        List<Note> out = new ArrayList<Note>();
        Ears e = instance;
        if (e == null) return out;

        StatusBarNotification[] all;
        try { all = e.getActiveNotifications(); }
        catch (Throwable t) { return out; }
        if (all == null) return out;

        PackageManager pm = ctx.getPackageManager();
        for (StatusBarNotification sbn : all) {
            try {
                if (sbn.getPackageName().equals(ctx.getPackageName())) continue;

                Notification n = sbn.getNotification();
                if (n == null) continue;
                // group summaries duplicate their children
                if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;

                Note note = new Note();
                note.key = sbn.getKey();
                note.pkg = sbn.getPackageName();
                note.when = sbn.getPostTime();
                note.clearable = sbn.isClearable();
                note.open = n.contentIntent;

                // Titles and bodies live in half a dozen different extras
                // depending on the style the app used. Reading only TITLE and
                // TEXT dropped anything built with BigTextStyle, InboxStyle or
                // MessagingStyle — which is most chat and mail apps, i.e. most
                // of the notifications anyone actually wants to see.
                Bundle x = n.extras;
                if (x != null) {
                    note.title = firstString(x,
                            Notification.EXTRA_TITLE,
                            Notification.EXTRA_TITLE_BIG,
                            Notification.EXTRA_CONVERSATION_TITLE,
                            Notification.EXTRA_SUB_TEXT);
                    note.text = firstString(x,
                            Notification.EXTRA_TEXT,
                            Notification.EXTRA_BIG_TEXT,
                            Notification.EXTRA_SUMMARY_TEXT,
                            Notification.EXTRA_INFO_TEXT);
                    if (note.text.length() == 0) {
                        CharSequence[] lines = x.getCharSequenceArray(
                                Notification.EXTRA_TEXT_LINES);
                        if (lines != null && lines.length > 0 && lines[0] != null) {
                            note.text = lines[0].toString();
                        }
                    }
                }
                if (note.title.length() == 0 && note.text.length() == 0
                        && n.tickerText != null) {
                    note.title = n.tickerText.toString();
                }

                // A CallStyle notification does not put the caller in TITLE.
                // It reads "Call" / "On-going call" there and carries the
                // person in a Person object instead, which is why an incoming
                // call showed a title, a body and no idea who it was from.
                String who = person(x);
                if (who != null) {
                    if (note.title.length() == 0
                            || note.title.equalsIgnoreCase("call")) {
                        note.title = who;
                    } else if (note.text.length() == 0) {
                        note.text = who;
                    }
                }
                // Still nothing readable, but it IS a real notification with an
                // icon. Fall back to the app's own name rather than binning it.
                if (note.title.length() == 0 && note.text.length() == 0) {
                    note.title = appLabel(pm, note.pkg);
                    if (note.title.length() == 0) continue;
                }

                try { note.icon = pm.getApplicationIcon(note.pkg); }
                catch (Throwable ignored) {}

                out.add(note);
            } catch (Throwable ignored) {}
        }

        Collections.sort(out, new Comparator<Note>() {
            @Override public int compare(Note a, Note b) {
                return Long.compare(b.when, a.when);
            }
        });
        return out;
    }

    /** The caller or sender, from whichever extra the app chose to use. */
    private static String person(Bundle x) {
        if (x == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                Object p = x.getParcelable("android.callPerson");
                if (p == null) p = x.getParcelable("android.messagingUser");
                if (p instanceof android.app.Person) {
                    CharSequence n = ((android.app.Person) p).getName();
                    if (n != null && n.toString().trim().length() > 0) return n.toString();
                }
                android.os.Parcelable[] people = x.getParcelableArray("android.people.list");
                if (people != null) {
                    for (android.os.Parcelable pp : people) {
                        if (pp instanceof android.app.Person) {
                            CharSequence n = ((android.app.Person) pp).getName();
                            if (n != null && n.toString().trim().length() > 0) {
                                return n.toString();
                            }
                        }
                    }
                }
            }
            // Pre-28 apps list people as tel: or contact: URIs.
            String[] legacy = x.getStringArray("android.people");
            if (legacy != null) {
                for (String s : legacy) {
                    if (s == null) continue;
                    if (s.startsWith("tel:")) return s.substring(4);
                    if (s.trim().length() > 0) return s;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String firstString(Bundle x, String... keys) {
        for (String k : keys) {
            try {
                CharSequence v = x.getCharSequence(k);
                if (v != null && v.toString().trim().length() > 0) return v.toString();
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) { return ""; }
    }

    /**
     * Whether the user has actually ticked Space OS in Notification access.
     *
     * {@link #connected()} only says whether the service has been bound yet,
     * which is false for a moment after boot even when access IS granted — and
     * false forever when it is not. Telling those apart is the difference
     * between the bar saying "no notifications" and saying "you have not let
     * me see them", and the first of those is a lie.
     */
    public static boolean accessGranted(Context ctx) {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(ctx.getPackageName());
        } catch (Throwable t) { return connected(); }
    }

    public static int count(Context ctx) { return active(ctx).size(); }

    /** Dismisses one notification, as the shade would. */
    public static boolean dismiss(String key) {
        Ears e = instance;
        if (e == null || key == null) return false;
        try { e.cancelNotification(key); return true; }
        catch (Throwable t) { return false; }
    }

    public static boolean dismissAll() {
        Ears e = instance;
        if (e == null) return false;
        try { e.cancelAllNotifications(); return true; }
        catch (Throwable t) { return false; }
    }

    /** App label for a package, falling back to the package name. */
    public static String appName(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }
}
