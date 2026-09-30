package com.llmcopilot.errors;

import com.llmcopilot.errors.ErrorSolutions.Solution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for reading the shortlist of candidate fixes back out of a reply. */
class ErrorSolutionsTest {

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }

    @Test void readsTheNumberedListThePromptAsksFor() {
        List<Solution> solutions = ErrorSolutions.parse(lines(
            "1. Guard the basket before totalling",
            "   `basket` is undefined when the cart is empty, so `.items` throws.",
            "   Return 0 early when there is nothing in it.",
            "2. Load the basket before calling total",
            "   The fetch on line 30 is not awaited, so total runs on a promise."
        ), 6);

        assertEquals(2, solutions.size());
        assertEquals("Guard the basket before totalling", solutions.get(0).title());
        assertEquals("basket is undefined when the cart is empty, so .items throws. "
                   + "Return 0 early when there is nothing in it.", solutions.get(0).detail());
        assertEquals("Load the basket before calling total", solutions.get(1).title());
    }

    @Test void readsBulletsAndBoldedHeadingsWhichModelsProduceAnyway() {
        List<Solution> solutions = ErrorSolutions.parse(lines(
            "- **Await the fetch**",
            "  The call returns a promise.",
            "* Check the config path",
            "  The file is read relative to the process directory.",
            "**Rebuild the native module**",
            "The binary was built for another runtime version."
        ), 6);

        assertEquals(List.of("Await the fetch", "Check the config path", "Rebuild the native module"),
            solutions.stream().map(Solution::title).toList());
    }

    @Test void readsAFixStyleList() {
        List<Solution> solutions = ErrorSolutions.parse(
            lines("Fix 1: Pin the dependency", "It resolved to a new major.", "Fix 2: Clear the cache"), 6);

        assertEquals(List.of("Pin the dependency", "Clear the cache"),
            solutions.stream().map(Solution::title).toList());
    }

    @Test void movesTheTailOfAnOverLongTitleIntoTheDetail() {
        List<Solution> solutions = ErrorSolutions.parse(
            "1. The basket is undefined because the fetch on line 30 is never awaited. "
          + "Add await, or return early when the cart is empty.", 6);

        assertEquals(1, solutions.size());
        assertEquals("The basket is undefined because the fetch on line 30 is never awaited",
            solutions.get(0).title());
        assertTrue(solutions.get(0).detail().contains("Add await"));
    }

    @Test void skipsCodeFencesWhichBelongInTheAnswerRatherThanTheList() {
        List<Solution> solutions = ErrorSolutions.parse(lines(
            "1. Await the fetch",
            "```java",
            "var basket = load().get();",
            "```",
            "2. Return early"
        ), 6);

        assertEquals(List.of("Await the fetch", "Return early"),
            solutions.stream().map(Solution::title).toList());
    }

    @Test void honoursTheLimitItIsGiven() {
        assertEquals(2, ErrorSolutions.parse(lines("1. One", "2. Two", "3. Three", "4. Four"), 2).size());
    }

    @Test void returnsNothingForAReplyWithNoListInIt() {
        assertTrue(ErrorSolutions.parse("", 4).isEmpty());
        assertTrue(ErrorSolutions.parse("I could not work out what went wrong.", 4).isEmpty());
    }

    // ── Where the fix lands ──────────────────────────────────

    @Test void carriesTheFileAndLineAFixNamesThroughToThePane() {
        List<Solution> solutions = ErrorSolutions.parse(lines(
            "1. Await the basket fetch [src/cart/Total.java:30] (likely)",
            "   The call returns a future.",
            "2. Widen the type [src/cart/Types.java] (possible)"
        ), 6);

        Solution first = solutions.get(0);
        assertEquals("Await the basket fetch", first.title());
        assertEquals("src/cart/Total.java", first.file());
        assertEquals(30, first.line());
        assertEquals("likely", first.confidence());
        assertTrue(first.hasTarget());

        Solution second = solutions.get(1);
        assertEquals("src/cart/Types.java", second.file());
        assertEquals(0, second.line());
        assertEquals("possible", second.confidence());
    }

    @Test void leavesAPlainTitleAlone() {
        Solution read = ErrorSolutions.readTarget("Await the fetch");
        assertEquals("Await the fetch", read.title());
        assertEquals("", read.file());
        assertFalse(read.hasTarget());
    }

    @Test void ignoresBracketsThatAreNotAPath() {
        Solution read = ErrorSolutions.readTarget("Handle the [empty] case");
        assertEquals("Handle the [empty] case", read.title());
        assertEquals("", read.file());
    }

    @Test void takesTheConfidenceWithoutALocation() {
        Solution read = ErrorSolutions.readTarget("Rebuild the native module (unlikely)");
        assertEquals("Rebuild the native module", read.title());
        assertEquals("unlikely", read.confidence());
    }

    @Test void stripsTheSeparatorAModelLeavesBehind() {
        assertEquals("Pin the dependency",
            ErrorSolutions.readTarget("Pin the dependency — [pom.xml:12]").title());
    }
}
