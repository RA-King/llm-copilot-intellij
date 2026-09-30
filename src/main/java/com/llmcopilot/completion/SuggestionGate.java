package com.llmcopilot.completion;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one place that answers "may ghost text appear here, and how much of it".
 *
 * <p>Ghost text is intrusive by construction: it is unasked-for text on the
 * screen, in the middle of writing, and it changes the document if accepted.
 * Everything that makes it tolerable is a refusal — it does not show over code
 * the author has already written, it does not show mid-word, it does not show
 * again the moment after it was dismissed, and it does not run on past the
 * thought it was completing.
 *
 * <p>The rules, in the order they are applied:
 *
 * <ol>
 *   <li>Nothing while text is selected, or with more than one caret.</li>
 *   <li>Nothing when the last edit was not the author typing forward at the
 *       caret — deletions, undo and paste all suppress.</li>
 *   <li>Nothing when real code follows the caret on the line. Closing
 *       delimiters and whitespace do not count, because finishing an argument
 *       list inside its own brackets is the normal case.</li>
 *   <li>Nothing inside a string literal or a comment.</li>
 *   <li>Nothing until an identifier being typed is long enough to be worth
 *       guessing at, and nothing straight after a member-access dot, where the
 *       IDE's own completion list is the better answer.</li>
 *   <li>Nothing on a line the author has already dismissed a suggestion on,
 *       until they have typed enough to make it a different question.</li>
 *   <li>Block-sized suggestions only where a block genuinely belongs.</li>
 * </ol>
 *
 * <p>Pure: the caller passes facts, the gate returns a verdict. Mirrors
 * {@code suggestionGate.ts}.
 */
public final class SuggestionGate {

    private SuggestionGate() { }

    // ── Verdict ──────────────────────────────────────────────────────────────

    public record Limits(int statementLines, int blockLines, int minIdentifierChars) {
        public static final Limits DEFAULTS = new Limits(3, 12, 2);
    }

    public record Input(
        String language,
        String linePrefix,
        String lineSuffix,
        String previousLine,
        IntentInference.Shape shape,
        boolean singleEmptyCaret,
        boolean typingForward,
        boolean recentlyDismissed,
        Limits limits
    ) { }

    /** {@code show} false carries the reason, for the log; true carries the budget. */
    public record Verdict(boolean show, String reason, IntentInference.Shape shape, int maxLines) {
        static Verdict no(String reason) {
            return new Verdict(false, reason, IntentInference.Shape.STATEMENT, 0);
        }
        static Verdict yes(IntentInference.Shape shape, int maxLines) {
            return new Verdict(true, "", shape, maxLines);
        }
    }

    /** What may follow the caret without the suggestion being intrusive. */
    private static final Pattern HARMLESS_SUFFIX = Pattern.compile("^[\\s)\\]}>,;:'\"`]*$");
    private static final Pattern TRAILING_IDENTIFIER = Pattern.compile("([A-Za-z_$][\\w$]*)$");
    private static final Pattern MEMBER_ACCESS = Pattern.compile("(?:\\.|->|::|\\?\\.)\\s*$");

    // ── The gate ─────────────────────────────────────────────────────────────

    public static Verdict evaluate(Input in) {
        if (!in.singleEmptyCaret()) return Verdict.no("a selection or a second caret is active");
        if (!in.typingForward())    return Verdict.no("the last edit was not forward typing");

        // Rule 3 — the author's own text after the caret is never written over.
        if (!HARMLESS_SUFFIX.matcher(in.lineSuffix()).matches()) {
            return Verdict.no("code follows the caret on this line");
        }

        // Rule 4 — an open quote or an unterminated comment means the caret is
        // inside prose, where a code completion is noise.
        if (inStringOrComment(in.linePrefix(), in.language())) {
            return Verdict.no("the caret is inside a string or a comment");
        }

        if (in.recentlyDismissed()) return Verdict.no("a suggestion was dismissed here");

        // Rule 5 — leave the short prefixes to the IDE's own completion list,
        // which is instant, exact, and already on screen.
        String identifier = trailingIdentifier(in.linePrefix());
        boolean afterMember = afterMemberAccess(in.linePrefix(), identifier);
        int minimum = in.limits().minIdentifierChars();

        if (afterMember && identifier.length() < minimum) {
            return Verdict.no("the IDE's own completion list covers this");
        }
        if (!afterMember && !identifier.isEmpty() && identifier.length() < minimum
            && in.linePrefix().trim().equals(identifier)) {
            return Verdict.no("too little typed to guess from");
        }

        // Rule 7 — a block belongs only where one was just opened. Anywhere
        // else, a block-sized answer is the model writing the rest of the
        // function.
        IntentInference.Shape shape = in.shape();
        if (shape == IntentInference.Shape.BLOCK && !opensBlock(in.previousLine(), in.language())) {
            shape = IntentInference.Shape.STATEMENT;
        }

        return Verdict.yes(shape, lineBudget(shape, in.limits()));
    }

