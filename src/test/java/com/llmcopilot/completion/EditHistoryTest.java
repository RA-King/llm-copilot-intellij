package com.llmcopilot.completion;

import com.llmcopilot.completion.EditHistory.TextChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the recent-edit record next edit suggestions read from. Mirrors editHistory.test.ts. */
class EditHistoryTest {

    private static final String SOURCE = "function load(user) {\n  return fetch(user.id);\n}\n\nsave(user);\n";

    /** A change replacing the first occurrence of {@code find} in the history's current text. */
    private static TextChange replace(EditHistory h, String find, String text) {
        int at = h.current().indexOf(find);
        assertTrue(at >= 0, "no " + find);
        return new TextChange(at, find.length(), text);
    }

    @Test void recordsAChangeAsWholeBeforeAndAfterLines() {
        EditHistory h = new EditHistory();
        h.reset(SOURCE);
        h.apply(List.of(replace(h, "load(user)", "load(account)")));
        EditHistory.LineEdit e = h.edits().get(0);
        assertEquals(0, e.startLine());
        assertEquals(List.of("function load(user) {"), e.before());
        assertEquals(List.of("function load(account) {"), e.after());
    }

    @Test void mergesKeystrokesInTheSamePlaceIntoOneEdit() {
        EditHistory h = new EditHistory();
        h.reset("const a = 1;\n");
        int at = 12;
        for (char c : " // one".toCharArray()) h.apply(List.of(new TextChange(at++, 0, String.valueOf(c))));
        assertEquals(1, h.edits().size());
        assertEquals(List.of("const a = 1; // one"), h.edits().get(0).after());
    }

    @Test void forgetsAnEditThatWasTypedAndThenDeleted() {
        EditHistory h = new EditHistory();
        h.reset("x\n");
        h.apply(List.of(new TextChange(1, 0, "yz")));
        h.apply(List.of(new TextChange(1, 2, "")));
        assertEquals(List.of(), h.edits());
    }

    @Test void keepsSeparatePlacesSeparateAndRendersOnlyWhatChanged() {
        EditHistory h = new EditHistory();
        h.reset(SOURCE);
        h.apply(List.of(replace(h, "load(user)", "load(account)")));
        h.apply(List.of(replace(h, "save(user)", "save(account)")));
        assertEquals(String.join("\n",
            "@@ line 1", "- function load(user) {", "+ function load(account) {",
            "@@ line 5", "- save(user);", "+ save(account);"), h.render());
    }

    @Test void mergesAnEditOnTheAdjacentLineIntoThePreviousOne() {
        EditHistory h = new EditHistory();
        h.reset("a\nb\nc\n");
        h.apply(List.of(replace(h, "a", "A")));
        h.apply(List.of(replace(h, "b", "B")));
        assertEquals(1, h.edits().size());
        assertEquals(List.of("a", "b"), h.edits().get(0).before());
        assertEquals(List.of("A", "B"), h.edits().get(0).after());
    }

    @Test void movesOlderEditsDownWhenLinesAreInsertedAboveThem() {
        EditHistory h = new EditHistory();
        h.reset("one\ntwo\nthree\nfour\nfive\n");
        h.apply(List.of(replace(h, "five", "FIVE")));
        h.apply(List.of(new TextChange(0, 0, "zero\n")));
        assertEquals(5, h.edits().stream().filter(e -> e.after().get(0).equals("FIVE")).findFirst().orElseThrow().startLine());
    }

    @Test void appliesABatchFromTheEndBackwards() {
        EditHistory h = new EditHistory();
        h.reset("aa bb aa\n");
        h.apply(List.of(new TextChange(0, 2, "cc"), new TextChange(6, 2, "cc")));
        assertEquals("cc bb cc\n", h.current());
    }

    @Test void keepsOnlyTheMostRecentEdits() {
        EditHistory h = new EditHistory(2);
        h.reset("a\n\nb\n\nc\n");
        h.apply(List.of(replace(h, "a", "A")));
        h.apply(List.of(replace(h, "b", "B")));
        h.apply(List.of(replace(h, "c", "C")));
        assertEquals(List.of("B", "C"), h.edits().stream().map(e -> e.after().get(0)).toList());
    }
}
