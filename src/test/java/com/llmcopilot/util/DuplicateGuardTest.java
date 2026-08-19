package com.llmcopilot.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural tests for the three-level duplication guard.
 *
 * Level 3 strips echoed prefixes, level 2 rejects whole-block repeats,
 * level 1 drops individual lines that already exist around the cursor.
 */
class DuplicateGuardTest {

    @Nested
    @DisplayName("rejects input that carries no new code")
    class Rejections {

        @Test
        void nullCompletionIsSuppressed() {
            assertNull(DuplicateGuard.guard(null, "prefix", "suffix", ""));
        }

        @Test
        void blankCompletionIsSuppressed() {
            assertNull(DuplicateGuard.guard("   \n\t ", "prefix", "suffix", ""));
        }

        @Test
        void completionMadeOnlyOfTrivialLinesIsSuppressed() {
            // Braces and punctuation carry no information, so nothing survives.
            assertNull(DuplicateGuard.guard("}\n);\n{", "class A {", "}", ""));
        }
    }

    @Nested
    @DisplayName("level 3 — echo stripping")
    class EchoStripping {

        @Test
        void textAlreadyTypedOnTheLineIsNotRepeated() {
            String out = DuplicateGuard.guard("System.out.println(x);", "", "", "System.out.");
            assertEquals("println(x);", out);
        }

        @Test
        void leadingLinesEchoedFromThePrefixAreRemoved() {
            String prefix = "int alpha = 1;\nint beta = 2;";
            String completion = "int alpha = 1;\nint beta = 2;\nint gamma = 3;";

            assertEquals("int gamma = 3;",
                DuplicateGuard.guard(completion, prefix, "", ""));
        }

        @Test
        void echoStrippingStopsAtTheFirstNonMatchingLine() {
            // "int zeta" is new, so nothing after it is treated as an echo.
            String out = DuplicateGuard.guard("int zeta = 9;\nint omega = 7;", "int alpha = 1;", "", "");
            assertEquals("int zeta = 9;\nint omega = 7;", out);
        }
    }

    @Nested
    @DisplayName("level 2 — whole-block duplicates")
    class BlockDuplicates {

        @Test
        void aBlockThatAlreadyExistsAboveTheCursorIsSuppressed() {
            String body = "public void run() {\n    doWork();\n}";
            assertNull(DuplicateGuard.guard(body, body + "\n", "", ""));
        }

        @Test
        void aBlockThatAlreadyExistsBelowTheCursorIsSuppressed() {
            String body = "private int computeTotalValue() {\n    return accumulator;\n}";
            assertNull(DuplicateGuard.guard(body, "", body, ""));
        }
    }

    @Nested
    @DisplayName("level 1 — per-line de-duplication")
    class LineDeduplication {

        @Test
        void novelCodeIsReturnedUnchanged() {
            String out = DuplicateGuard.guard("int total = a + b;", "class Foo {", "}", "");
            assertEquals("int total = a + b;", out);
        }

        @Test
        void completionIsSuppressedWhenMoreThanHalfTheLinesAlreadyExist() {
            String prefix = "int alpha = 1;\nint beta = 2;\nint gamma = 3;";
            String completion = "int zeta = 9;\n" + prefix;

            assertNull(DuplicateGuard.guard(completion, prefix, "", ""));
        }

        @Test
        void individualDuplicateLinesAreDroppedWhenTheMajorityIsNew() {
            String out = DuplicateGuard.guard(
                "int zeta = 9;\nint alpha = 1;\nint omega = 7;",
                "int alpha = 1;", "", "");

            assertEquals("int zeta = 9;\nint omega = 7;", out);
        }

        @Test
        void duplicateDetectionIgnoresWhitespaceDifferences() {
            // Same statements, different spacing — still recognised as duplicates.
            String out = DuplicateGuard.guard(
                "int zeta = 0;\nint  a  =  1;\nint   b   =   2;",
                "int a = 1;\nint b = 2;", "", "");

            assertNull(out);
        }

        @Test
        void trivialLinesSurviveEvenWhenTheyAlreadyExist() {
            String out = DuplicateGuard.guard(
                "// note\nint fresh = 5;\n}",
                "// note\n}", "", "");

            assertEquals("// note\nint fresh = 5;\n}", out);
        }
    }
}
