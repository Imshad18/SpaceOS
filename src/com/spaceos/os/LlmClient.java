package com.spaceos.os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The only component in Space OS that touches the network, and only when the
 * neural link has been explicitly enabled.
 *
 * Two adapters cover essentially every provider worth having:
 *
 *   OPENAI_COMPAT — the /chat/completions shape. OpenAI, OpenRouter, Groq,
 *                   Together, DeepSeek, Mistral, HuggingFace's router, and
 *                   every local server (Ollama, llama.cpp, LM Studio, vLLM)
 *                   all speak it.
 *   ANTHROPIC     — the /messages shape, for talking to Claude directly.
 *
 * Written against HttpURLConnection and org.json, both in the platform, so
 * there are no third-party dependencies to audit.
 */
public class LlmClient {

    public static class Msg {
        public final String role;      // "user" | "assistant"
        public final String content;
        public Msg(String role, String content) { this.role = role; this.content = content; }
    }

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 90000;
    private static final int MAX_TOKENS = 1400;

    private final Config cfg;

    public LlmClient(Config cfg) { this.cfg = cfg; }

    /**
     * One completion. Blocking — never call from the main thread.
     * Throws with a human-readable message on any failure.
     */
    public String complete(String system, List<Msg> history) throws Exception {
        if (!cfg.enabled()) throw new Exception("neural link disabled");
        return cfg.kind().equals(Config.ANTHROPIC)
                ? anthropic(system, history)
                : openaiCompat(system, history);
    }

    // =====================================================================
    // OPENAI-COMPATIBLE
    // =====================================================================

    private String openaiCompat(String system, List<Msg> history) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", cfg.model());
        body.put("max_tokens", MAX_TOKENS);
        body.put("temperature", 0.6);

        JSONArray msgs = new JSONArray();
        if (system != null && system.length() > 0) {
            msgs.put(new JSONObject().put("role", "system").put("content", system));
        }
        for (Msg m : history) {
            msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
        }
        body.put("messages", msgs);

        JSONObject res = post(cfg.endpoint() + "/chat/completions", body, false);

        JSONArray choices = res.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("no choices in response");
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) throw new Exception("malformed choice");
        String content = msg.optString("content", "");
        if (content.length() == 0) throw new Exception("empty completion");
        return content;
    }

    // =====================================================================
    // ANTHROPIC
    // =====================================================================

    private String anthropic(String system, List<Msg> history) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", cfg.model());
        body.put("max_tokens", MAX_TOKENS);
        if (system != null && system.length() > 0) body.put("system", system);

        JSONArray msgs = new JSONArray();
        for (Msg m : history) {
            msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
        }
        body.put("messages", msgs);

        JSONObject res = post(cfg.endpoint() + "/messages", body, true);

        JSONArray content = res.optJSONArray("content");
        if (content == null || content.length() == 0) throw new Exception("empty response");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block != null && "text".equals(block.optString("type"))) {
                sb.append(block.optString("text", ""));
            }
        }
        if (sb.length() == 0) throw new Exception("no text block");
        return sb.toString();
    }

    // =====================================================================
    // TRANSPORT
    // =====================================================================

    private JSONObject post(String url, JSONObject body, boolean anthropicAuth)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            String key = cfg.apiKey();
            if (anthropicAuth) {
                if (key.length() > 0) conn.setRequestProperty("x-api-key", key);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            } else if (key.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            // OpenRouter asks callers to identify themselves
            conn.setRequestProperty("HTTP-Referer", "https://spaceos.local");
            conn.setRequestProperty("X-Title", "Space OS");

            byte[] payload = body.toString().getBytes("UTF-8");
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (code >= 400) {
                String detail = briefError(text);
                Journal.error("http", "HTTP " + code + " " + url + " :: " + detail);
                throw new Exception("HTTP " + code + ": " + detail);
            }
            return new JSONObject(text);

        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    /** Pulls the useful sentence out of a provider error blob. */
    private static String briefError(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (m.length() > 0) return m;
            }
            String m = o.optString("message", "");
            if (m.length() > 0) return m;
        } catch (Throwable ignored) {}
        raw = raw == null ? "" : raw.trim().replace('\n', ' ');
        return raw.length() > 180 ? raw.substring(0, 180) + "…" : raw;
    }

    /**
     * Asks the provider what models it actually serves.
     *
     * Hardcoded model ids rot — Google retired gemini-2.0-flash and every
     * request started 404ing with no way for the user to know what to type
     * instead. This makes the catalogue live.
     */
    public List<String> listModels() throws Exception {
        String url = cfg.endpoint() + "/models";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");

            String key = cfg.apiKey();
            if (cfg.kind().equals(Config.ANTHROPIC)) {
                if (key.length() > 0) conn.setRequestProperty("x-api-key", key);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            } else if (key.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }

            int code = conn.getResponseCode();
            String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new Exception("HTTP " + code + ": " + briefError(text));

            JSONObject o = new JSONObject(text);
            // OpenAI-compatible: {data:[{id:...}]}   Anthropic: {data:[{id:...}]}
            JSONArray arr = o.optJSONArray("data");
            if (arr == null) arr = o.optJSONArray("models");
            if (arr == null) throw new Exception("no model list in response");

            List<String> out = new ArrayList<String>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", m.optString("name", ""));
                // Gemini returns "models/gemini-x"; the chat endpoint wants the bare id
                if (id.startsWith("models/")) id = id.substring(7);
                if (id.length() > 0) out.add(id);
            }
            if (out.isEmpty()) throw new Exception("provider returned an empty list");
            java.util.Collections.sort(out);
            return out;

        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** Quick reachability probe used by the setup screen. */
    public String ping() {
        try {
            List<Msg> one = new ArrayList<Msg>();
            one.add(new Msg("user", "Reply with exactly: ONLINE"));
            String r = complete("You are a terse test endpoint.", one);
            return "OK — " + r.trim().replace('\n', ' ');
        } catch (Throwable t) {
            return "FAIL — " + t.getMessage();
        }
    }
}
