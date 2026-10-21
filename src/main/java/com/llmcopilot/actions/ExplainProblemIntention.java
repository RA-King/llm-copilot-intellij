package com.llmcopilot.actions;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiFile;
import com.llmcopilot.chat.LLMChatPanel;
import com.llmcopilot.services.AssistPrompts;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.util.LanguageUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Alt+Enter → Explain with LLM Copilot, answered in the chat window. */
public final class ExplainProblemIntention extends ProblemIntention {

    @Override public @NotNull String getText() { return "Explain with LLM Copilot"; }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        Document doc = editor.getDocument();
        List<ProblemsAtCaret.Problem> problems = ProblemsAtCaret.onCaretLine(project, editor);
        if (problems.isEmpty()) return;

        int[] lines = window(doc, problems);
        String code = doc.getText().substring(doc.getLineStartOffset(lines[0]), doc.getLineEndOffset(lines[1]));
        List<LLMClient.ChatMessage> messages = AssistPrompts.problemExplain(
            LanguageUtils.getLanguageId(editor), relativePath(project, file),
            problems.stream().map(ProblemsAtCaret.Problem::describe).toList(), code);

        var tw = ToolWindowManager.getInstance(project).getToolWindow("LLM Copilot");
        if (tw != null) tw.show();
        LLMChatPanel panel = LLMChatPanel.getInstance(project);
        if (panel != null) panel.ask("Explain: " + problems.get(0).description(), messages);
    }
}
