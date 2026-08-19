package com.llmcopilot.completion;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.actionSystem.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.actionSystem.IdeActions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attaches per-editor handlers to every writable editor.
 *
 * Tab key handling:
 *   Uses EditorActionManager to override the TAB action handler globally.
 *   The override checks if ANY editor currently has ghost text; if yes it
 *   accepts the suggestion. Otherwise it falls through to IntelliJ's
 *   original TAB handler (indentation, completion list, etc.).
 *
 *   This is set up ONCE when the first editor is created (see setupTabHandler).
 *
 * Escape key handling:
 *   Overrides the EditorEscape action similarly.
 */
@SuppressWarnings({"rawtypes","unchecked"})
public class LLMEditorFactoryListener implements EditorFactoryListener {

    private static final ConcurrentHashMap<Editor, InlineCompletionHandler> HANDLERS     = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Editor, DocCommentHandler>       DOC_HANDLERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Editor, GhostTextManager>        GHOST        = new ConcurrentHashMap<>();

    private static volatile boolean tabHandlerInstalled = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        if (editor.isViewer()) return;

        // Ghost text manager (tracks suggestion, handles advance/dismiss on typing)
        GhostTextManager ghost = new GhostTextManager(editor);
        GHOST.put(editor, ghost);

        // Inline completion trigger
        InlineCompletionHandler handler = new InlineCompletionHandler(editor, ghost);
        HANDLERS.put(editor, handler);

        // Doc comment trigger
        DocCommentHandler docHandler = new DocCommentHandler(editor);
        DOC_HANDLERS.put(editor, docHandler);

        // Install Tab/Escape handlers once (global override)
        setupTabHandler();
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();

        GhostTextManager ghost = GHOST.remove(editor);
        if (ghost != null) ghost.dispose();

        InlineCompletionHandler h = HANDLERS.remove(editor);
        if (h != null) h.dispose();

        DocCommentHandler dh = DOC_HANDLERS.remove(editor);
        if (dh != null) dh.dispose();
    }

    // ── Global Tab / Escape override ──────────────────────────────────────────

    private static synchronized void setupTabHandler() {
        if (tabHandlerInstalled) return;
        tabHandlerInstalled = true;

        EditorActionManager am = EditorActionManager.getInstance();

        // ── Tab ──────────────────────────────────────────────────────────────
        EditorActionHandler originalTab = am.getActionHandler(IdeActions.ACTION_EDITOR_TAB);
        am.setActionHandler(IdeActions.ACTION_EDITOR_TAB, new EditorActionHandler() {
            @Override
            protected void doExecute(@NotNull Editor editor,
                                     @Nullable Caret caret,
                                     @NotNull DataContext ctx) {
                GhostTextManager ghost = GHOST.get(editor);
                if (ghost != null && ghost.hasSuggestion()) {
                    ghost.acceptSuggestion();
                    return; // consumed
                }
                // No ghost text — normal Tab (indent / completion list)
                originalTab.execute(editor, caret, ctx);
            }

            @Override
            public boolean isEnabledForCaret(@NotNull Editor editor,
                                             @NotNull Caret caret,
                                             DataContext ctx) {
                return true;
            }
        });

        // ── Escape ────────────────────────────────────────────────────────────
        EditorActionHandler originalEsc = am.getActionHandler(IdeActions.ACTION_EDITOR_ESCAPE);
        am.setActionHandler(IdeActions.ACTION_EDITOR_ESCAPE, new EditorActionHandler() {
            @Override
            protected void doExecute(@NotNull Editor editor,
                                     @Nullable Caret caret,
                                     @NotNull DataContext ctx) {
                GhostTextManager ghost = GHOST.get(editor);
                if (ghost != null && ghost.hasSuggestion()) {
                    ghost.dismiss();
                    return; // consumed
                }
                originalEsc.execute(editor, caret, ctx);
            }

            @Override
            public boolean isEnabledForCaret(@NotNull Editor editor,
                                             @NotNull Caret caret,
                                             DataContext ctx) {
                return true;
            }
        });
    }

    // ── Static accessors ──────────────────────────────────────────────────────

    public static InlineCompletionHandler getHandler(Editor editor) { return HANDLERS.get(editor); }
    public static GhostTextManager        getGhost(Editor editor)   { return GHOST.get(editor); }

    /** Accept ghost text in editor (used by TriggerCompletionAction). */
    public static boolean acceptSuggestion(Editor editor) {
        GhostTextManager ghost = GHOST.get(editor);
        return ghost != null && ghost.acceptSuggestion();
    }

    /** Dismiss ghost text in editor. */
    public static void dismissSuggestion(Editor editor) {
        GhostTextManager ghost = GHOST.get(editor);
        if (ghost != null) ghost.dismiss();
    }
}
