package com.llmcopilot.review;

import com.llmcopilot.review.CodeReview.Finding;
import com.llmcopilot.review.CodeReview.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for review prompts and reading findings back. Mirrors codeReview.test.ts. */
class CodeReviewTest {

    @Test void numbersFromTheGivenLinePadded() {
        assertEquals(" 9 | a\n10 | b", CodeReview.numberLines("a\nb", 9));
    }

    @Test void givesEverySurvivingLineItsNewNumber() {
        String diff = String.join("\n",
            "diff --git a/src/a.ts b/src/a.ts",
            "index 1..2 100644",
            "--- a/src/a.ts",
            "+++ b/src/a.ts",
            "@@ -10,3 +10,3 @@ function f() {",
            " keep",
            "-old",
            "+new",
            " tail");
        assertEquals(String.join("\n",
            "=== src/a.ts",
            "   …",
            "   10   keep",
            "      - old",
            "   11 + new",
            "   12   tail"), CodeReview.annotateDiff(diff));
    }

    @Test void leavesOutDeletedFiles() {
        String diff = "diff --git a/x b/x\ndeleted file mode 100644\n--- a/x\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone";
        assertEquals("   …\n      - gone", CodeReview.annotateDiff(diff));
    }

    @Test void readsTheRequestedFormatAndItsCommonVariations() {
        List<Finding> findings = CodeReview.parseFindings(String.join("\n",
            "src/a.ts:12: error: Promise is not awaited.",
            "- `src/b.py:3: warning: Bare except hides errors.`",
            "40: suggestion: Name this constant.",
            "[src/c.go:7] - info - Consider a context timeout.",
            "Overall the change looks fine."));
        assertEquals(List.of(
            new Finding("src/a.ts", 12, Severity.ERROR, "Promise is not awaited."),
            new Finding("src/b.py", 3, Severity.WARNING, "Bare except hides errors."),
            new Finding(null, 40, Severity.INFO, "Name this constant."),
            new Finding("src/c.go", 7, Severity.INFO, "Consider a context timeout.")), findings);
    }

    @Test void findsNothingInNoIssues() {
        assertEquals(List.of(), CodeReview.parseFindings("NO ISSUES"));
    }
}
