package com.llmcopilot.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a streamed HTTP body into text deltas. Lines are buffered until
 * complete; each provider family then wraps its tokens differently. Mirrors
 * streamDecoder.ts.
 *
 * <ul>
 *   <li>SSE — OpenAI-compatible and Anthropic: {@code data: {...}} lines</li>
 *   <li>NDJSON — Ollama: one JSON object per line</li>
 * </ul>
 */
public final class StreamDecoder {

    public enum Framing { SSE, NDJSON }
    public enum Dialect { OPENAI, ANTHROPIC, OLLAMA }

    /** {@code error} is set when the provider reported a failure mid-stream. */
    public record Delta(String text, boolean done, String error) {
        static Delta of(String text, boolean done) { return new Delta(text, done, null); }
    }

    private final Framing framing;
    private final StringBuilder pending = new StringBuilder();

    public StreamDecoder(Framing framing) { this.framing = framing; }

    /** Complete payloads in this chunk; a trailing partial line is held back. */
    public List<String> push(String chunk) {
        pending.append(chunk);
        List<String> out = new ArrayList<>();
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            String payload = unframe(pending.substring(0, newline));
            pending.delete(0, newline + 1);
            if (payload != null) out.add(payload);
        }
        return out;
    }

    /** Whatever was left without a newline when the stream ended. */
    public List<String> flush() {
        String payload = unframe(pending.toString());
        pending.setLength(0);
        return payload == null ? List.of() : List.of(payload);
    }

    private String unframe(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        if (framing == Framing.NDJSON) return trimmed;
        if (!trimmed.startsWith("data:")) return null;
        return trimmed.substring(5).trim();
    }

    public static Delta read(String payload, Dialect dialect) {
        if ("[DONE]".equals(payload)) return Delta.of("", true);
        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) return Delta.of("", false);
            json = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return Delta.of("", false);
        }

        if (json.has("error") && !json.get("error").isJsonNull()) {
            JsonElement error = json.get("error");
            String message = error.isJsonObject() && error.getAsJsonObject().has("message")
                ? error.getAsJsonObject().get("message").getAsString()
                : error.isJsonPrimitive() ? error.getAsString() : error.toString();
            return new Delta("", true, message);
        }

        return switch (dialect) {
            case ANTHROPIC -> {
                String type = string(json, "type");
                if ("content_block_delta".equals(type) && json.has("delta")) {
                    yield Delta.of(string(json.getAsJsonObject("delta"), "text"), false);
                }
                yield Delta.of("", "message_stop".equals(type));
            }
            case OLLAMA -> {
                String text = json.has("message") ? string(json.getAsJsonObject("message"), "content") : string(json, "response");
                yield Delta.of(text, json.has("done") && json.get("done").getAsBoolean());
            }
            case OPENAI -> {
                String text = "";
                if (json.has("choices") && json.getAsJsonArray("choices").size() > 0) {
                    JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                    if (choice.has("delta")) text = string(choice.getAsJsonObject("delta"), "content");
                }
                yield Delta.of(text, false);
            }
        };
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
