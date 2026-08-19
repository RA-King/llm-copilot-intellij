package com.llmcopilot.util;

import com.intellij.openapi.editor.Editor;
import com.llmcopilot.testsupport.FakeEditor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for stripping model artefacts and re-indenting a completion to the cursor. */
class IndentUtilsTest {

    private final Editor spaces = FakeEditor.withIndentSettings(false, 4);

    @Nested
    @DisplayName("artefact stripping")
    class ArtefactStripping {

        @Test
        void markdownFencesAreRemoved() {
            assertEquals("foo();",
                IndentUtils.reindent("```java\nfoo();\n```", "", spaces));
        }

        @Test
        void fenceWithoutALanguageTagIsRemoved() {
            assertEquals("foo();",
                IndentUtils.reindent("```\nfoo();\n```", "", spaces));
        }

        @Test
        void aLeadingCompletionLabelIsRemoved() {
            assertEquals("foo();",
                IndentUtils.reindent("Completion:\nfoo();", "", spaces));
        }

        @Test
        void trailingWhitespaceIsTrimmed() {
            assertEquals("foo();",
                IndentUtils.reindent("foo();   \n\n", "", spaces));
        }

        @Test
        void inputThatIsOnlyAnArtefactCollapsesToEmpty() {
            assertEquals("", IndentUtils.reindent("```\n```", "", spaces));
            assertEquals("", IndentUtils.reindent("   ", "", spaces));
        }
    }

    @Nested
    @DisplayName("re-indentation")
    class Reindentation {

        @Test
        void firstLineIsNotIndentedBecauseTheCursorIsAlreadyThere() {
            String out = IndentUtils.reindent("foo();\nbar();", "    ", spaces);
            assertEquals("foo();\n    bar();", out);
        }

        @Test
        void relativeIndentationBetweenLinesIsPreserved() {
            String out = IndentUtils.reindent("if (x) {\n    doIt();\n}", "  ", spaces);
            assertEquals("if (x) {\n      doIt();\n  }", out);
        }

        @Test
        void commonLeadingIndentInTheModelOutputIsStrippedBeforeReindenting() {
            // Every line arrives indented by 8; that shared offset must not stack
            // on top of the cursor's own indent.
            String out = IndentUtils.reindent("        a();\n        b();", "    ", spaces);
            assertEquals("a();\n    b();", out);
        }

        @Test
        void blankLinesAreKeptWithoutTrailingIndent() {
            String out = IndentUtils.reindent("a();\n\nb();", "  ", spaces);
            assertEquals("a();\n\n  b();", out);
        }

        @Test
        void tabIndentedEditorsGetTabs() {
            Editor tabs = FakeEditor.withIndentSettings(true, 4);
            String out = IndentUtils.reindent("a();\nb();", "\t", tabs);
            assertEquals("a();\n\tb();", out);
        }

        @Test
        void tabWidthIsUsedWhenMeasuringExistingIndentation() {
            Editor tabs = FakeEditor.withIndentSettings(true, 2);
            // One tab of width 2 at the cursor -> one tab of width 2 on line two.
            String out = IndentUtils.reindent("a();\nb();", "\t", tabs);
            assertEquals("a();\n\tb();", out);
        }

        @Test
        void anUnindentedCursorLeavesTheCompletionFlush() {
            String out = IndentUtils.reindent("a();\nb();", "", spaces);
            assertEquals("a();\nb();", out);
        }
    }
}
