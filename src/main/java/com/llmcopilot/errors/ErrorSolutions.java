package com.llmcopilot.errors;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the shortlist of candidate fixes back out of the model's reply.
 *
 * <p>Models drift between numbered lists, bullets and bolded headings no matter
 * how firmly the format is specified, so all three are accepted. A title that
 * runs long is split at its first sentence and the remainder pushed into the
 * detail, because the pane shows the title on one line.
 *
 * <p>Mirrors {@code parseSolutions} in {@code errorAssist.ts}.
 */
public final class ErrorSolutions {

    private ErrorSolutions() { }

    /**
     * One candidate fix. {@code file} and {@code line} are where the model
     * said the edit lands, and {@code confidence} how sure it said it was;
     * all three are optional, because a model that ignores the format loses
     * the jump-to-the-edit entry in the pane and nothing else.
     */
    public record Solution(String title, String detail, String file, int line, String confidence) {
        public Solution(String title, String detail) { this(title, detail, "", 0, ""); }

        public boolean hasTarget() { return file != null && !file.isEmpty(); }
    }

    /** {@code 1.} / {@code 2)} / {@code - } / {@code Fix 3:} — every way a model numbers a list. */
    private static final Pattern ITEM_START = Pattern.compile(
        "^\\s*(?:\\d+[.)]|[-*•]|(?:solution|fix|option|cause)\\s*\\d*\\s*[:.])\\s*(.+)$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern BOLD_HEADING = Pattern.compile("^\\*\\*(.+?)\\*\\*:?$");
    private static final Pattern HASH_HEADING = Pattern.compile("^#+\\s+(.+)$");

    /** The point in an over-long title where it can be cut. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.;:] ");

    /** {@code [path/to/File.java:42]} — where the fix lands. */
    private static final Pattern LOCATION = Pattern.compile("\\[([^\\]\\s]+?)(?::(\\d+))?\\]");
    /** {@code (likely)} — how sure the model said it was. */
    private static final Pattern CONFIDENCE = Pattern.compile(
        "\\((likely|possible|unlikely)\\)", Pattern.CASE_INSENSITIVE);
    /** A path has a separator in it; plain bracketed prose does not. */
    private static final Pattern LOOKS_LIKE_PATH = Pattern.compile("[./\\\\]");

    private static final int MAX_TITLE = 80;

    public static List<Solution> parse(String raw, int limit) {
        List<String[]> collected = new ArrayList<>();   // [title, detail]
        String[] current = null;

        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("```")) continue;

            // A heading on its own line is a title. Checked before the bullet
            // forms, whose leading `*` would otherwise eat the first star of a
            // bolded one.
            String title = firstGroup(BOLD_HEADING, text);
            if (title == null) title = firstGroup(HASH_HEADING, text);
            if (title == null) title = firstGroup(ITEM_START, text);

            if (title != null) {
                current = new String[]{ cleanTitle(title), "" };
                if (!current[0].isEmpty()) collected.add(current);
                continue;
            }

            if (current != null) {
                String prose = cleanProse(text);
                current[1] = current[1].isEmpty() ? prose : current[1] + " " + prose;
            }
        }

        List<Solution> solutions = new ArrayList<>();
        for (String[] entry : collected) {
            if (entry[0].isEmpty()) continue;
            Solution target = readTarget(entry[0]);
            Solution shortened = shorten(target.title(), entry[1]);
            solutions.add(new Solution(shortened.title(), shortened.detail(),
                target.file(), target.line(), target.confidence()));
            if (solutions.size() >= limit) break;
        }
        return solutions;
    }

    /**
     * Pull the {@code [file:line]} and {@code (likely)} the solutions prompt
     * asks for out of a title, leaving the title itself readable.
     */
    public static Solution readTarget(String rawTitle) {
        String title = rawTitle;
        String file = "";
        int line = 0;
        String confidence = "";

        Matcher located = LOCATION.matcher(title);
        if (located.find() && LOOKS_LIKE_PATH.matcher(located.group(1)).find()) {
            file = located.group(1);
            line = located.group(2) == null ? 0 : Integer.parseInt(located.group(2));
            title = title.replace(located.group(0), "").trim();
        }

        Matcher sureness = CONFIDENCE.matcher(title);
        if (sureness.find()) {
            confidence = sureness.group(1).toLowerCase(java.util.Locale.ROOT);
            title = title.replace(sureness.group(0), "").trim();
        }

        title = title.replaceAll("[\\s\u2014\u2013-]+$", "").trim();
        return new Solution(title, "", file, line, confidence);
    }

    /** Moves the tail of an over-long title into its detail. */
    private static Solution shorten(String title, String detail) {
        if (title.length() <= MAX_TITLE) return new Solution(title, detail);

        Matcher end = SENTENCE_END.matcher(title);
        boolean atSentence = end.find() && end.start() > 20;
        int at = atSentence ? end.start() : MAX_TITLE - 3;

        String overflow = title.substring(Math.min(at + 1, title.length())).trim();
        String head = title.substring(0, at).trim() + (atSentence ? "" : "…");
        String merged = overflow.isEmpty() ? detail : (overflow + " " + detail).trim();
        return new Solution(head, merged);
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Markdown a picker shows as literal characters rather than as formatting. */
    private static String cleanProse(String text) {
        return text.replace("**", "").replace("`", "").trim();
    }

    private static String cleanTitle(String text) {
        return cleanProse(text.replaceAll("^#+\\s*", "")).replaceAll("[:\\s]+$", "").trim();
    }
}
