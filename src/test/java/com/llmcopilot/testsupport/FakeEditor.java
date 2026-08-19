package com.llmcopilot.testsupport;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorSettings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds lightweight {@link Editor} stubs backed by a plain string, so editor-aware
 * logic can be exercised without starting the IntelliJ platform.
 *
 * Only the handful of {@link Document} methods the production code actually calls
 * are implemented; line offsets follow the platform convention where a line's end
 * offset excludes its terminating newline.
 */
public final class FakeEditor {

    private FakeEditor() {
    }

    /** Editor over {@code text} using 4-space indentation. */
    public static Editor withText(String text) {
        return withText(text, false, 4);
    }

    public static Editor withText(String text, boolean useTabs, int tabSize) {
        // Both collaborators are fully built before the editor is stubbed — creating a
        // mock inside an in-progress when(...) confuses the mocking engine.
        Document doc = document(text);

        EditorSettings settings = mock(EditorSettings.class);
        when(settings.isUseTabCharacter(any())).thenReturn(useTabs);
        when(settings.getTabSize(any())).thenReturn(tabSize);

        Editor editor = mock(Editor.class);
        when(editor.getSettings()).thenReturn(settings);
        when(editor.getDocument()).thenReturn(doc);
        return editor;
    }

    /** Editor whose indentation settings are stubbed but whose document is empty. */
    public static Editor withIndentSettings(boolean useTabs, int tabSize) {
        return withText("", useTabs, tabSize);
    }

    /** An editor plus the caret offset that a {@code |} marker denoted in the source text. */
    public record Fixture(Editor editor, int offset) {
    }

    /**
     * Builds a fixture from text containing a single {@code |} caret marker.
     * The marker is removed before the document is created.
     */
    public static Fixture withCaret(String textWithCaret) {
        int offset = textWithCaret.indexOf('|');
        if (offset < 0) {
            throw new IllegalArgumentException("fixture text needs a | caret marker");
        }
        String text = textWithCaret.substring(0, offset) + textWithCaret.substring(offset + 1);
        return new Fixture(withText(text), offset);
    }

    public static Document document(String text) {
        String[] parts = text.split("\n", -1);
        int lineCount = parts.length;
        int[] start = new int[lineCount];
        int[] end = new int[lineCount];
        int offset = 0;
        for (int i = 0; i < lineCount; i++) {
            start[i] = offset;
            end[i] = offset + parts[i].length();
            offset = end[i] + 1; // skip the newline
        }

        Document doc = mock(Document.class);
        when(doc.getTextLength()).thenReturn(text.length());
        when(doc.getText()).thenReturn(text);
        when(doc.getCharsSequence()).thenReturn(text);
        when(doc.getLineCount()).thenReturn(lineCount);
        when(doc.getLineStartOffset(anyInt()))
            .thenAnswer(in -> start[clamp(in.getArgument(0), lineCount)]);
        when(doc.getLineEndOffset(anyInt()))
            .thenAnswer(in -> end[clamp(in.getArgument(0), lineCount)]);
        when(doc.getLineNumber(anyInt()))
            .thenAnswer(in -> lineOf(in.getArgument(0), end, text.length()));
        return doc;
    }

    private static int clamp(int line, int lineCount) {
        return Math.max(0, Math.min(line, lineCount - 1));
    }

    private static int lineOf(int rawOffset, int[] end, int textLength) {
        int offset = Math.max(0, Math.min(rawOffset, textLength));
        for (int i = 0; i < end.length; i++) {
            if (offset <= end[i]) return i;
        }
        return end.length - 1;
    }
}
