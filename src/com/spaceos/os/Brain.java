package com.spaceos.os;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The conversational engine.
 *
 * This is NOT a neural network and not an LLM. It is two classical language
 * models working together, which is what actually fits on a device with no
 * native code:
 *
 *   1. RETRIEVAL — every corpus entry is a bag of words. An utterance is
 *      scored against them with inverse document frequency weighting, so rare
 *      words ("thermal") count far more than common ones ("the", "what").
 *      Highest scoring entry above threshold wins, and one of its responses is
 *      chosen without repeating the last one used.
 *
 *   2. N-GRAM — a bigram Markov chain trained on everything the user has ever
 *      said. It supplies a reply when retrieval finds nothing, so the voice
 *      drifts toward the user's own phrasing over time.
 *
 * Both adapt: new exchanges reweight the IDF table and extend the chain.
 */
public class Brain {

    /** Score below which retrieval is considered a miss. */
    private static final float THRESHOLD = 0.34f;

    public static class Entry {
        final List<String> patterns = new ArrayList<String>();
        final List<String> responses = new ArrayList<String>();
        /** Token sets per pattern, precomputed. */
        final List<Set<String>> tokenSets = new ArrayList<Set<String>>();
        int lastUsed = -1;
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    /** token -> how many corpus patterns contain it (document frequency). */
    private final Map<String, Integer> docFreq = new HashMap<String, Integer>();
    private int patternCount = 0;

    /** Bigram chain learned from the user: word -> following words. */
    private final Map<String, List<String>> chain = new HashMap<String, List<String>>();

    private final Random rng = new Random();
    private Memory memory;
    private boolean loaded;

    private static final Set<String> STOP = new HashSet<String>(Arrays.asList(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "am",
            "do", "does", "did", "to", "of", "in", "on", "at", "for", "and",
            "or", "it", "its", "this", "that", "with", "as", "so", "if",
            "i", "me", "my", "you", "your", "we", "us", "he", "she", "they"));

    // =====================================================================
    // LOADING
    // =====================================================================

    public void load(Context ctx, Memory mem) {
        if (loaded) return;
        this.memory = mem;

        // built-in corpus
        try {
            InputStream in = ctx.getResources().openRawResource(R.raw.corpus);
            parse(new BufferedReader(new InputStreamReader(in), 8192));
        } catch (Throwable ignored) {}

        // user corpus, merged on top — lets you add thousands of lines
        // without rebuilding the APK
        try {
            File user = new File("/sdcard/SpaceOS/corpus.txt");
            if (user.isFile() && user.canRead()) {
                parse(new BufferedReader(new FileReader(user), 8192));
            }
        } catch (Throwable ignored) {}

        index();
        trainFromMemory();
        loaded = true;
    }

    public int entryCount() { return entries.size(); }

    public int responseCount() {
        int n = 0;
        for (Entry e : entries) n += e.responses.size();
        return n;
    }

    public int chainSize() { return chain.size(); }

