package com.llmcopilot.completion;

import com.llmcopilot.review.CodeReview;
import com.llmcopilot.services.LLMClient.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Next edit suggestions: from the edits just made, predict the one that comes
 * next — the other uses of a renamed variable, the call site of a method that
 * just gained a parameter — anywhere in the file, not only at the caret.
 * Mirrors nextEdit.ts.
 *
 * <p>The model answers with one search/replace block. Quoting the text to change
 * rather than giving line numbers or offsets is what keeps the answer
 * applicable when the model miscounts.
 */
public final class NextEdit {

    private NextEdit() {}

    public record SearchReplace(String search, String replace) {}

    /** Offsets into the document text. */
    public record Located(int start, int end, String replace) {}

    public static List<ChatMessage> prompt(String language, String file, String history,
                                           String window, int windowStart, int cursorLine) {
        return List.of(
            new ChatMessage("system",
                "You predict the next edit a developer will make to a " + language + " file, from the edits they just made. " +
                "Look for the edit that the recent ones make necessary or obvious: the remaining uses of something " +
                "renamed, a call that must change because a signature changed, the same change applied to sibling " +
                "code, an import the new code needs. Do not repeat or undo an edit already made, and do not invent " +
                "new features.\n\n" +
                "Reply with exactly one block in this form, copying the lines to change exactly as they appear " +
                "(without the line numbers), and with enough of them to be unique in the file:\n" +
                "<<<<<<< SEARCH\n" +
                "lines as they are now\n" +
                "=======\n" +
                "the same lines after the edit\n" +
                ">>>>>>> REPLACE\n\n" +
                "If no further edit clearly follows from the recent ones, reply with exactly: NONE"),
            new ChatMessage("user",
                "File: " + file + "\n\nRecent edits, oldest first:\n" + history + "\n\n" +
                "The file around the cursor (line " + cursorLine + "):\n" + CodeReview.numberLines(window, windowStart)));
    }

    private static final Pattern BLOCK = Pattern.compile(
        "<{5,}\\s*SEARCH[^\\n]*\\n([\\s\\S]*?)\\n?={5,}[^\\n]*\\n([\\s\\S]*?)\\n?>{5,}\\s*REPLACE");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+ \\| ");

    /** The block from a reply, or null for NONE or anything unreadable. */
    public static SearchReplace parse(String reply) {
        Matcher m = BLOCK.matcher(reply.replace("\r\n", "\n"));
        if (!m.find()) return null;
        String search = stripNumbers(m.group(1));
        String replace = stripNumbers(m.group(2));
        if (search.isBlank() || search.equals(replace)) return null;
        return new SearchReplace(search, replace);
    }

    /** Numbered lines quoted back despite the instruction. */
    private static String stripNumbers(String s) {
        String[] lines = s.split("\n", -1);
        for (String l : lines) if (!l.isBlank() && !NUMBERED.matcher(l).find()) return s;
        List<String> out = new ArrayList<>();
        for (String l : lines) out.add(NUMBERED.matcher(l).replaceFirst(""));
        return String.join("\n", out);
    }

    /**
     * Where the quoted text is, choosing the occurrence nearest the caret when it
     * appears more than once. Falls back to matching with trailing whitespace
     * ignored, the most common way a model's quote differs from the file.
     */
    public static Located locate(String text, SearchReplace edit, int cursorOffset) {
        int best = -1;
        for (int at = text.indexOf(edit.search()); at >= 0; at = text.indexOf(edit.search(), at + 1)) {
            if (best < 0 || Math.abs(at - cursorOffset) < Math.abs(best - cursorOffset)) best = at;
        }
        if (best >= 0) return new Located(best, best + edit.search().length(), edit.replace());

        String[] lines = text.split("\n", -1);
        String[] wanted = edit.search().split("\n", -1);
        int[] offsets = new int[lines.length];
        for (int i = 0, o = 0; i < lines.length; i++) { offsets[i] = o; o += lines[i].length() + 1; }

        int bestLine = -1;
        for (int i = 0; i + wanted.length <= lines.length; i++) {
            boolean match = true;
            for (int j = 0; j < wanted.length && match; j++) {
                match = lines[i + j].stripTrailing().equals(wanted[j].stripTrailing());
            }
            if (match && (bestLine < 0 || Math.abs(offsets[i] - cursorOffset) < Math.abs(offsets[bestLine] - cursorOffset))) {
                bestLine = i;
            }
        }
        if (bestLine < 0) return null;
        int lastLine = bestLine + wanted.length - 1;
        return new Located(offsets[bestLine], offsets[lastLine] + lines[lastLine].length(), edit.replace());
    }

    /** True when applying the edit would put back what the last edit took away. */
    public static boolean revertsLastEdit(SearchReplace edit, EditHistory.LineEdit last) {
        if (last == null) return false;
        String before = String.join("\n", last.before());
        String after = String.join("\n", last.after());
        return !edit.search().isBlank() && !edit.replace().isBlank()
            && after.contains(edit.search().trim()) && before.contains(edit.replace().trim());
    }

    /** The edit narrowed to the characters that actually change. */
    public static Located trim(String text, Located edit) {
        String old = text.substring(edit.start(), edit.end());
        String rep = edit.replace();
        int head = 0;
        while (head < old.length() && head < rep.length() && old.charAt(head) == rep.charAt(head)) head++;
        int tail = 0;
        while (tail < old.length() - head && tail < rep.length() - head
            && old.charAt(old.length() - 1 - tail) == rep.charAt(rep.length() - 1 - tail)) tail++;
        return new Located(edit.start() + head, edit.end() - tail, rep.substring(head, rep.length() - tail));
    }
}
