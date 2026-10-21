package com.llmcopilot.completion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.llmcopilot.chat.CustomInstructions;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.settings.LLMCopilotSettings;
import com.llmcopilot.util.LanguageUtils;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Next edit suggestions in one editor. After an edit and a pause, the recent
 * edits are sent off and the predicted follow-up is drawn where it belongs:
 * the text it replaces struck through, the new text beside it. Tab jumps to a
 * suggestion elsewhere in the file and a second Tab applies it; Escape drops
 * it. Applying one is itself an edit, so a rename walks through the file one
 * Tab at a time. Mirrors nextEditController.ts.
 */
@SuppressWarnings({"rawtypes", "RedundantSuppression"})
public final class NextEditManager implements DocumentListener, CaretListener {

    /** Lines either side of the caret shown to the model. */
    private static final int WINDOW_LINES = 60;
    /** Files larger than this are not worth the round trip. */
    private static final int MAX_FILE_CHARS = 400_000;

    private static final ScheduledExecutorService DEBOUNCE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llm-next-edit"); t.setDaemon(true); return t;
    });

    private static final Color REMOVED_BG = new JBColor(new Color(255, 220, 220), new Color(90, 40, 40));
    private static final Color INSERTED_BG = new JBColor(new Color(220, 255, 220), new Color(40, 80, 40));

    private record Suggestion(long stamp, NextEdit.Located edit) {}

    private final Editor editor;
    private final GhostTextManager ghost;
    private final EditHistory history = new EditHistory();
    private final AtomicInteger generation = new AtomicInteger();
    private ScheduledFuture<?> pending;

    private Suggestion current;
    private RangeHighlighter highlighter;
    private Inlay inserted;
    private Inlay hint;

    public NextEditManager(Editor editor, GhostTextManager ghost) {
        this.editor = editor;
        this.ghost = ghost;
        history.reset(editor.getDocument().getText());
        editor.getDocument().addDocumentListener(this);
        editor.getCaretModel().addCaretListener(this);
    }

    public boolean hasSuggestion() { return current != null; }

    // ── Listening ────────────────────────────────────────────────────────────

    @Override
    public void documentChanged(@NotNull DocumentEvent e) {
        history.apply(List.of(new EditHistory.TextChange(e.getOffset(), e.getOldLength(), e.getNewFragment().toString())));
        Document doc = e.getDocument();
        if (history.current().length() != doc.getTextLength()) history.reset(doc.getText());

        dismiss();
        Project project = editor.getProject();
        if (project != null && UndoManager.getInstance(project).isUndoOrRedoInProgress()) return;
        schedule();
    }

    @Override
    public void caretPositionChanged(@NotNull CaretEvent event) {
        if (current != null) refreshHint();
    }

    private void schedule() {
        if (pending != null) pending.cancel(false);
        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
        if (!s.isEnabled() || !s.isNextEditEnabled()) return;
        int gen = generation.incrementAndGet();
        pending = DEBOUNCE.schedule(() -> predict(gen), s.getNextEditDebounceMs(), TimeUnit.MILLISECONDS);
    }

    // ── Predicting ───────────────────────────────────────────────────────────

    private record Snapshot(String text, long stamp, int caret, int first, int caretLine, String window,
                            int windowOffset, String language, String file, String base) {}

    private void predict(int gen) {
        if (editor.isDisposed() || history.edits().isEmpty()) return;

        Snapshot snap = ReadAction.compute(() -> {
            Document doc = editor.getDocument();
            if (doc.getTextLength() > MAX_FILE_CHARS) return null;
            int caret = editor.getCaretModel().getOffset();
            int line = doc.getLineNumber(caret);
            int first = Math.max(0, line - WINDOW_LINES);
            int last = Math.min(doc.getLineCount() - 1, line + WINDOW_LINES);
            int from = doc.getLineStartOffset(first);
            String text = doc.getText();
            VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
            Project project = editor.getProject();
            String base = project != null ? project.getBasePath() : null;
            String file = vf == null ? "untitled"
                : base != null && vf.getPath().startsWith(base + "/") ? vf.getPath().substring(base.length() + 1) : vf.getName();
            return new Snapshot(text, doc.getModificationStamp(), caret, first, line,
                text.substring(from, doc.getLineEndOffset(last)), from, LanguageUtils.getLanguageId(editor), file, base);
        });
        if (snap == null) return;

        String reply;
        try {
            List<LLMClient.ChatMessage> messages = NextEdit.prompt(snap.language(), snap.file(), history.render(),
                snap.window(), snap.first() + 1, snap.caretLine() + 1);
            reply = LLMClient.chat(CustomInstructions.withInstructions(messages,
                CustomInstructions.forFile(snap.base(), snap.file())), LLMClient.CHAT_MAX_TOKENS);
        } catch (Exception e) {
            return;   // a failed prediction is simply no prediction
        }

        NextEdit.SearchReplace parsed = NextEdit.parse(reply);
        if (parsed == null || NextEdit.revertsLastEdit(parsed, history.last())) return;

        // The model was shown the window, so the quote is looked for there first;
        // the whole file is the fallback for an edit just outside it.
        NextEdit.Located inWindow = NextEdit.locate(snap.window(), parsed, snap.caret() - snap.windowOffset());
        NextEdit.Located located = inWindow != null
            ? new NextEdit.Located(inWindow.start() + snap.windowOffset(), inWindow.end() + snap.windowOffset(), inWindow.replace())
            : NextEdit.locate(snap.text(), parsed, snap.caret());
        if (located == null) return;
        NextEdit.Located edit = NextEdit.trim(snap.text(), located);

        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed() || gen != generation.get()) return;
            if (editor.getDocument().getModificationStamp() != snap.stamp()) return;
            if (ghost != null && ghost.hasSuggestion()) return;   // ghost text has the floor
            show(new Suggestion(snap.stamp(), edit));
        });
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private void show(Suggestion s) {
        dismiss();
        current = s;
        NextEdit.Located edit = s.edit();

        if (edit.end() > edit.start()) {
            TextAttributes struck = new TextAttributes();
            struck.setBackgroundColor(REMOVED_BG);
            struck.setEffectType(EffectType.STRIKEOUT);
            struck.setEffectColor(JBColor.GRAY);
            highlighter = editor.getMarkupModel().addRangeHighlighter(edit.start(), edit.end(),
                HighlighterLayer.SELECTION - 1, struck, HighlighterTargetArea.EXACT_RANGE);
        }

        if (!edit.replace().isEmpty()) {
            String[] lines = edit.replace().split("\n", -1);
            String shown = lines[0] + (lines.length > 1
                ? "  … +" + (lines.length - 1) + " line" + (lines.length > 2 ? "s" : "") : "");
            inserted = editor.getInlayModel().addInlineElement(edit.end(), true, new Label(shown, INSERTED_BG));
        }
        refreshHint();
    }

    private boolean far() {
        Document doc = editor.getDocument();
        int caretLine = doc.getLineNumber(editor.getCaretModel().getOffset());
        int startLine = doc.getLineNumber(Math.min(current.edit().start(), doc.getTextLength()));
        int endLine = doc.getLineNumber(Math.min(current.edit().end(), doc.getTextLength()));
        return caretLine < startLine - 1 || caretLine > endLine + 1;
    }

    private void refreshHint() {
        if (hint != null) { hint.dispose(); hint = null; }
        if (current == null || !far()) return;
        int line = editor.getDocument().getLineNumber(current.edit().start()) + 1;
        hint = editor.getInlayModel().addAfterLineEndElement(editor.getCaretModel().getOffset(), true,
            new Label("   ⇥ Tab: next edit at line " + line, null));
    }

    // ── Tab and Escape ───────────────────────────────────────────────────────

    /** Tab: go to the suggestion if it is elsewhere, apply it if the caret is already there. */
    public void acceptOrJump() {
        Suggestion s = current;
        if (s == null) return;
        if (editor.getDocument().getModificationStamp() != s.stamp()) { dismiss(); return; }

        if (far()) {
            editor.getCaretModel().moveToOffset(s.edit().start());
            editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
            refreshHint();
            return;
        }

        dismiss();
        NextEdit.Located edit = s.edit();
        WriteCommandAction.runWriteCommandAction(editor.getProject(), "LLM Copilot: Next Edit", null, () -> {
            editor.getDocument().replaceString(edit.start(), edit.end(), edit.replace());
            editor.getCaretModel().moveToOffset(edit.start() + edit.replace().length());
        });
    }

    public void dismiss() {
        generation.incrementAndGet();
        current = null;
        if (highlighter != null) { editor.getMarkupModel().removeHighlighter(highlighter); highlighter = null; }
        if (inserted != null) { inserted.dispose(); inserted = null; }
        if (hint != null) { hint.dispose(); hint = null; }
    }

    public void dispose() {
        if (pending != null) pending.cancel(false);
        dismiss();
        editor.getDocument().removeDocumentListener(this);
        editor.getCaretModel().removeCaretListener(this);
    }

    /** Italic ghost-coloured text, optionally on a tinted background. */
    private static final class Label implements EditorCustomElementRenderer {
        private final String text;
        private final Color background;

        Label(String text, Color background) {
            this.text = text;
            this.background = background;
        }

        private static Font font(Editor ed) {
            return ed.getColorsScheme().getFont(EditorFontType.PLAIN).deriveFont(Font.ITALIC);
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            return inlay.getEditor().getContentComponent().getFontMetrics(font(inlay.getEditor())).stringWidth(text) + 6;
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle r, @NotNull TextAttributes attributes) {
            Editor ed = inlay.getEditor();
            if (background != null) {
                g.setColor(background);
                g.fillRect(r.x, r.y, r.width, r.height);
            }
            g.setFont(font(ed));
            g.setColor(JBColor.GRAY);
            g.drawString(text, r.x + 3, r.y + ed.getAscent());
        }
    }
}
