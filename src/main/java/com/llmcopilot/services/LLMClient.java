package com.llmcopilot.services;

import com.google.gson.*;
import com.llmcopilot.settings.LLMCopilotSettings;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLMClient — HTTP client for all supported LLM providers.
 *
 * Providers: ollama | openai | anthropic | mistral | groq | openrouter |
 *            lmstudio | claudecode | custom
 *
 * Claude Code uses a port+path auto-discovery waterfall that tries all known
 * community proxy configurations and caches the first working one.
 */
public class LLMClient {

    private static final Gson GSON = new Gson();

    public record ChatMessage(String role, String content) {}

    // ── Claude Code endpoint discovery cache ─────────────────────────────────

    private record ClaudeEndpoint(int port, String path, boolean isAnthropic) {}

    private static final AtomicReference<ClaudeEndpoint> cachedEndpoint = new AtomicReference<>(null);
    private static volatile String cachedBaseUrl = "";

    /** Known Claude Code proxy configurations (port, path, isAnthropic) */
    private static final Object[][] CLAUDE_CANDIDATES = {
        {3000,  "/v1/messages",         true },
        {3000,  "/v1/chat/completions", false},
        {3456,  "/v1/chat/completions", false},   // claude-max-api-proxy
        {8000,  "/v1/chat/completions", false},   // claude-code-api
        {4141,  "/v1/chat/completions", false},   // copilot-api
        {8082,  "/v1/messages",         true },   // claude-code-proxy
        {8080,  "/v1/chat/completions", false},
        {1234,  "/v1/chat/completions", false},
        {11435, "/v1/chat/completions", false},
    };

    // ── Main public API ───────────────────────────────────────────────────────

    public static String complete(String prompt) throws Exception {
        return chat(List.of(new ChatMessage("user", prompt)));
    }

    public static String chat(List<ChatMessage> messages) throws Exception {
        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
        return switch (s.getProvider()) {
            case "ollama"     -> ollamaChat(messages, s);
            case "anthropic"  -> anthropicChat(messages, s);
            case "mistral"    -> mistralChat(messages, s);
            case "claudecode" -> claudeCodeChat(messages, s);
            case "gemini"     -> geminiChat(messages, s);
            case "deepseek"   -> deepseekChat(messages, s);
            case "grok"       -> grokChat(messages, s);
            case "azure"      -> azureChat(messages, s);
            default           -> openaiChat(messages, s);   // openai / groq / openrouter / lmstudio / custom
        };
    }

    public static String testConnection() {
        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
        try {
            if ("ollama".equals(s.getProvider())) {
                String url = s.getBaseUrl().replaceAll("/$", "") + "/api/tags";
                httpGet(url);
                return "✅ Connected to Ollama at " + s.getBaseUrl();
            }
            if ("claudecode".equals(s.getProvider())) {
                cachedEndpoint.set(null); cachedBaseUrl = "";
                ClaudeEndpoint ep = discoverClaudeEndpoint(s);
                return "✅ Claude Code at port " + ep.port() + ep.path() +
                       " (" + (ep.isAnthropic() ? "Anthropic" : "OpenAI") + " format)";
            }
            String resp = chat(List.of(new ChatMessage("user", "Say OK.")));
            return "✅ Connected to " + s.getProvider() + " (" + s.getModel() + "): " + resp.substring(0, Math.min(50, resp.length()));
        } catch (Exception e) {
            return "❌ " + e.getMessage();
        }
    }

    public static List<String> diagnoseClaudeCode() {
        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
        String host = extractHost(s.getBaseUrl());
        List<String> results = new ArrayList<>();
        results.add("Probing host: " + host);
        results.add("");
        for (Object[] c : CLAUDE_CANDIDATES) {
            int port = (int)c[0]; String path = (String)c[1]; boolean isAnthro = (boolean)c[2];
            String url = "http://" + host + ":" + port + path;
            try {
                String body = buildClaudeBody(List.of(new ChatMessage("user","Hi")), 16, s.getModel(), isAnthro);
                Map<String,String> hdrs = isAnthro ? Map.of("anthropic-version","2023-06-01") : Map.of();
                String raw = httpPost(url, body, hdrs);
                String content = extractContent(raw);
                results.add("✅ PORT " + port + "  " + path + "  (" + (isAnthro ? "Anthropic" : "OpenAI") + ")");
                results.add("   → Set baseUrl to http://localhost:" + port);
                results.add("   → Set claudeCodeApiPath to " + path);
            } catch (Exception e) {
                results.add("❌ PORT " + port + "  " + path + ": " + e.getMessage().substring(0, Math.min(60, e.getMessage().length())));
            }
        }
        return results;
    }

