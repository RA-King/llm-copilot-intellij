package com.llmcopilot.actions;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import com.llmcopilot.chat.CustomInstructions;
import com.llmcopilot.index.ProjectIndexService;
import com.llmcopilot.services.AssistPrompts;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.util.LanguageUtils;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Alt+Enter → Fix with LLM Copilot. */
public final class FixProblemIntention extends ProblemIntention {

    private static final Pattern TYPE_NAME = Pattern.compile("\\b[A-Z][A-Za-z0-9_]{2,}\\b");

    @Override public @NotNull String getText() { return "Fix with LLM Copilot"; }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        Document doc = editor.getDocument();
        List<ProblemsAtCaret.Problem> problems = ProblemsAtCaret.onCaretLine(project, editor);
        if (problems.isEmpty()) return;

        int[] lines = window(doc, problems);
        int start = doc.getLineStartOffset(lines[0]);
        int end = doc.getLineEndOffset(lines[1]);
        String original = doc.getText().substring(start, end);
        long stamp = doc.getModificationStamp();
        String relPath = relativePath(project, file);
        String language = LanguageUtils.getLanguageId(editor);

        Set<String> names = new LinkedHashSet<>();
        Matcher m = TYPE_NAME.matcher(original);
        while (m.find()) names.add(m.group());
        ProjectIndexService index = ProjectIndexService.getInstance(project);
        String declarations = index.isReady() ? index.declarationsFor(names, 1500) : "";

        List<LLMClient.ChatMessage> messages = CustomInstructions.withInstructions(
            AssistPrompts.problemFix(language, relPath,
                problems.stream().map(ProblemsAtCaret.Problem::describe).toList(),
                lines[0] + 1, original, declarations),
            CustomInstructions.forFile(project.getBasePath(), relPath));

        new Task.Backgroundable(project, "LLM Copilot: fixing", true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                String fixed;
                try {
                    fixed = AssistPrompts.extractCodeBlock(LLMClient.chat(messages, LLMClient.CHAT_MAX_TOKENS));
                } catch (Exception ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    ApplicationManager.getApplication().invokeLater(() ->
                        Messages.showErrorDialog(project, msg, "LLM Copilot"));
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (fixed.isBlank() || fixed.equals(original.stripTrailing())) {
                        Messages.showInfoMessage(project, "No change was proposed for this problem.", "LLM Copilot");
                        return;
                    }
                    if (doc.getModificationStamp() != stamp) {
                        Messages.showWarningDialog(project,
                            "The file changed while the fix was being written; try again.", "LLM Copilot");
                        return;
                    }
                    WriteCommandAction.runWriteCommandAction(project, "LLM Copilot: Fix Problem", null,
                        () -> doc.replaceString(start, end, fixed));
                });
            }
        }.queue();
    }
}
