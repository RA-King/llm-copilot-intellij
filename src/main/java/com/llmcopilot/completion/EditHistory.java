package com.llmcopilot.completion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The edits made to one document recently, kept as before/after runs of whole
 * lines. This is what a next-edit prediction is read from: having renamed
 * {@code user} to {@code account} on one line, the next edit is very likely
 * the same rename a few lines down. Mirrors editHistory.ts.
 *
 * <p>Keystrokes in the same place merge into one entry, so typing a word is one
 * edit rather than six; an entry whose lines end up as they started — typed and
 * then deleted — disappears.
 */
public final class EditHistory {

    /** {@code rangeOffset} refers to the text as it was before this change. */
    public record TextChange(int rangeOffset, int rangeLength, String text) {}

    /** {@code startLine} is zero-based, in the document as it stands now. */
    public static final class LineEdit {
        int startLine;
        List<String> before;
        List<String> after;

        LineEdit(int startLine, List<String> before, List<String> after) {
            this.startLine = startLine;
            this.before = before;
            this.after = after;
        }

        public int startLine() { return startLine; }
        public List<String> before() { return List.copyOf(before); }
        public List<String> after() { return List.copyOf(after); }
    }

    private final int limit;
    private String text = "";
    private final List<LineEdit> entries = new ArrayList<>();

    public EditHistory() { this(6); }
    public EditHistory(int limit) { this.limit = limit; }

    /** Start over from this text, forgetting what came before. */
    public synchronized void reset(String text) {
        this.text = text;
        entries.clear();
    }

    public synchronized String current() { return text; }

    public synchronized List<LineEdit> edits() {
        List<LineEdit> copy = new ArrayList<>();
        for (LineEdit e : entries) copy.add(new LineEdit(e.startLine, new ArrayList<>(e.before), new ArrayList<>(e.after)));
        return copy;
    }

    public synchronized LineEdit last() {
        if (entries.isEmpty()) return null;
        LineEdit e = entries.get(entries.size() - 1);
        return new LineEdit(e.startLine, new ArrayList<>(e.before), new ArrayList<>(e.after));
    }

    /** Changes in one batch refer to the text before the batch, so they are applied from the end backwards. */
    public synchronized void apply(List<TextChange> changes) {
        List<TextChange> ordered = new ArrayList<>(changes);
        ordered.sort(Comparator.comparingInt(TextChange::rangeOffset).reversed());
        for (TextChange change : ordered) applyOne(change);
    }

    private static int lineAt(String text, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static List<String> lines(String text) {
        return new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    }

    private static List<String> slice(List<String> list, int from, int to) {
        from = Math.max(0, Math.min(from, list.size()));
        to = Math.max(from, Math.min(to, list.size()));
        return new ArrayList<>(list.subList(from, to));
    }

    private void applyOne(TextChange change) {
        List<String> oldLines = lines(text);
        int cs = lineAt(text, change.rangeOffset());
        int ce = lineAt(text, change.rangeOffset() + change.rangeLength());
        int added = (int) change.text().chars().filter(c -> c == '\n').count();
        int delta = added - (ce - cs);

        text = text.substring(0, change.rangeOffset()) + change.text()
            + text.substring(change.rangeOffset() + change.rangeLength());
        List<String> newLines = lines(text);

        LineEdit last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        int le = last != null ? last.startLine + last.after.size() - 1 : -2;
        if (last != null && cs <= le + 1 && ce >= last.startLine - 1) {
            // Touches the lines the last edit produced: widen that edit to cover both.
            int us = Math.min(last.startLine, cs);
            int ue = Math.max(le, ce);
            List<String> before = slice(oldLines, us, last.startLine);
            before.addAll(last.before);
            before.addAll(slice(oldLines, le + 1, ue + 1));
            last.before = before;
            last.after = slice(newLines, us, ue + delta + 1);
            last.startLine = us;
            if (last.before.equals(last.after)) entries.remove(entries.size() - 1);
            shift(ue, delta, last);
            return;
        }

        LineEdit entry = new LineEdit(cs, slice(oldLines, cs, ce + 1), slice(newLines, cs, ce + delta + 1));
        shift(ce, delta, null);
        if (entry.before.equals(entry.after)) return;
        entries.add(entry);
        if (entries.size() > limit) entries.remove(0);
    }

    /** Keep older entries pointing at the right lines after lines above them moved. */
    private void shift(int afterLine, int delta, LineEdit except) {
        if (delta == 0) return;
        for (LineEdit e : entries) if (e != except && e.startLine > afterLine) e.startLine += delta;
    }

    /** The edits as compact diffs, oldest first, with unchanged lines at either end trimmed. */
    public synchronized String render() {
        List<String> out = new ArrayList<>();
        for (LineEdit e : entries) {
            int head = 0;
            while (head < e.before.size() && head < e.after.size() && e.before.get(head).equals(e.after.get(head))) head++;
            int tail = 0;
            while (tail < e.before.size() - head && tail < e.after.size() - head
                && e.before.get(e.before.size() - 1 - tail).equals(e.after.get(e.after.size() - 1 - tail))) tail++;
            out.add("@@ line " + (e.startLine + head + 1));
            for (String l : e.before.subList(head, e.before.size() - tail)) out.add("- " + l);
            for (String l : e.after.subList(head, e.after.size() - tail)) out.add("+ " + l);
        }
        return String.join("\n", out);
    }
}
