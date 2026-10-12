package com.spaceos.os;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Jarvis retains between sessions.
 *
 * Stored in the app's private directory — not on the shared card — so nothing
 * here is readable by other apps, and it is deleted with the app. Plain text,
 * one record per line, so you can read exactly what was kept by pulling the
 * file yourself.
 *
 * This is the only component in Space OS that writes to disk.
 */
public class Memory {

    private static final String FILE = "jarvis.memory";
    private static final int MAX_HISTORY = 4000;      // exchanges retained
    private static final int MAX_LINE = 400;          // chars per utterance

    /** Things the user asked to be remembered: "my name is X". */
    private final Map<String, String> facts = new LinkedHashMap<String, String>();

    /** Every exchange, oldest first. */
    private final List<String[]> history = new ArrayList<String[]>();

    /** Launch counts per app label, so ranking adapts to actual use. */
    private final Map<String, Integer> appUse = new HashMap<String, Integer>();

    /** Word frequency across everything the user has ever said. */
    private final Map<String, Integer> vocabulary = new HashMap<String, Integer>();

    private final File file;
    private boolean dirty;

    public Memory(Context ctx) {
        file = new File(ctx.getFilesDir(), FILE);
        load();
    }

    // =====================================================================
    // RECORDING
    // =====================================================================

    /** Records one exchange and folds the user's words into the vocabulary. */
    public synchronized void record(String said, String replied) {
        if (said == null) return;
        said = clip(said);
        replied = clip(replied == null ? "" : replied);

        history.add(new String[]{said, replied});
        while (history.size() > MAX_HISTORY) history.remove(0);

        for (String w : Brain.tokenize(said)) {
            Integer n = vocabulary.get(w);
            vocabulary.put(w, n == null ? 1 : n + 1);
        }
        dirty = true;
    }

    public synchronized void noteAppLaunch(String label) {
        if (label == null || label.length() == 0) return;
        String k = label.toLowerCase(Locale.US);
        Integer n = appUse.get(k);
        appUse.put(k, n == null ? 1 : n + 1);
        dirty = true;
    }

    public synchronized int appScore(String label) {
        if (label == null) return 0;
        Integer n = appUse.get(label.toLowerCase(Locale.US));
        return n == null ? 0 : n;
    }

    public synchronized void remember(String key, String value) {
        if (key == null || value == null) return;
        facts.put(key.toLowerCase(Locale.US).trim(), clip(value));
        dirty = true;
    }

    public synchronized String recall(String key) {
        if (key == null) return null;
        return facts.get(key.toLowerCase(Locale.US).trim());
    }

    public synchronized Map<String, String> allFacts() {
        return new LinkedHashMap<String, String>(facts);
    }

    public synchronized int exchangeCount() { return history.size(); }

    public synchronized int vocabularySize() { return vocabulary.size(); }

    public synchronized List<String[]> recentHistory(int n) {
        int from = Math.max(0, history.size() - n);
        return new ArrayList<String[]>(history.subList(from, history.size()));
    }

    /** Everything the user has ever said, for training the n-gram model. */
    public synchronized List<String> utterances() {
        List<String> out = new ArrayList<String>(history.size());
        for (String[] h : history) out.add(h[0]);
        return out;
    }

    /** The user's most-used words, heaviest first. */
    public synchronized List<String> topWords(int n) {
        List<Map.Entry<String, Integer>> es =
                new ArrayList<Map.Entry<String, Integer>>(vocabulary.entrySet());
        Collections.sort(es, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a,
                                         Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < Math.min(n, es.size()); i++) out.add(es.get(i).getKey());
        return out;
    }

    public synchronized void wipe() {
        facts.clear();
        history.clear();
        appUse.clear();
        vocabulary.clear();
        dirty = true;
        try { if (file.exists()) file.delete(); } catch (Throwable ignored) {}
    }

    // =====================================================================
    // PERSISTENCE
    // =====================================================================

    /**
     * Line format, deliberately greppable:
     *   F<TAB>key<TAB>value
     *   H<TAB>said<TAB>replied
     *   A<TAB>label<TAB>count
     *   V<TAB>word<TAB>count
     */
    public synchronized void flush() {
        if (!dirty) return;
        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new FileWriter(file, false), 8192);
            for (Map.Entry<String, String> e : facts.entrySet()) {
                w.write("F\t" + esc(e.getKey()) + "\t" + esc(e.getValue()));
                w.newLine();
            }
            for (String[] h : history) {
                w.write("H\t" + esc(h[0]) + "\t" + esc(h[1]));
                w.newLine();
            }
            for (Map.Entry<String, Integer> e : appUse.entrySet()) {
                w.write("A\t" + esc(e.getKey()) + "\t" + e.getValue());
                w.newLine();
            }
            for (Map.Entry<String, Integer> e : vocabulary.entrySet()) {
                w.write("V\t" + esc(e.getKey()) + "\t" + e.getValue());
                w.newLine();
            }
            dirty = false;
        } catch (Throwable ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }

    private void load() {
        if (!file.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(file), 8192);
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 3);
                if (p.length < 3) continue;
                String a = unesc(p[1]), b = unesc(p[2]);
                if (p[0].equals("F")) facts.put(a, b);
                else if (p[0].equals("H")) history.add(new String[]{a, b});
                else if (p[0].equals("A")) appUse.put(a, parseInt(b));
                else if (p[0].equals("V")) vocabulary.put(a, parseInt(b));
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return 0; }
    }

    private static String clip(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > MAX_LINE ? s.substring(0, MAX_LINE) : s;
    }

    private static String esc(String s) { return s.replace("\t", " "); }
    private static String unesc(String s) { return s; }
}