    public static int lineBudget(IntentInference.Shape shape, Limits limits) {
        return switch (shape) {
            case EXPRESSION -> 1;
            case STATEMENT  -> Math.max(1, limits.statementLines());
            case BLOCK      -> Math.max(1, limits.blockLines());
        };
    }

    // ── Reading the line ─────────────────────────────────────────────────────

    private static final Map<String, String> LINE_COMMENT = Map.of(
        "python", "#", "ruby", "#", "shell", "#", "yaml", "#", "perl", "#",
        "lua", "--", "sql", "--", "haskell", "--");

    /**
     * Whether the caret sits inside a string or a comment, judged from the line
     * prefix alone. A multi-line string reads as closed and a multi-line
     * comment as absent, both of which fail open — the suggestion still has to
     * pass the duplicate guard downstream.
     */
    public static boolean inStringOrComment(String prefix, String language) {
        String lineComment = LINE_COMMENT.getOrDefault(language, "//");
        char quote = 0;
        boolean blockComment = false;

        for (int i = 0; i < prefix.length(); i++) {
            char ch = prefix.charAt(i);

            if (blockComment) {
                if (prefix.startsWith("*/", i)) { blockComment = false; i++; }
                continue;
            }

            if (quote != 0) {
                if (ch == '\\') { i++; continue; }
                if (ch == quote) quote = 0;
                continue;
            }

            if (ch == '"' || ch == '\'' || ch == '`') { quote = ch; continue; }
            if (prefix.startsWith("/*", i)) { blockComment = true; i++; continue; }
            if (prefix.startsWith(lineComment, i)) return true;
            if ("python".equals(language) && ch == '#') return true;
        }

        return quote != 0 || blockComment;
    }

    /** The identifier the caret is in the middle of; empty after punctuation. */
    public static String trailingIdentifier(String prefix) {
        var m = TRAILING_IDENTIFIER.matcher(prefix);
        return m.find() ? m.group(1) : "";
    }

    public static boolean afterMemberAccess(String prefix, String identifier) {
        String before = prefix.substring(0, prefix.length() - identifier.length());
        return MEMBER_ACCESS.matcher(before).find();
    }

    /** Does the line above open a body the caret is now at the top of? */
    public static boolean opensBlock(String previousLine, String language) {
        String line = previousLine == null ? "" : previousLine.trim();
        if (line.isEmpty()) return false;

        char last = line.charAt(line.length() - 1);
        if (last == '{' || last == '(' || last == '[') return true;

        return switch (language) {
            case "python", "yaml" -> last == ':';
            case "ruby" -> line.endsWith("do") || line.endsWith("then")
                || line.startsWith("def ") || line.startsWith("class ") || line.startsWith("module ");
            default -> false;
        };
    }

    // ── Trimming to the budget ───────────────────────────────────────────────

    /**
     * Cut a suggestion to {@code maxLines} without leaving it unbalanced.
     * Truncating mid-block would produce text that cannot be accepted, so the
     * cut is taken at the last line where everything opened inside the snippet
     * is closed again. Returns null when there is no such line inside the
     * budget — half a block is worse than nothing.
     */
    public static String trimToBudget(String text, int maxLines) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) return text;

        int depth = 0;
        int lastBalanced = -1;

        for (int i = 0; i < Math.min(lines.length, maxLines); i++) {
            depth += delimiterDelta(lines[i]);
            if (depth <= 0) lastBalanced = i;
        }

        if (lastBalanced < 0) return null;

        StringBuilder out = new StringBuilder();
        for (int i = 0; i <= lastBalanced; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString().stripTrailing();
    }

    private static int delimiterDelta(String line) {
        int delta = 0;
        char quote = 0;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote != 0) {
                if (ch == '\\') i++;
                else if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') { quote = ch; continue; }
            if (ch == '{' || ch == '(' || ch == '[') delta++;
            else if (ch == '}' || ch == ')' || ch == ']') delta--;
        }
        return delta;
    }
}