    // ── Provider implementations ──────────────────────────────────────────────

    private static String ollamaChat(List<ChatMessage> messages, LLMCopilotSettings s) throws Exception {
        String url = s.getBaseUrl().replaceAll("/$","") + "/api/chat";
        JsonObject body = new JsonObject();
        body.addProperty("model", s.getModel());
        body.addProperty("stream", false);
        JsonObject opts = new JsonObject();
        opts.addProperty("temperature", s.getTemperature());
        body.add("options", opts);
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            msgs.add(o);
        }
        body.add("messages", msgs);
        String raw = httpPost(url, GSON.toJson(body), Map.of());
        JsonObject resp = GSON.fromJson(raw, JsonObject.class);
        return resp.getAsJsonObject("message").get("content").getAsString();
    }

    private static String anthropicChat(List<ChatMessage> messages, LLMCopilotSettings s) throws Exception {
        String url = "https://api.anthropic.com/v1/messages";
        String body = buildAnthropicBody(messages, s.getMaxTokens(), s.getModel(), s.getTemperature());
        String raw  = httpPost(url, body, Map.of(
            "x-api-key", s.getApiKey(),
            "anthropic-version", "2023-06-01"
        ));
        JsonObject resp = GSON.fromJson(raw, JsonObject.class);
        return resp.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static String mistralChat(List<ChatMessage> messages, LLMCopilotSettings s) throws Exception {
        String url  = "https://api.mistral.ai/v1/chat/completions";
        String body = buildOpenAIBody(messages, s.getMaxTokens(), s.getModel(), s.getTemperature());
        String raw  = httpPost(url, body, Map.of("Authorization", "Bearer " + s.getApiKey()));
        return extractOpenAIContent(raw);
    }

    // ── Google Gemini (OpenAI-compatible endpoint) ────────────────────────────
    private static String geminiChat(List<ChatMessage> messages, LLMCopilotSettings s) throws Exception {
        String model = s.getModel().isEmpty() ? "gemini-2.5-flash" : s.getModel();
        String body  = buildOpenAIBody(messages, s.getMaxTokens(), model, s.getTemperature());
        String raw   = httpPost(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            body, Map.of("Authorization", "Bearer " + s.getApiKey()));
        return extractOpenAIContent(raw);
    }

    // ── DeepSeek ──────────────────────────────────────────────────────────────
    private static String deepseekChat(List<ChatMessage> messages, LLMCopilotSettings s) throws Exception {
        String model = s.getModel().isEmpty() ? "deepseek-chat" : s.getModel();
        String body  = buildOpenAIBody(messages, s.getMaxTokens(), model, s.getTemperature());
        String raw   = httpPost(
            "https://api.deepseek.com/v1/chat/completions",
            body, Map.of("Authorization", "Bearer " + s.getApiKey()));
        return extractOpenAIContent(raw);
    }

    // ── xAI Grok ──────────────────────────────────────────────────────────────
    private static String grokChat(List<ChatMessage> messages, LLMCopilotSettings s) throws Exception {
        String model = s.getModel().isEmpty() ? "grok-3-mini" : s.getModel();
        String body  = buildOpenAIBody(messages, s.getMaxTokens(), model, s.getTemperature());
        String raw   = httpPost(
            "https://api.x.ai/v1/chat/completions",
            body, Map.of("Authorization", "Bearer " + s.getApiKey()));
        return extractOpenAIContent(raw);
    }

    // ── Azure OpenAI ──────────────────────────────────────────────────────────
    // baseUrl = https://{resource}.openai.azure.com/openai/deployments/{deployment}
    // apiKey  = Azure API key
    private static String azureChat(List<ChatMessage> messages, LLMCopilotSettings s) throws Exception {
        String base = s.getBaseUrl().replaceAll("/$", "");
        String url  = base + "/chat/completions?api-version=2024-12-01-preview";
        // Azure: model is part of the URL (deployment), not the body
        JsonObject body = new JsonObject();
        body.addProperty("max_tokens", s.getMaxTokens());
        if (s.getTemperature() > 0) body.addProperty("temperature", s.getTemperature());
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role()); o.addProperty("content", m.content());
            msgs.add(o);
        }
        body.add("messages", msgs);
        String raw = httpPost(url, GSON.toJson(body), Map.of("api-key", s.getApiKey()));
        return extractOpenAIContent(raw);
    }

    private static String openaiChat(List<ChatMessage> messages, LLMCopilotSettings s) throws Exception {
        String base = switch (s.getProvider()) {
            case "openai"     -> "https://api.openai.com";
            case "groq"       -> "https://api.groq.com/openai";
            case "openrouter" -> "https://openrouter.ai/api";
            default           -> s.getBaseUrl().replaceAll("/$","");
        };
        String url  = base + "/v1/chat/completions";
        String body = buildOpenAIBody(messages, s.getMaxTokens(), s.getModel(), s.getTemperature());
        Map<String,String> hdrs = new HashMap<>();
        if (!s.getApiKey().isEmpty()) hdrs.put("Authorization", "Bearer " + s.getApiKey());
        if ("openrouter".equals(s.getProvider())) hdrs.put("X-Title", "LLM Copilot IntelliJ");
        String raw = httpPost(url, body, hdrs);
        return extractOpenAIContent(raw);
    }

    // ── Claude Code with auto-discovery ───────────────────────────────────────

    private static String claudeCodeChat(List<ChatMessage> messages, LLMCopilotSettings s) throws Exception {
        ClaudeEndpoint ep = discoverClaudeEndpoint(s);
        String host = extractHost(s.getBaseUrl());
        String url  = "http://" + host + ":" + ep.port() + ep.path();
        String body = buildClaudeBody(messages, 2048, s.getModel(), ep.isAnthropic());
        Map<String,String> hdrs = ep.isAnthropic() ? Map.of("anthropic-version","2023-06-01") : Map.of();
        try {
            String raw = httpPost(url, body, hdrs);
            return extractContent(raw);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("405")) {
                // Clear cache and retry once
                cachedEndpoint.set(null); cachedBaseUrl = "";
                ClaudeEndpoint ep2 = discoverClaudeEndpoint(s);
                String url2  = "http://" + host + ":" + ep2.port() + ep2.path();
                String body2 = buildClaudeBody(messages, 2048, s.getModel(), ep2.isAnthropic());
                Map<String,String> hdrs2 = ep2.isAnthropic() ? Map.of("anthropic-version","2023-06-01") : Map.of();
                return extractContent(httpPost(url2, body2, hdrs2));
            }
            throw e;
        }
    }

    private static ClaudeEndpoint discoverClaudeEndpoint(LLMCopilotSettings s) throws Exception {
        // Use override path if configured
        String override = s.getClaudeCodeApiPath().trim();
        if (!override.isEmpty()) {
            String host = extractHost(s.getBaseUrl());
            int port = extractPort(s.getBaseUrl(), 3000);
            boolean isAnthro = override.contains("messages");
            return new ClaudeEndpoint(port, override, isAnthro);
        }

        // Return cache if base URL hasn't changed
        if (cachedEndpoint.get() != null && cachedBaseUrl.equals(s.getBaseUrl())) {
            return cachedEndpoint.get();
        }

        String host = extractHost(s.getBaseUrl());
        int configuredPort = extractPort(s.getBaseUrl(), 3000);
        String model = s.getModel().isEmpty() ? "claude-opus-4-5" : s.getModel();

        // Try configured port first
        for (Object[] c : CLAUDE_CANDIDATES) {
            int port = (int)c[0];
            if (port != configuredPort) continue;
            String path = (String)c[1]; boolean isAnthro = (boolean)c[2];
            if (probeClaudeEndpoint(host, port, path, isAnthro, model)) {
                ClaudeEndpoint ep = new ClaudeEndpoint(port, path, isAnthro);
                cachedEndpoint.set(ep); cachedBaseUrl = s.getBaseUrl();
                return ep;
            }
        }

        // Try all other candidate ports
        for (Object[] c : CLAUDE_CANDIDATES) {
            int port = (int)c[0];
            if (port == configuredPort) continue;
            String path = (String)c[1]; boolean isAnthro = (boolean)c[2];
            if (probeClaudeEndpoint(host, port, path, isAnthro, model)) {
                ClaudeEndpoint ep = new ClaudeEndpoint(port, path, isAnthro);
                cachedEndpoint.set(ep); cachedBaseUrl = s.getBaseUrl();
                return ep;
            }
        }

        throw new Exception(
            "Claude Code proxy not found on " + host + ". " +
            "Tried ports: 3000, 3456, 8000, 4141, 8082, 8080, 1234. " +
            "Run 'LLM Copilot: Diagnose Claude Code' or set claudeCodeApiPath manually."
        );
    }

    private static boolean probeClaudeEndpoint(String host, int port, String path, boolean isAnthro, String model) {
        try {
            String url  = "http://" + host + ":" + port + path;
            String body = buildClaudeBody(List.of(new ChatMessage("user","Reply OK.")), 16, model, isAnthro);
            Map<String,String> hdrs = isAnthro ? Map.of("anthropic-version","2023-06-01") : Map.of();
            // Use short 1s timeout for probes — 9 candidates × 5s = 45s freeze otherwise
            String raw = httpPostWithTimeout(url, body, hdrs, 1_000, 5_000);
            JsonObject j = GSON.fromJson(raw, JsonObject.class);
            return j.has("content") || j.has("choices");
        } catch (Exception e) {
            return false;
        }
    }

    // ── Body builders ─────────────────────────────────────────────────────────

    private static String buildOpenAIBody(List<ChatMessage> messages, int maxTokens, String model, double temp) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        if (temp > 0) body.addProperty("temperature", temp);
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject o = new JsonObject(); o.addProperty("role", m.role()); o.addProperty("content", m.content());
            msgs.add(o);
        }
        body.add("messages", msgs);
        return GSON.toJson(body);
    }

    private static String buildAnthropicBody(List<ChatMessage> messages, int maxTokens, String model, double temp) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.isEmpty() ? "claude-3-5-sonnet-20241022" : model);
        body.addProperty("max_tokens", maxTokens);
        // System message as content block array (strict Anthropic format)
        StringBuilder sys = new StringBuilder();
        for (ChatMessage m : messages) { if ("system".equals(m.role())) sys.append(m.content()).append("\n"); }
        if (sys.length() > 0) {
            JsonArray sysArr = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", sys.toString().trim());
            sysArr.add(block);
            body.add("system", sysArr);
        }
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            if ("system".equals(m.role())) continue;
            JsonObject o = new JsonObject(); o.addProperty("role", m.role()); o.addProperty("content", m.content());
            msgs.add(o);
        }
        body.add("messages", msgs);
        return GSON.toJson(body);
    }

    /** Build a request body that works for both Anthropic and OpenAI endpoints */
    private static String buildClaudeBody(List<ChatMessage> messages, int maxTokens, String model, boolean isAnthropic) {
        if (isAnthropic) {
            return buildAnthropicBody(messages, maxTokens,
                model.isEmpty() ? "claude-opus-4-5" : model, 0.2);
        } else {
            return buildOpenAIBody(messages, maxTokens,
                model.isEmpty() ? "claude-opus-4-5" : model, 0.2);
        }
    }

    // ── Response parsers ──────────────────────────────────────────────────────

    private static String extractOpenAIContent(String raw) {
        JsonObject j = GSON.fromJson(raw, JsonObject.class);
        return j.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
    }

    private static String extractContent(String raw) {
        JsonObject j = GSON.fromJson(raw, JsonObject.class);
        if (j.has("content")) {
            JsonElement ce = j.get("content");
            if (ce.isJsonArray()) return ce.getAsJsonArray().get(0).getAsJsonObject().get("text").getAsString();
            return ce.getAsString();
        }
        if (j.has("choices")) return extractOpenAIContent(raw);
        return "";
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /** Probe-specific POST with configurable timeouts */
    private static String httpPostWithTimeout(String urlStr, String jsonBody,
                                              Map<String,String> extraHeaders,
                                              int connectMs, int readMs) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        for (Map.Entry<String,String> e : extraHeaders.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
        try (OutputStream os = conn.getOutputStream()) { os.write(jsonBody.getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        if (code >= 400) throw new Exception("HTTP " + code + ": " + body);
        return body;
    }

    public static String httpPost(String urlStr, String jsonBody, Map<String,String> extraHeaders) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        for (Map.Entry<String,String> e : extraHeaders.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        if (code >= 400) throw new Exception("HTTP " + code + ": " + body);
        return body;
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        int code = conn.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String extractHost(String baseUrl) {
        try { return new URI(baseUrl).getHost(); } catch (Exception e) { return "localhost"; }
    }

    private static int extractPort(String baseUrl, int defaultPort) {
        try {
            int p = new URI(baseUrl).getPort();
            return p > 0 ? p : defaultPort;
        } catch (Exception e) { return defaultPort; }
    }
}
