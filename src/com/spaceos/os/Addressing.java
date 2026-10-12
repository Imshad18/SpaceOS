package com.spaceos.os;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a phrase was aimed at the assistant.
 *
 * Requiring the wake word every time is annoying; acting on everything the
 * microphone picks up is worse — it means your conversation with someone else
 * gets treated as orders. This scores a phrase on how much it looks like an
 * instruction to a machine versus talk between people.
 *
 * It is a heuristic and it will be wrong sometimes, which is why the strict
 * and permissive modes both still exist.
 */
public final class Addressing {

    /** Verbs that open an instruction to a device. */
    private static final Set<String> COMMAND_VERBS = new HashSet<String>(Arrays.asList(
            "open", "launch", "start", "run", "execute", "show", "tell", "give",
            "play", "pause", "stop", "turn", "set", "call", "dial", "check",
            "find", "search", "read", "list", "close", "kill", "mute", "unmute",
            "increase", "decrease", "raise", "lower", "take", "make", "send",
            "remind", "wake", "sleep", "reboot", "scan", "report", "status"));

    /** Question openers, which usually mean a query rather than chatter. */
    private static final Set<String> QUESTION_WORDS = new HashSet<String>(Arrays.asList(
            "what", "what's", "whats", "how", "where", "when", "why", "who",
            "which", "is", "are", "can", "could", "do", "does", "did", "will",
            "should", "am"));

    /** Nouns that only really come up when talking to this device. */
    private static final Set<String> DEVICE_NOUNS = new HashSet<String>(Arrays.asList(
            "battery", "power", "charge", "wifi", "network", "uplink", "signal",
            "storage", "memory", "ram", "disk", "torch", "flashlight", "beacon",
            "sensor", "sensors", "uptime", "cpu", "processor", "brightness",
            "volume", "bluetooth", "gps", "location", "settings", "app", "apps",
            "shell", "terminal", "console", "system", "device", "phone",
            "contacts", "clipboard", "files", "archive", "screen", "display"));

    /**
     * Markers of people talking to each other. Reported speech and discourse
     * particles almost never appear in a command to a machine.
     */
    private static final String[] HUMAN_MARKERS = {
            " he said", " she said", " they said", " i said", " i told",
            " he was", " she was", " we were", " they were", " was like",
            " you know", " i mean", " kind of", " sort of", " by the way",
            "haha", " lol ", " anyway", " actually i", " but then",
            " and then he", " and then she", " told me", " asked me"};

    private Addressing() {}

    /** Verdict for one heard phrase. */
    public static class Verdict {
        public boolean addressed;
        public float score;
        public String reason = "";
    }

    /**
     * @param heard      the raw transcript
     * @param name       the assistant's configured name
     * @param actionable true when the command layer could actually do something
     *                   with this phrase — the single strongest signal there is
     * @param corpusConf how confidently the conversation corpus matched, 0..1
     */
    public static Verdict judge(String heard, String name,
                                boolean actionable, float corpusConf) {
        Verdict v = new Verdict();
        if (heard == null) return v;

        String s = heard.toLowerCase(Locale.US).trim();
        if (s.length() == 0) return v;

        // Name present: decisive, no scoring needed.
        if (name != null && containsWord(s, name)) {
            v.addressed = true;
            v.score = 1f;
            v.reason = "named";
            return v;
        }

        List<String> words = Brain.tokenize(s);
        if (words.isEmpty()) return v;

        float score = 0f;
        StringBuilder why = new StringBuilder();

        String first = words.get(0);
        if (COMMAND_VERBS.contains(first)) { score += 0.45f; why.append("imperative "); }
        else if (QUESTION_WORDS.contains(first)) { score += 0.28f; why.append("question "); }

        for (String w : words) {
            if (DEVICE_NOUNS.contains(w)) { score += 0.30f; why.append("device-term "); break; }
        }

        // The command layer can genuinely act on it — very strong evidence.
        if (actionable) { score += 0.42f; why.append("actionable "); }

        // A near-exact hit on a known conversational trigger ("hello", "thank
        // you") is strong on its own — those phrases carry no device nouns and
        // no imperative verb, so nothing else in this function would catch them.
        if (corpusConf >= 0.95f) { score += 0.42f; why.append("corpus-exact "); }
        else if (corpusConf >= 0.80f) { score += 0.24f; why.append("corpus "); }

        // Short utterances are far more likely to be commands.
        int n = words.size();
        if (n <= 4) score += 0.16f;
        else if (n <= 7) score += 0.06f;
        else if (n >= 13) { score -= 0.28f; why.append("long "); }

        // Evidence of people talking to each other.
        String padded = " " + s + " ";
        for (String marker : HUMAN_MARKERS) {
            if (padded.contains(marker)) {
                score -= 0.55f;
                why.append("human-talk ");
                break;
            }
        }

        // Second-person address to a person ("you should call her") reads as
        // conversation unless a device term is also present.
        if (padded.contains(" he ") || padded.contains(" she ")
                || padded.contains(" him ") || padded.contains(" her ")
                || padded.contains(" them ")) {
            score -= 0.22f;
        }

        v.score = Math.max(0f, Math.min(1f, score));
        v.addressed = v.score >= 0.55f;
        v.reason = why.length() == 0 ? "no signal" : why.toString().trim();
        return v;
    }

    /** Whole-word containment, so "jarvis" hits but "jarvised" does not. */
    static boolean containsWord(String haystack, String needle) {
        if (haystack == null || needle == null || needle.length() == 0) return false;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetter(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean rightOk = end >= haystack.length()
                    || !Character.isLetter(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            idx = haystack.indexOf(needle, idx + 1);
        }
        return false;
    }
}
