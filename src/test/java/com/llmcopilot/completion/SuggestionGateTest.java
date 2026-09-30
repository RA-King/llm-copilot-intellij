package com.llmcopilot.completion;

import com.llmcopilot.completion.SuggestionGate.Input;
import com.llmcopilot.completion.SuggestionGate.Limits;
import com.llmcopilot.completion.SuggestionGate.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the rules that decide whether ghost text may appear at all. */
class SuggestionGateTest {

    private static Input at(String prefix, String suffix) {
        return new Input("java", prefix, suffix, "    void total(List<Item> items) {",
            IntentInference.Shape.STATEMENT, true, true, false, Limits.DEFAULTS);
    }

    private static Input with(Input base, IntentInference.Shape shape) {
        return new Input(base.language(), base.linePrefix(), base.lineSuffix(),
            base.previousLine(), shape, base.singleEmptyCaret(), base.typingForward(),
            base.recentlyDismissed(), base.limits());
    }

    // ── Whether ─────────────────────────────────────────────────────────────

    @Test void allowsASuggestionAtTheEndOfALineBeingTyped() {
        Verdict verdict = SuggestionGate.evaluate(at("        int total = ", ""));
        assertTrue(verdict.show());
        assertEquals(3, verdict.maxLines());
    }

    @Test void refusesToWriteOverCodeThatFollowsTheCaret() {
        assertFalse(SuggestionGate.evaluate(at("        int total = ", "items.size();")).show());
    }

    @Test void allowsClosingDelimitersAfterTheCaret() {
        // Finishing an argument list from inside its own brackets is the
        // normal case, not an intrusion.
        assertTrue(SuggestionGate.evaluate(at("        total(", ");")).show());
    }

    @Test void staysQuietWhileTheAuthorIsDeleting() {
        Input in = at("        int total = ", "");
        Input deleting = new Input(in.language(), in.linePrefix(), in.lineSuffix(),
            in.previousLine(), in.shape(), true, false, false, in.limits());
        assertFalse(SuggestionGate.evaluate(deleting).show());
    }

    @Test void staysQuietWithASelectionActive() {
        Input in = at("        int total = ", "");
        Input selecting = new Input(in.language(), in.linePrefix(), in.lineSuffix(),
            in.previousLine(), in.shape(), false, true, false, in.limits());
        assertFalse(SuggestionGate.evaluate(selecting).show());
    }

    @Test void staysQuietInsideAString() {
        assertFalse(SuggestionGate.evaluate(at("        String m = \"hello ", "")).show());
    }

    @Test void staysQuietInsideAComment() {
        assertFalse(SuggestionGate.evaluate(at("        // work out the ", "")).show());
    }

    @Test void staysQuietAfterADismissal() {
        Input in = at("        int total = ", "");
        Input dismissed = new Input(in.language(), in.linePrefix(), in.lineSuffix(),
            in.previousLine(), in.shape(), true, true, true, in.limits());
        assertFalse(SuggestionGate.evaluate(dismissed).show());
    }

    @Test void leavesABareMemberAccessToTheIdesOwnCompletionList() {
        assertFalse(SuggestionGate.evaluate(at("        order.", "")).show());
        assertFalse(SuggestionGate.evaluate(at("        order.f", "")).show());
        assertTrue(SuggestionGate.evaluate(at("        order.fin", "")).show());
    }

    // ── How much ────────────────────────────────────────────────────────────

    @Test void givesAnExpressionOneLine() {
        Verdict verdict = SuggestionGate.evaluate(
            with(at("        int total = ", ""), IntentInference.Shape.EXPRESSION));
        assertTrue(verdict.show());
        assertEquals(1, verdict.maxLines());
    }

    @Test void demotesABlockUnlessOneWasJustOpened() {
        Input notOpened = new Input("java", "        ", "", "        int a = 1;",
            IntentInference.Shape.BLOCK, true, true, false, Limits.DEFAULTS);
        Verdict demoted = SuggestionGate.evaluate(notOpened);
        assertEquals(IntentInference.Shape.STATEMENT, demoted.shape());
        assertEquals(3, demoted.maxLines());

        Verdict opened = SuggestionGate.evaluate(
            with(at("        ", ""), IntentInference.Shape.BLOCK));
        assertEquals(IntentInference.Shape.BLOCK, opened.shape());
        assertEquals(12, opened.maxLines());
    }

    @Test void honoursTheConfiguredCeilings() {
        Input in = new Input("java", "        ", "", "    void total() {",
            IntentInference.Shape.BLOCK, true, true, false, new Limits(2, 4, 2));
        assertEquals(4, SuggestionGate.evaluate(in).maxLines());
    }

    // ── Reading the line ────────────────────────────────────────────────────

    @Test void seesAnOpenQuote() {
        assertTrue(SuggestionGate.inStringOrComment("String a = \"abc", "java"));
        assertFalse(SuggestionGate.inStringOrComment("String a = \"abc\";", "java"));
    }

    @Test void ignoresAnEscapedQuote() {
        assertFalse(SuggestionGate.inStringOrComment("String a = \"say \\\"hi\\\"\";", "java"));
    }

    @Test void readsTheLanguagesOwnCommentMarker() {
        assertTrue(SuggestionGate.inStringOrComment("x = 1  # set ", "python"));
        assertFalse(SuggestionGate.inStringOrComment("x = 1  # set ", "java"));
    }

    @Test void seesAnUnterminatedBlockComment() {
        assertTrue(SuggestionGate.inStringOrComment("/* note ", "java"));
        assertFalse(SuggestionGate.inStringOrComment("/* note */ int a", "java"));
    }

    @Test void doesNotTreatAUrlInAStringAsAComment() {
        assertFalse(SuggestionGate.inStringOrComment("String u = \"https://example.com\";", "java"));
    }

    @Test void readsTheIdentifierBeingTyped() {
        assertEquals("fet", SuggestionGate.trailingIdentifier("  int fet"));
        assertEquals("", SuggestionGate.trailingIdentifier("  repo."));
    }

    @Test void noticesAMemberAccess() {
        assertTrue(SuggestionGate.afterMemberAccess("  repo.fin", "fin"));
        assertTrue(SuggestionGate.afterMemberAccess("  repo::fin", "fin"));
        assertFalse(SuggestionGate.afterMemberAccess("  int fin", "fin"));
    }

    @Test void readsWhatOpensABlock() {
        assertTrue(SuggestionGate.opensBlock("void f() {", "java"));
        assertFalse(SuggestionGate.opensBlock("int a = 1;", "java"));
        assertTrue(SuggestionGate.opensBlock("def f():", "python"));
        assertFalse(SuggestionGate.opensBlock("a = 1", "python"));
    }

    // ── Trimming ────────────────────────────────────────────────────────────

    @Test void leavesAShortSuggestionAlone() {
        assertEquals("a\nb", SuggestionGate.trimToBudget("a\nb", 3));
    }

    @Test void cutsAtTheLastBalancedLine() {
        String text = "int a = 1;\nint b = 2;\nif (a > 0) {\n  b();\n}";
        assertEquals("int a = 1;\nint b = 2;", SuggestionGate.trimToBudget(text, 3));
    }

    @Test void refusesWhenNothingInsideTheBudgetIsBalanced() {
        assertNull(SuggestionGate.trimToBudget("if (a) {\n  b();\n  c();\n}", 2));
    }

    @Test void ignoresBracesInsideStrings() {
        String text = "String a = \"{\";\nint b = 2;\nint c = 3;";
        assertEquals("String a = \"{\";\nint b = 2;", SuggestionGate.trimToBudget(text, 2));
    }
}
