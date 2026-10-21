package com.llmcopilot.actions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.llmcopilot.settings.LLMCopilotSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * "Fix with LLM Copilot" and "Explain with LLM Copilot" in the Alt+Enter menu
 * of any error or warning the IDE is showing on the caret's line. Mirrors
 * quickFix.ts.
 */
abstract class ProblemIntention implements IntentionAction, PriorityAction {

    /** Lines either side of the problem sent with it, and replaced by the answer. */
    static final int WINDOW = 6;

    @Override public @NotNull String getFamilyName() { return "LLM Copilot"; }
    @Override public boolean startInWriteAction() { return false; }
    @Override public @NotNull Priority getPriority() { return Priority.LOW; }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return editor != null && LLMCopilotSettings.getInstance().isEnabled()
            && !ProblemsAtCaret.onCaretLine(project, editor).isEmpty();
    }

    /** The run of lines a fix may rewrite, as [first, last], zero-based. */
    static int[] window(Document doc, List<ProblemsAtCaret.Problem> problems) {
        int first = problems.stream().mapToInt(ProblemsAtCaret.Problem::line).min().orElse(0);
        int last  = problems.stream().mapToInt(ProblemsAtCaret.Problem::line).max().orElse(0);
        return new int[] { Math.max(0, first - WINDOW), Math.min(doc.getLineCount() - 1, last + WINDOW) };
    }

    static String relativePath(Project project, PsiFile file) {
        VirtualFile vf = file != null ? file.getVirtualFile() : null;
        if (vf == null) return file != null ? file.getName() : "untitled";
        String base = project.getBasePath();
        String path = vf.getPath();
        return base != null && path.startsWith(base + "/") ? path.substring(base.length() + 1) : vf.getName();
    }
}
