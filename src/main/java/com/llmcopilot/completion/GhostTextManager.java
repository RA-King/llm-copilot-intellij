package com.llmcopilot.completion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * GhostTextManager – central authority for all ghost-text state.
 *
 * One instance per editor (held in LLMEditorFactoryListener).
 * Responsibilities:
 *  1. Show/dismiss ghost text inlays
 *  2. Track current suggestion (full text + insertion offset)
 *  3. Advance ghost text on matching keystrokes (partial accept on one char)
 *  4. Dismiss on non-matching keystrokes
 *  5. Accept full suggestion on Tab
 */
@SuppressWarnings({"rawtypes","unchecked"})
public class GhostTextManager implements DocumentListener {

    private final Editor editor;

    // Current ghost text state (null = nothing shown)
    private volatile String currentSuggestion = null;   // full multi-line text
    private volatile int    suggestionOffset   = -1;    // doc offset where text starts
    private volatile boolean ignoreNextChange  = false; // set while we apply acceptance

    public GhostTextManager(Editor editor) {
        this.editor = editor;
        editor.getDocument().addDocumentListener(this);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Show multi-line ghost text starting at caretOffset. */
    public void showSuggestion(String fullText, int caretOffset) {
        if (editor.isDisposed() || fullText == null || fullText.isBlank()) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed()) return;
            dismissAllInlays();

            Document doc = editor.getDocument();
            if (doc.getTextLength() == 0 || doc.getLineCount() == 0) return;
            if (caretOffset < 0 || caretOffset > doc.getTextLength()) return;

            currentSuggestion = fullText;
            suggestionOffset  = caretOffset;

            String[] lines = fullText.split("\n", -1);
            InlayModel inlay = editor.getInlayModel();
            int caretLine = doc.getLineNumber(caretOffset);
            int lineEnd = doc.getLineEndOffset(caretLine);

            // The first line of the suggestion belongs at the end of the
            // caret's own line, where the author is typing.
            inlay.addAfterLineEndElement(
                lineEnd, true, new LLMInlineElement(lines[0], caretOffset, true));

            // Everything after it goes in ONE block below that line.
            //
            // Hanging each continuation line off the end of the next physical
            // line — which is what this did before — writes the suggestion
            // across code the author has already written, so a five-line
            // suggestion appeared smeared over five unrelated lines of real
            // code. A block element takes space of its own instead: what is
            // below is pushed down while the suggestion is on screen and
            // springs back when it is dismissed, which is how the IDE's own
            // previews behave.
            List<String> rest = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                // A trailing newline is formatting, not a line to render.
                if (i == lines.length - 1 && lines[i].isEmpty()) continue;
                rest.add(lines[i]);
            }

            if (!rest.isEmpty()) {
                inlay.addBlockElement(
                    lineEnd,
                    /* relatesToPrecedingText */ true,
                    /* showAbove */ false,
                    /* priority */ 0,
                    new LLMBlockElement(rest));
            }
        });
    }

    /** Accept the current suggestion — insert full text at suggestionOffset. */
    public boolean acceptSuggestion() {
        if (editor.isDisposed()) return false;
        String text   = currentSuggestion;
        int    offset = suggestionOffset;
        if (text == null || offset < 0) return false;

        dismissAllInlays();

        Document doc = editor.getDocument();
        if (doc.getTextLength() == 0 || offset > doc.getTextLength()) return false;

        ignoreNextChange = true;
        WriteCommandAction.runWriteCommandAction(editor.getProject(),
            "LLM Copilot: Accept", null, () -> {
                if (offset <= doc.getTextLength()) {
                    doc.insertString(offset, text);
                    editor.getCaretModel().moveToOffset(
                        Math.min(offset + text.length(), doc.getTextLength()));
                }
            });
        return true;
    }

    /** Dismiss ghost text without accepting. */
    public void dismiss() {
        currentSuggestion = null;
        suggestionOffset  = -1;
        dismissAllInlays();
    }

    public boolean hasSuggestion() { return currentSuggestion != null; }

    // ── DocumentListener – smart dismiss/advance on typing ────────────────────

    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
        if (editor.isDisposed()) return;
        if (ignoreNextChange) { ignoreNextChange = false; return; }
        if (currentSuggestion == null) return;

        // Only react to single-character insertions (normal typing)
        CharSequence newFrag = event.getNewFragment();
        CharSequence oldFrag = event.getOldFragment();
        if (newFrag.length() != 1 || oldFrag.length() != 0) {
            // Paste, deletion, or replacement — dismiss
            ApplicationManager.getApplication().invokeLater(this::dismiss);
            return;
        }

        char typed = newFrag.charAt(0);
        int  changeOffset = event.getOffset();

        // If the typed character matches the next expected character of the suggestion
        if (changeOffset == suggestionOffset) {
            char expected = currentSuggestion.charAt(0);
            if (typed == expected) {
                // Advance: shrink suggestion by one char, move offset forward
                String remaining = currentSuggestion.substring(1);
                int newOffset = suggestionOffset + 1;
                if (remaining.isEmpty() || remaining.equals("\n")) {
                    ApplicationManager.getApplication().invokeLater(this::dismiss);
                } else {
                    ApplicationManager.getApplication().invokeLater(() ->
                        showSuggestion(remaining, newOffset));
                }
                return;
            }
        }

        // Typed char didn't match — dismiss
        ApplicationManager.getApplication().invokeLater(this::dismiss);
    }

    // ── Inlay cleanup ─────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dismissAllInlays() {
        if (editor.isDisposed()) return;
        InlayModel model = editor.getInlayModel();
        int len = editor.getDocument().getTextLength();
        // Remove all LLMInlineElement inlays (both after-line-end and inline)
        ((List<Inlay>) (List<?>) model.getAfterLineEndElementsInRange(0, len, LLMInlineElement.class))
            .forEach(Inlay::dispose);
        ((List<Inlay>) (List<?>) model.getInlineElementsInRange(0, len, LLMInlineElement.class))
            .forEach(Inlay::dispose);
        ((List<Inlay>) (List<?>) model.getBlockElementsInRange(0, len, LLMBlockElement.class))
            .forEach(Inlay::dispose);
    }

    public void dispose() {
        editor.getDocument().removeDocumentListener(this);
        dismiss();
    }
}