    private void parse(BufferedReader r) {
        Entry current = null;
        try {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;

                if (line.startsWith("?")) {
                    // a new trigger block starts a new entry
                    if (current == null || !current.responses.isEmpty()) {
                        current = new Entry();
                        entries.add(current);
                    }
                    for (String p : line.substring(1).split("\\|")) {
                        p = p.trim().toLowerCase(Locale.US);
                        if (p.length() == 0) continue;
                        current.patterns.add(p);
                        current.tokenSets.add(tokenSet(p));
                    }
                } else if (line.startsWith("=") && current != null) {
                    String resp = line.substring(1).trim();
                    if (resp.length() > 0) current.responses.add(resp);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { r.close(); } catch (Throwable ignored) {}
        }
        // drop any trailing entry with triggers but no replies
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).responses.isEmpty()) entries.remove(i);
        }
    }

    /** Builds the document-frequency table used for IDF weighting. */
    private void index() {
        docFreq.clear();
        patternCount = 0;
        for (Entry e : entries) {
            for (Set<String> ts : e.tokenSets) {
                patternCount++;
                for (String t : ts) {
                    Integer n = docFreq.get(t);
                    docFreq.put(t, n == null ? 1 : n + 1);
                }
            }
        }
    }

    /** Seeds the Markov chain from everything the user has said before. */
    private void trainFromMemory() {
        if (memory == null) return;
        for (String u : memory.utterances()) learn(u);
    }

    // =====================================================================
    // INFERENCE
    // =====================================================================

    /**
     * Returns the best corpus response, or null when nothing scores highly
     * enough. Slot substitution is the caller's job.
     */
    public String respond(String input) {
        if (input == null || entries.isEmpty()) return null;
        Set<String> q = tokenSet(input);
        if (q.isEmpty()) return null;

        float queryWeight = weightOf(q);

        Entry best = null;
        float bestScore = 0f;

        for (Entry e : entries) {
            for (Set<String> ts : e.tokenSets) {
                float s = similarity(q, queryWeight, ts);
                if (s > bestScore) { bestScore = s; best = e; }
            }
        }

        if (best == null || bestScore < THRESHOLD) return null;
        return pick(best);
    }

    /** Score the best match would achieve, for the diagnostics panel. */
    public float confidence(String input) {
        Set<String> q = tokenSet(input);
        float queryWeight = weightOf(q);
        float bestScore = 0f;
        for (Entry e : entries) {
            for (Set<String> ts : e.tokenSets) {
                bestScore = Math.max(bestScore, similarity(q, queryWeight, ts));
            }
        }
        return bestScore;
    }

    private float weightOf(Set<String> tokens) {
        float w = 0f;
        for (String t : tokens) w += idf(t);
        return w;
    }

    /**
     * Geometric mean of two IDF-weighted ratios:
     *
     *   coverage  — how much of the pattern the query satisfied
     *   precision — how much of the query the pattern actually explained
     *
     * Coverage alone is not enough. The pattern "what are you" reduces to the
     * single token {what}, which any question containing "what" covers
     * completely; without the precision term, "what is the weather" scores a
     * perfect match against it. Precision is low there because "weather"
     * carries most of the query's weight and goes unexplained, so the real
     * weather entry wins instead.
     */
    private float similarity(Set<String> query, float queryWeight, Set<String> pattern) {
        if (pattern.isEmpty() || queryWeight <= 0f) return 0f;

        float shared = 0f, patternWeight = 0f;
        for (String t : pattern) {
            float w = idf(t);
            patternWeight += w;
            if (query.contains(t)) shared += w;
        }
        if (patternWeight <= 0f || shared <= 0f) return 0f;

        float coverage = shared / patternWeight;
        float precision = shared / queryWeight;
        return (float) Math.sqrt(coverage * precision);
    }

    private float idf(String token) {
        Integer df = docFreq.get(token);
        int d = df == null ? 1 : df;
        return (float) Math.log((patternCount + 1.0) / (d + 0.5));
    }

    /** Picks a response, avoiding an immediate repeat. */
    private String pick(Entry e) {
        if (e.responses.size() == 1) return e.responses.get(0);
        int idx;
        int guard = 0;
        do {
            idx = rng.nextInt(e.responses.size());
        } while (idx == e.lastUsed && ++guard < 6);
        e.lastUsed = idx;
        return e.responses.get(idx);
    }

    // =====================================================================
    // LEARNING
    // =====================================================================

    /** Folds one utterance into the bigram chain. */
    public void learn(String text) {
        List<String> words = tokenize(text);
        if (words.size() < 2) return;
        for (int i = 0; i < words.size() - 1; i++) {
            String a = words.get(i), b = words.get(i + 1);
            List<String> next = chain.get(a);
            if (next == null) { next = new ArrayList<String>(); chain.put(a, next); }
            if (next.size() < 64) next.add(b);      // cap per-word fan-out
        }
    }

    /**
     * Generates from the learned chain. Used only when retrieval misses and
     * the user has spoken enough for the chain to be worth sampling.
     */
    public String generate(String seedText, int maxWords) {
        if (chain.size() < 25) return null;         // not enough signal yet

        List<String> seedWords = tokenize(seedText);
        String w = null;
        // prefer starting from a word the user just used
        for (int i = seedWords.size() - 1; i >= 0; i--) {
            if (chain.containsKey(seedWords.get(i))) { w = seedWords.get(i); break; }
        }
        if (w == null) {
            List<String> keys = new ArrayList<String>(chain.keySet());
            w = keys.get(rng.nextInt(keys.size()));
        }

        StringBuilder sb = new StringBuilder(w);
        for (int i = 1; i < maxWords; i++) {
            List<String> next = chain.get(w);
            if (next == null || next.isEmpty()) break;
            w = next.get(rng.nextInt(next.size()));
            sb.append(' ').append(w);
            if (i > 3 && rng.nextFloat() < 0.18f) break;
        }
        String out = sb.toString().trim();
        if (out.length() < 6) return null;
        return out.substring(0, 1).toUpperCase(Locale.US) + out.substring(1) + ".";
    }

    // =====================================================================
    // TOKENISING
    // =====================================================================

    /** Lowercase word tokens, punctuation stripped. Stopwords kept. */
    public static List<String> tokenize(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        StringBuilder cur = new StringBuilder();
        String lower = s.toLowerCase(Locale.US);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '\'') {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Token set with stopwords removed — what matching actually runs on. */
    private static Set<String> tokenSet(String s) {
        Set<String> set = new HashSet<String>();
        for (String t : tokenize(s)) {
            if (t.length() < 2 && !t.equals("i")) continue;
            if (STOP.contains(t)) continue;
            set.add(stem(t));
        }
        // if stripping left nothing, fall back to the raw tokens
        if (set.isEmpty()) set.addAll(tokenize(s));
        return set;
    }

    /** Crude suffix stemmer — enough to bind "sensors" to "sensor". */
    private static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("es")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }
}
