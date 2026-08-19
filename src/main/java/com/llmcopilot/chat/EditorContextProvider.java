package com.llmcopilot.chat;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.llmcopilot.util.LanguageUtils;

/**
 * Provides real-time editor context — selected code, surrounding context,
 * current file name and language — for inclusion in chat prompts.
 */
public class EditorContextProvider {

    public static class EditorContext {
        public final String  selectedText;
        public final String  surroundingContext; // 40 lines around cursor
        public final String  fileName;
        public final String  language;
        public final boolean hasSelection;
        public final int     selectionStart;
        public final int     selectionEnd;

        public EditorContext(String selectedText, String surroundingContext,
                             String fileName, String language,
                             boolean hasSelection, int selectionStart, int selectionEnd) {
            this.selectedText      = selectedText;
            this.surroundingContext= surroundingContext;
            this.fileName          = fileName;
            this.language          = language;
            this.hasSelection      = hasSelection;
            this.selectionStart    = selectionStart;
            this.selectionEnd      = selectionEnd;
        }

        public static EditorContext EMPTY = new EditorContext("", "", "", "text", false, 0, 0);
    }

    /** Get context from the currently active editor in the given project. */
    public static EditorContext getActive(Project project) {
        if (project == null) return EditorContext.EMPTY;

        FileEditor fe = FileEditorManager.getInstance(project).getSelectedEditor();
        if (!(fe instanceof TextEditor te)) return EditorContext.EMPTY;
        Editor editor = te.getEditor();

        Document doc = editor.getDocument();
        SelectionModel sel = editor.getSelectionModel();

        String selected = (sel.hasSelection() && sel.getSelectedText() != null)
            ? sel.getSelectedText() : "";

        // 40 lines of surrounding context centred on the cursor
        int caretLine = editor.getCaretModel().getLogicalPosition().line;
        int startLine = Math.max(0, caretLine - 20);
        int endLine   = Math.min(doc.getLineCount() - 1, caretLine + 20);
        int startOff  = doc.getLineStartOffset(startLine);
        int endOff    = doc.getLineEndOffset(endLine);
        String surrounding = doc.getText().substring(startOff, Math.min(endOff, doc.getTextLength()));

        VirtualFile vf = te.getFile();
        String fileName = vf != null ? vf.getName() : "untitled";
        String language = LanguageUtils.getLanguageId(editor);

        return new EditorContext(
            selected, surrounding, fileName, language,
            sel.hasSelection(), sel.getSelectionStart(), sel.getSelectionEnd()
        );
    }

    /**
     * Replace the selection (or insert at caret) in the active editor.
     * Called after user accepts a proposed code change.
     */
    public static void applyToEditor(Project project, String newCode, EditorContext ctx) {
        if (project == null || project.isDisposed() || newCode == null) return;
        FileEditor fe = FileEditorManager.getInstance(project).getSelectedEditor();
        if (!(fe instanceof TextEditor te)) return;
        Editor editor = te.getEditor();

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project,
            "LLM Copilot: Apply Change", null, () -> {
                Document doc = editor.getDocument();
                if (ctx.hasSelection) {
                    // Replace selection
                    int start = Math.min(ctx.selectionStart, doc.getTextLength());
                    int end   = Math.min(ctx.selectionEnd,   doc.getTextLength());
                    doc.replaceString(start, end, newCode);
                    editor.getCaretModel().moveToOffset(Math.min(start + newCode.length(), doc.getTextLength()));
                } else {
                    // Insert at caret
                    int offset = Math.min(editor.getCaretModel().getOffset(), doc.getTextLength());
                    doc.insertString(offset, newCode);
                    editor.getCaretModel().moveToOffset(Math.min(offset + newCode.length(), doc.getTextLength()));
                }
            });
    }
}
