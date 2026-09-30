package com.llmcopilot.completion;

import com.llmcopilot.completion.GhostTextPacing.AdaptiveDebounce;
import com.llmcopilot.completion.GhostTextPacing.ContinuationCache;
import com.llmcopilot.completion.GhostTextPacing.DismissalMemory;
import com.llmcopilot.completion.GhostTextPacing.TypingTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the state that paces ghost text: refusals, typing, latency, reuse. */
class GhostTextPacingTest {

    // ── Dismissal memory ────────────────────────────────────────────────────

    @Test void holdsADismissalUntilTheAuthorTypesPastIt() {
        DismissalMemory memory = new DismissalMemory(30_000, 4);
        memory.record("Total.java", 3, "        int t");

        assertTrue(memory.isDismissed("Total.java", 3, "        int t"));
        assertTrue(memory.isDismissed("Total.java", 3, "        int to"));
        assertFalse(memory.isDismissed("Total.java", 3, "        int total"));
    }

    @Test void releasesWhenTheLineIsRewrittenRatherThanExtended() {
        DismissalMemory memory = new DismissalMemory();
        memory.record("Total.java", 3, "        int t");
        assertFalse(memory.isDismissed("Total.java", 3, "        var x"));
    }

    @Test void isScopedToTheLineAndTheFile() {
        DismissalMemory memory = new DismissalMemory();
        memory.record("Total.java", 3, "        int t");
        assertFalse(memory.isDismissed("Total.java", 4, "        int t"));
        assertFalse(memory.isDismissed("Other.java", 3, "        int t"));
    }

    @Test void expires() {
        DismissalMemory memory = new DismissalMemory(0, 6);
        memory.record("Total.java", 3, "        int t");
        assertFalse(memory.isDismissed("Total.java", 3, "        int t"));
    }

    // ── Typing state ────────────────────────────────────────────────────────

    @Test void isForwardWhileInserting() {
        TypingTracker tracker = new TypingTracker();
        tracker.note(1, 0);
        assertTrue(tracker.isTypingForward());
    }

    @Test void isNotForwardWhileDeleting() {
        TypingTracker tracker = new TypingTracker();
        tracker.note(1, 0);
        tracker.note(0, 1);
        assertFalse(tracker.isTypingForward());
    }

    @Test void isNotForwardOnAReplacement() {
        TypingTracker tracker = new TypingTracker();
        tracker.note(4, 9);
        assertFalse(tracker.isTypingForward());
    }

    @Test void treatsAnExplicitInvokeAsIntent() {
        TypingTracker tracker = new TypingTracker();
        tracker.note(0, 1);
        tracker.noteExplicitInvoke();
        assertTrue(tracker.isTypingForward());
    }

    // ── Adaptive debounce ───────────────────────────────────────────────────

    @Test void waitsTheFullCeilingBeforeItHasMeasuredAnything() {
        assertEquals(600, new AdaptiveDebounce(150, 600).currentMs());
    }

    @Test void dropsToTheFloorForAFastModel() {
        AdaptiveDebounce pacing = new AdaptiveDebounce(150, 600);
        for (long ms : new long[]{ 90, 110, 100, 95 }) pacing.observe(ms);
        assertEquals(150, pacing.currentMs());
    }

    @Test void staysAtTheCeilingForASlowOne() {
        AdaptiveDebounce pacing = new AdaptiveDebounce(150, 600);
        for (long ms : new long[]{ 1800, 2000, 1900, 2100 }) pacing.observe(ms);
        assertEquals(600, pacing.currentMs());
    }

    @Test void sitsInBetweenForAMiddlingOne() {
        AdaptiveDebounce pacing = new AdaptiveDebounce(150, 600);
        for (long ms : new long[]{ 300, 320, 310, 305 }) pacing.observe(ms);
        int wait = pacing.currentMs();
        assertTrue(wait > 150 && wait < 600, "expected a wait between the floor and ceiling, got " + wait);
    }

    @Test void ignoresNonsenseMeasurements() {
        AdaptiveDebounce pacing = new AdaptiveDebounce(150, 600);
        pacing.observe(-1);
        pacing.observe(0);
        assertEquals(0, pacing.medianMs());
    }

    // ── Continuation cache ──────────────────────────────────────────────────

    @Test void returnsTheRemainderWhenTheAuthorTypesThroughASuggestion() {
        ContinuationCache cache = new ContinuationCache();
        cache.remember("Total.java", 2, "        int t", "otal = items.size();");

        assertEquals("tal = items.size();", cache.continuation("Total.java", 2, "        int to"));
        assertEquals(" = items.size();", cache.continuation("Total.java", 2, "        int total"));
    }

    @Test void givesTheWholeSuggestionBackWhenNothingHasBeenTypedSince() {
        ContinuationCache cache = new ContinuationCache();
        cache.remember("Total.java", 2, "        int t", "otal = 0;");
        assertEquals("otal = 0;", cache.continuation("Total.java", 2, "        int t"));
    }

    @Test void forgetsTheMomentTheAuthorDiverges() {
        ContinuationCache cache = new ContinuationCache();
        cache.remember("Total.java", 2, "        int t", "otal = 0;");
        assertNull(cache.continuation("Total.java", 2, "        int x"));
        assertNull(cache.continuation("Total.java", 2, "        int to"));
    }

    @Test void doesNotAnswerForAnotherLineOrAnotherFile() {
        ContinuationCache cache = new ContinuationCache();
        cache.remember("Total.java", 2, "        int t", "otal = 0;");
        assertNull(cache.continuation("Total.java", 3, "        int t"));
        assertNull(cache.continuation("Other.java", 2, "        int t"));
    }

    @Test void stopsOnceTheSuggestionHasBeenTypedOutInFull() {
        ContinuationCache cache = new ContinuationCache();
        cache.remember("Total.java", 2, "        int t", "otal");
        assertNull(cache.continuation("Total.java", 2, "        int total"));
    }
}
