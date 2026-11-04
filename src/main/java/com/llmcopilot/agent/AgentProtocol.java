package com.llmcopilot.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.llmcopilot.services.LLMClient.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conversation between the agent loop and the model. Tool calls are written
 * as a tagged block of JSON rather than through a provider's native tool
 * calling, so the same agent works with every provider — including a local
 * model that has never heard of function calling. Mirrors agentProtocol.ts.
 *
 * <pre>
 * Reading the config to see how retries are set up.
 * &lt;tool name="read_file"&gt;
 * {"path": "src/Config.java"}
 * &lt;/tool&gt;
 * </pre>
 *
 * One call per reply; the result comes back as the next user message. A reply
 * with no call is the final answer.
 */
public final class AgentProtocol {

    private AgentProtocol() {}

    /** Argument names ending in {@code ?} are optional. */
    public record ToolSpec(String name, String description, Map<String, String> args) {}

    public record ToolCall(String name, JsonObject args) {}

    /** {@code error} is set when a block was there but could not be read. */
    public record Reply(String text, ToolCall call, String error) {}

    private static Map<String, String> args(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    public static final List<ToolSpec> TOOLS = List.of(
        new ToolSpec("list_files", "List files in the workspace matching a glob.",
            args("pattern?", "glob, default \"**/*\"")),
        new ToolSpec("read_file", "Read a file, with line numbers. Long files come back in parts; ask for the lines you need.",
            args("path", "workspace-relative path", "start_line?", "first line, 1-based", "end_line?", "last line, inclusive")),
        new ToolSpec("search_text", "Search file contents. Returns path:line: text for each match.",
            args("query", "text to find", "regex?", "true to treat query as a regular expression", "include?", "glob limiting the files searched")),
        new ToolSpec("find_symbol", "Find where a class, function, type or constant is declared.",
            args("name", "the identifier")),
        new ToolSpec("edit_file", "Replace one exact passage of a file. search must match the file exactly, once; include enough surrounding lines to make it unique.",
            args("path", "workspace-relative path", "search", "the exact text to replace", "replace", "the new text")),
        new ToolSpec("create_file", "Create a file, or overwrite one completely.",
            args("path", "workspace-relative path", "content", "the whole file")),
        new ToolSpec("get_problems", "Errors and warnings the IDE reports, for one file or every file the agent has touched.",
            args("path?", "workspace-relative path; omit for every file changed so far")),
        new ToolSpec("run_command", "Run a shell command in the workspace root and return its output and exit code. The user must approve each command.",
            args("command", "the command line")));

    public static String systemPrompt(String environment) {
        StringBuilder tools = new StringBuilder();
        for (ToolSpec t : TOOLS) {
            tools.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
            t.args().forEach((k, v) -> tools.append("    ").append(k).append(": ").append(v).append('\n'));
        }
        return "You are an autonomous coding agent working inside the user's project. You complete the task you are given " +
            "by calling tools, one at a time, and reading what they return.\n\n" +
            environment + "\n\n" +
            "Tools:\n" + tools.toString().stripTrailing() + "\n\n" +
            "To call a tool, write one short sentence saying what you are doing and why, then exactly one block:\n" +
            "<tool name=\"TOOL_NAME\">\n{\"arg\": \"value\"}\n</tool>\n" +
            "The arguments are a JSON object. Stop after the block; the result arrives in the next message.\n\n" +
            "Work like a careful engineer: find and read the relevant code before changing it, make the smallest change " +
            "that does the job, follow the conventions already in the code, and verify — check the problems in files " +
            "you edited, and run the tests or build when there is one. Never edit a file you have not read. Do not ask " +
            "the user questions you can answer by looking.\n\n" +
            "When the task is done, or cannot be done, reply without a tool block: a short summary of what you changed " +
            "and why, and anything the user should check.";
    }

    private static final Pattern TAGGED = Pattern.compile(
        "<tool\\s+name\\s*=\\s*[\"']?([\\w-]+)[\"']?\\s*>([\\s\\S]*?)(?:</tool>|$)");
    private static final Pattern FENCED = Pattern.compile(
        "```(?:json)?\\s*\\n(\\{[\\s\\S]*?\"tool\"\\s*:[\\s\\S]*?})\\s*\\n```");

    public static Reply parse(String reply) {
        Matcher tagged = TAGGED.matcher(reply);
        if (tagged.find()) {
            String text = reply.substring(0, tagged.start()).trim();
            String body = tagged.group(2).trim()
                .replaceFirst("^```(?:json)?\\s*\\n?", "").replaceFirst("\\n?```$", "").trim();
            try {
                JsonElement args = body.isEmpty() ? new JsonObject() : JsonParser.parseString(body);
                if (!args.isJsonObject()) throw new IllegalArgumentException("arguments must be a JSON object");
                return new Reply(text, new ToolCall(tagged.group(1), args.getAsJsonObject()), null);
            } catch (RuntimeException e) {
                return new Reply(text, null, "The arguments to " + tagged.group(1) + " are not valid JSON ("
                    + e.getMessage() + "). Send the call again.");
            }
        }

        // Some models fall back to a fenced {"tool": ..., "args": ...} object.
        Matcher fenced = FENCED.matcher(reply);
        if (fenced.find()) {
            try {
                JsonObject obj = JsonParser.parseString(fenced.group(1)).getAsJsonObject();
                if (obj.has("tool") && obj.get("tool").isJsonPrimitive()) {
                    JsonObject args = obj.has("args") && obj.get("args").isJsonObject() ? obj.getAsJsonObject("args")
                        : obj.has("arguments") && obj.get("arguments").isJsonObject() ? obj.getAsJsonObject("arguments")
                        : new JsonObject();
                    return new Reply(reply.substring(0, fenced.start()).trim(), new ToolCall(obj.get("tool").getAsString(), args), null);
                }
            } catch (RuntimeException ignored) { }
        }

        return new Reply(reply.trim(), null, null);
    }

    /** What the user sees of a reply: the prose, without the call block. */
    public static String visibleText(String reply) {
        Matcher m = Pattern.compile("<tool\\s+name").matcher(reply);
        return (m.find() ? reply.substring(0, m.start()) : reply).trim();
    }

    public static String stringArg(ToolCall call, String name, boolean required) {
        JsonElement value = call.args().get(name);
        if (value == null || value.isJsonNull()) {
            if (required) throw new IllegalArgumentException(call.name() + " needs \"" + name + "\"");
            return null;
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    public static Integer intArg(ToolCall call, String name) {
        JsonElement value = call.args().get(name);
        if (value == null || value.isJsonNull() || (value.isJsonPrimitive() && value.getAsString().isEmpty())) return null;
        try {
            return (int) Math.floor(Double.parseDouble(value.getAsString()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("\"" + name + "\" must be a number");
        }
    }

    /** Bounded tool output, keeping the start and the end, where errors usually are. */
    public static String truncate(String text, int max) {
        if (text.length() <= max) return text;
        int head = (int) Math.floor(max * 0.6);
        int tail = max - head;
        return text.substring(0, head) + "\n… (" + (text.length() - max) + " characters omitted) …\n"
            + text.substring(text.length() - tail);
    }

    /**
     * Keeps the conversation inside a character budget by blanking the oldest
     * tool results first. The model's own messages are kept: they record what it
     * learned from those results.
     */
    public static List<ChatMessage> compact(List<ChatMessage> messages, int budget) {
        List<ChatMessage> out = new ArrayList<>(messages);
        int size = out.stream().mapToInt(m -> m.content().length()).sum();
        // Never touch the system prompt, the task, or the latest result.
        for (int i = 2; i < out.size() - 1 && size > budget; i++) {
            ChatMessage m = out.get(i);
            if (!"user".equals(m.role()) || !m.content().startsWith("Result of ")) continue;
            String header = m.content().split("\n", 2)[0];
            String replacement = header + "\n(earlier result omitted to save space — call the tool again if you need it)";
            if (replacement.length() < m.content().length()) {
                size -= m.content().length() - replacement.length();
                out.set(i, new ChatMessage("user", replacement));
            }
        }
        return out;
    }

    /** Where an edit applies, or why it cannot: an agent edit must match exactly one place. */
    public record Match(int start, int end, String error) {}

    public static Match findUnique(String text, String search) {
        if (search.isEmpty()) return new Match(-1, -1, "search is empty");
        int first = text.indexOf(search);
        if (first >= 0) {
            if (text.indexOf(search, first + 1) >= 0) {
                int count = 0;
                for (int at = text.indexOf(search); at >= 0; at = text.indexOf(search, at + 1)) count++;
                return new Match(-1, -1, "search matches " + count + " places; include more surrounding lines so it matches one");
            }
            return new Match(first, first + search.length(), null);
        }

        // Tolerate trailing whitespace and line-ending differences, nothing more.
        String[] lines = text.split("\n", -1);
        String[] wanted = search.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < wanted.length; i++) wanted[i] = wanted[i].stripTrailing();
        int[] offsets = new int[lines.length];
        for (int i = 0, o = 0; i < lines.length; i++) { offsets[i] = o; o += lines[i].length() + 1; }

        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i + wanted.length <= lines.length; i++) {
            boolean match = true;
            for (int j = 0; j < wanted.length && match; j++) {
                match = lines[i + j].replaceFirst("\r$", "").stripTrailing().equals(wanted[j]);
            }
            if (match) hits.add(i);
        }
        if (hits.size() == 1) {
            int last = hits.get(0) + wanted.length - 1;
            return new Match(offsets[hits.get(0)], offsets[last] + lines[last].replaceFirst("\r$", "").length(), null);
        }
        if (hits.size() > 1) {
            return new Match(-1, -1, "search matches " + hits.size() + " places; include more surrounding lines so it matches one");
        }
        return new Match(-1, -1, "search text not found in the file; read the file again and quote it exactly");
    }

    /**
     * A workspace-relative path made safe: no escaping the workspace through
     * {@code ..}, no absolute paths outside it. Throws with the reason otherwise.
     */
    public static String safeRelativePath(String input, String root) {
        String p = input.trim().replace('\\', '/');
        if (root != null) {
            String r = root.replace('\\', '/').replaceFirst("/$", "");
            if (p.equals(r)) p = "";
            else if (p.startsWith(r + "/")) p = p.substring(r.length() + 1);
        }
        if (p.matches("^([a-zA-Z]:)?/.*")) throw new IllegalArgumentException(input + " is outside the workspace");
        List<String> parts = new ArrayList<>();
        for (String part : p.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IllegalArgumentException(input + " is outside the workspace");
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("a file path is needed");
        return String.join("/", parts);
    }
}
