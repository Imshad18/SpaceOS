package com.spaceos.os;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The agentic loop: model proposes a tool, Space OS runs it, the result goes
 * back, repeat until the model answers in plain language.
 *
 * Tool calls travel as a text protocol rather than provider-native function
 * calling:
 *
 *     &lt;tool&gt;{"name":"shell","args":{"cmd":"df -h"}}&lt;/tool&gt;
 *
 * That is deliberate. Native function-calling schemas differ across OpenAI,
 * Anthropic and the local servers, and most small local models do not support
 * them at all. A text protocol works everywhere, which is the point of letting
 * you plug in any provider.
 */
public class Agent {

    /**
     * Backstop only. The real limit comes from {@link Config#maxRounds()},
     * which the user sets and can push to effectively unbounded.
     *
     * A ceiling exists at all because the failure mode without one is not
     * "runs forever doing useful work" — it is a model looping
     * tap -> screen -> tap on the same element all night, burning the user's
     * API credits and re-sending the same message forty times. Stall
     * detection below is what actually keeps it honest; this is just a wall.
     */
    private static final int ABSOLUTE_CEILING = 500;

    public interface Trace {
        /** The model said something in plain language. */
        void onThought(String text);
        /** A tool is about to run. */
        void onToolCall(String name, String args);
        /** That tool returned. */
        void onToolResult(String name, String result);
        /** Terminal answer, suitable for speaking aloud. */
        void onAnswer(String text);
        /** Something went wrong; the loop is over. */
        void onFailure(String message);
        /** Step counter, so a long run does not look frozen. */
        void onProgress(int step, int limit);
    }

    private final Context ctx;
    private final Config cfg;
    private final LlmClient client;

    /** Conversation carried across turns so follow-ups have context. */
    private final List<LlmClient.Msg> history = new ArrayList<LlmClient.Msg>();

    public Agent(Context ctx, Config cfg) {
        this.ctx = ctx.getApplicationContext();
        this.cfg = cfg;
        this.client = new LlmClient(cfg);
    }

    public void reset() { history.clear(); }

    public int turnCount() { return history.size(); }

    // =====================================================================

    /** Blocking. Run on a worker thread. */
    public void ask(String userText, Trace trace) {
        if (!cfg.enabled()) {
            trace.onFailure("neural link is not configured");
            return;
        }

        Journal.info("agent", "ask: " + userText);
        history.add(new LlmClient.Msg("user", userText));
        trimHistory();

        String system = systemPrompt();
        int limit = Math.min(ABSOLUTE_CEILING, Math.max(4, cfg.maxRounds()));

        recentCalls.clear();
        cancelled = false;

        for (int round = 0; round < limit; round++) {
            if (cancelled) { trace.onFailure("stopped"); return; }

            String reply;
            try {
                reply = client.complete(system, history);
            } catch (Throwable t) {
                Journal.error("agent", "completion failed: " + t.getMessage());
                trace.onFailure(String.valueOf(t.getMessage()));
                return;
            }

            history.add(new LlmClient.Msg("assistant", reply));
            Journal.call("agent", "reply: " + clip(reply, 600));

            Call call = parseCall(reply);
            String spoken = stripCalls(reply).trim();

            if (call == null) {
                // no tool requested — this is the answer
                trace.onAnswer(spoken.length() > 0 ? spoken : reply.trim());
                return;
            }

            if (spoken.length() > 0) trace.onThought(spoken);
            trace.onToolCall(call.name, call.args.toString());
            Journal.tool(call.name, call.args.toString());
            trace.onProgress(round + 1, limit);

            // ---- stall detection ----------------------------------------
            String signature = call.name + "|" + call.args.toString();
            recentCalls.add(signature);
            while (recentCalls.size() > 8) recentCalls.remove(0);
            int repeats = 0;
            for (String s : recentCalls) if (s.equals(signature)) repeats++;

            String result = Tools.run(ctx, cfg, call.name, call.args);
            trace.onToolResult(call.name, result);
            Journal.tool(call.name + ":result", clip(result, 800));

            String note = "";
            if (repeats >= 5) {
                trace.onFailure("stuck — repeated the same " + call.name
                        + " call " + repeats + " times without progress");
                return;
            } else if (repeats == 3) {
                // nudge before giving up; often enough to break the cycle
                note = "\n\nNOTE: you have now issued this exact call three times "
                        + "and the state is not changing. Try a different approach, "
                        + "or tell the user what is blocking you.";
            }

            history.add(new LlmClient.Msg("user",
                    "TOOL RESULT (" + call.name + "):\n" + clip(result, 6000) + note));
            trimHistory();
        }

        trace.onFailure("reached the " + limit + " step limit — say continue to resume");
    }

