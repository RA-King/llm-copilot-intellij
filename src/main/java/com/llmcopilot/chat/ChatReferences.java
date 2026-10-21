package com.llmcopilot.chat;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a chat message asks for beyond its words: a leading slash command, and
 * {@code #} references that pull material into the request the way Copilot's
 * chat variables do. Mirrors chatReferences.ts.
 *
 * <ul>
 *   <li>{@code #file:src/App.java} — the file, read in full (quoted paths may hold spaces)</li>
 *   <li>{@code #selection} — the editor selection</li>
 *   <li>{@code #codebase} — the parts of the project index that match the question</li>
 *   <li>{@code #problems} — the errors and warnings in the active file</li>
 *   <li>{@code #changes} — the uncommitted git diff</li>
 *   <li>{@code #terminalLastCommand} — the last failure captured from a run, debug or terminal console</li>
 * </ul>
 */
public final class ChatReferences {

    private ChatReferences() {}

    public enum Kind { FILE, SELECTION, CODEBASE, PROBLEMS, CHANGES, TERMINAL_LAST_COMMAND }

    public record Reference(Kind kind, String arg) {
        public static Reference of(Kind kind) { return new Reference(kind, null); }
    }

    /** {@code command} is the slash command without its slash, lowercased, or null. */
    public record Parsed(String command, String text, List<Reference> references) {}

    public record Attachment(String label, String language, String content) {}

    private static final Map<String, Kind> NAMED = Map.of(
        "selection", Kind.SELECTION,
        "codebase", Kind.CODEBASE,
        "problems", Kind.PROBLEMS,
        "changes", Kind.CHANGES,
        "terminallastcommand", Kind.TERMINAL_LAST_COMMAND,
        "terminal", Kind.TERMINAL_LAST_COMMAND);

    private static final Pattern SLASH = Pattern.compile("^/([\\w-]+)(?:\\s+|$)");
    private static final Pattern FILE  = Pattern.compile("(^|\\s)#file:(?:\"([^\"]+)\"|(\\S+))");
    private static final Pattern NAME  = Pattern.compile("(^|\\s)#([a-zA-Z]+)\\b");

    public static Parsed parse(String raw) {
        String text = raw.trim();
        String command = null;

        Matcher slash = SLASH.matcher(text);
        if (slash.find()) {
            command = slash.group(1).toLowerCase(Locale.ROOT);
            text = text.substring(slash.end());
        }

        List<Reference> references = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Matcher file = FILE.matcher(text);
        StringBuilder afterFiles = new StringBuilder();
        while (file.find()) {
            String path = (file.group(2) != null ? file.group(2) : file.group(3)).replaceAll("[.,;:!?)]+$", "");
            if (seen.add("FILE:" + path)) references.add(new Reference(Kind.FILE, path));
            file.appendReplacement(afterFiles, Matcher.quoteReplacement(file.group(1) + path));
        }
        file.appendTail(afterFiles);

        Matcher name = NAME.matcher(afterFiles.toString());
        StringBuilder afterNames = new StringBuilder();
        while (name.find()) {
            Kind kind = NAMED.get(name.group(2).toLowerCase(Locale.ROOT));
            if (kind == null) {
                name.appendReplacement(afterNames, Matcher.quoteReplacement(name.group()));
                continue;
            }
            if (seen.add(kind.name())) references.add(Reference.of(kind));
            name.appendReplacement(afterNames, Matcher.quoteReplacement(name.group(1)));
        }
        name.appendTail(afterNames);

        return new Parsed(command, afterNames.toString().replaceAll("[ \\t]{2,}", " ").trim(), references);
    }

    /** Attachments as the block that follows the question in the user message. */
    public static String render(List<Attachment> attachments) {
        StringJoiner out = new StringJoiner("\n\n");
        for (Attachment a : attachments) {
            if (a.content().isBlank()) continue;
            if (a.language().isEmpty()) {
                out.add("### " + a.label() + "\n" + a.content().trim());
            } else {
                out.add("### " + a.label() + "\n```" + a.language() + "\n" + a.content().stripTrailing() + "\n```");
            }
        }
        return out.toString();
    }

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "be", "it",
        "this", "that", "with", "how", "what", "where", "why", "when", "does", "do", "can", "i", "we",
        "my", "our", "me", "you", "there", "which", "who", "from", "by", "as", "at", "into", "about",
        "code", "file", "files", "function", "functions", "class", "method", "project", "codebase",
        "use", "used", "using", "make", "get", "set", "all", "any", "some", "show", "find", "explain");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * The words in a question worth looking up in the index: identifiers as
     * written, plus the parts of any camelCase or snake_case name.
     */
    public static List<String> searchTerms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = IDENTIFIER.matcher(question);
        while (m.find()) {
            String token = m.group();
            if (token.length() < 3 || STOP_WORDS.contains(token.toLowerCase(Locale.ROOT))) continue;
            terms.add(token);
            for (String part : token.split("_|(?<=[a-z0-9])(?=[A-Z])")) {
                String lower = part.toLowerCase(Locale.ROOT);
                if (part.length() >= 3 && !STOP_WORDS.contains(lower)) terms.add(lower);
            }
        }
        return terms.stream().limit(16).toList();
    }
}
