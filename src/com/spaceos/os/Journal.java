package com.spaceos.os;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * JOURNAL :: the flight recorder.
 *
 * Every AI exchange, tool call, and fault lands here so a failure can be read
 * after the fact instead of guessed at. Kept in the app's private directory,
 * capped, and wipeable.
 *
 * Named Journal rather than Log so it cannot be confused with android.util.Log
 * at a glance in imports.
 */
public final class Journal {

    public static final String INFO = "INFO";
    public static final String CALL = "CALL";
    public static final String TOOL = "TOOL";
    public static final String VOICE = "VOICE";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERR ";

    private static final int MEMORY_LINES = 600;
    private static final long MAX_FILE = 512 * 1024;   // half a megabyte

    private static final List<Entry> recent = new ArrayList<Entry>();
    private static File file;
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    public static class Entry {
        public final long time;
        public final String level;
        public final String tag;
        public final String message;
        Entry(String level, String tag, String message) {
            this.time = System.currentTimeMillis();
            this.level = level; this.tag = tag; this.message = message;
        }
        public String stamp() { return STAMP.format(new Date(time)); }
    }

    private Journal() {}

    public static synchronized void attach(Context ctx) {
        if (file != null) return;
        try {
            file = new File(ctx.getApplicationContext().getFilesDir(), "spaceos.log");
        } catch (Throwable ignored) {}
    }

    public static void info(String tag, String msg)  { write(INFO, tag, msg); }
    public static void call(String tag, String msg)  { write(CALL, tag, msg); }
    public static void tool(String tag, String msg)  { write(TOOL, tag, msg); }
    public static void voice(String tag, String msg) { write(VOICE, tag, msg); }
    public static void warn(String tag, String msg)  { write(WARN, tag, msg); }
    public static void error(String tag, String msg) { write(ERROR, tag, msg); }

    public static synchronized void write(String level, String tag, String msg) {
        if (msg == null) msg = "";
        if (msg.length() > 4000) msg = msg.substring(0, 4000) + "… (truncated)";
        Entry e = new Entry(level, tag == null ? "-" : tag, msg);

        recent.add(e);
        while (recent.size() > MEMORY_LINES) recent.remove(0);

        appendToDisk(e);
    }

    /** Newest first, for the panel. */
    public static synchronized List<Entry> snapshot() {
        List<Entry> out = new ArrayList<Entry>(recent);
        java.util.Collections.reverse(out);
        return out;
    }

    public static synchronized int size() { return recent.size(); }

    public static synchronized long fileSize() {
        try { return file != null && file.exists() ? file.length() : 0; }
        catch (Throwable t) { return 0; }
    }

    public static synchronized String path() {
        return file == null ? "(not attached)" : file.getAbsolutePath();
    }

    public static synchronized void clear() {
        recent.clear();
        try { if (file != null && file.exists()) file.delete(); } catch (Throwable ignored) {}
    }

    private static void appendToDisk(Entry e) {
        if (file == null) return;
        BufferedWriter w = null;
        try {
            // simple rotation: once it is too big, start over rather than grow
            if (file.exists() && file.length() > MAX_FILE) file.delete();
            w = new BufferedWriter(new FileWriter(file, true), 4096);
            w.write(e.stamp() + "  " + e.level + "  [" + e.tag + "]  "
                    + e.message.replace('\n', '⏎'));
            w.newLine();
        } catch (Throwable ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }
}