    /** Signals the loop to stop at the next round boundary. */
    public void cancel() { cancelled = true; }

    private volatile boolean cancelled;

    /** Rolling window of recent tool-call signatures, for loop detection. */
    private final List<String> recentCalls = new ArrayList<String>();

    // =====================================================================
    // PROMPT
    // =====================================================================

    private String systemPrompt() {
        String name = cfg == null ? "Jarvis" : Config.displayName(assistantName());
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(name)
          .append(", the voice of Space OS — a custom Android launcher running on the user's phone.\n\n");

        sb.append("You are speaking aloud through text-to-speech. Keep replies short and ")
          .append("conversational: one or two sentences unless asked for detail. ")
          .append("No markdown, no bullet points, no code blocks in your spoken answer.\n\n");

        sb.append("DEVICE: ").append(Sys.hull())
          .append(", ").append(Sys.androidRelease()).append('\n');
        sb.append("You run as an ordinary unprivileged app. There is no root. ")
          .append("If something is blocked by Android, say so plainly instead of pretending.\n\n");

        sb.append("TOOLS — to use one, emit exactly this and nothing after it:\n");
        sb.append("<tool>{\"name\":\"TOOLNAME\",\"args\":{...}}</tool>\n\n");
        sb.append(Tools.catalogue(cfg));
        sb.append("\nRules:\n");
        sb.append("- One tool per message. Wait for the result before the next.\n");
        sb.append("- Use a tool when you need real data. Never invent a reading.\n");
        sb.append("- If a tool reports DENIED, tell the user which switch to turn on in NEURAL setup.\n");
        sb.append("\nFINISHING THE JOB — this matters most:\n");
        sb.append("- Opening an app is the START of a task, never the end. ")
          .append("\"open Instagram and message Ravi\" means: launch it, wait, read the ")
          .append("screen, tap through to the chat, type, send, then confirm.\n");
        sb.append("- After launch or any tap, call wait then screen. The UI needs a ")
          .append("moment and you must see the new state before acting again.\n");
        sb.append("- Keep going until the user's actual goal is done. Only reply in ")
          .append("plain speech when the task is complete or you are genuinely stuck.\n");
        sb.append("- If you are stuck, say exactly what you see and what you tried.\n");
        return sb.toString();
    }

    private String assistantName() {
        try { return cfg.assistantName(); } catch (Throwable t) { return "jarvis"; }
    }

    /** Keeps the window bounded so long sessions do not blow the context. */
    private void trimHistory() {
        final int MAX = 24;
        while (history.size() > MAX) history.remove(0);
    }

    // =====================================================================
    // PARSING
    // =====================================================================

    private static class Call {
        String name;
        JSONObject args;
    }

    /** Extracts the first well-formed tool call, or null. */
    private static Call parseCall(String reply) {
        if (reply == null) return null;
        int open = reply.indexOf("<tool>");
        if (open < 0) return null;
        int close = reply.indexOf("</tool>", open);
        String json = close < 0
                ? reply.substring(open + 6).trim()
                : reply.substring(open + 6, close).trim();
        if (json.length() == 0) return null;

        try {
            JSONObject o = new JSONObject(json);
            Call c = new Call();
            c.name = o.optString("name", "").trim().toLowerCase(Locale.US);
            if (c.name.length() == 0) return null;
            c.args = o.optJSONObject("args");
            if (c.args == null) c.args = new JSONObject();
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Removes tool tags so what is left can be spoken. */
    private static String stripCalls(String reply) {
        if (reply == null) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int open = reply.indexOf("<tool>", i);
            if (open < 0) { sb.append(reply.substring(i)); break; }
            sb.append(reply, i, open);
            int close = reply.indexOf("</tool>", open);
            if (close < 0) break;
            i = close + 7;
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
    }
}
