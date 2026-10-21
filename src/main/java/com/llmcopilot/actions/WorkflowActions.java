package com.llmcopilot.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.SimpleListCellRenderer;
import com.llmcopilot.chat.CustomInstructions;
import com.llmcopilot.review.CodeReview;
import com.llmcopilot.services.AssistPrompts;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.util.Git;
import com.llmcopilot.util.LanguageUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;

import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The actions that work over the repository rather than the caret: code
 * review, pull request descriptions, and turning a request into a shell
 * command. Mirrors workflowCommands.ts.
 */

// ════════════════════════════════════════════════════════════════════════════
// SHARED
// ════════════════════════════════════════════════════════════════════════════

abstract class WorkflowAction extends AnAction {

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    /** Runs {@code work} off the EDT with a progress indicator, reporting failures in a dialog. */
    static void inBackground(Project project, String title, ThrowingRunnable work) {
        new Task.Backgroundable(project, title, true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try {
                    work.run();
                } catch (Exception ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    onEDT(() -> Messages.showErrorDialog(project, msg, "LLM Copilot"));
                }
            }
        }.queue();
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static void onEDT(Runnable r) { ApplicationManager.getApplication().invokeLater(r); }

    static String ask(Project project, List<LLMClient.ChatMessage> messages) throws Exception {
        return LLMClient.chat(CustomInstructions.withInstructions(messages,
            CustomInstructions.forFile(project.getBasePath(), null)), LLMClient.CHAT_MAX_TOKENS);
    }
}

// ════════════════════════════════════════════════════════════════════════════
// CODE REVIEW
// ════════════════════════════════════════════════════════════════════════════

abstract class ReviewAction extends WorkflowAction {

    /** A finding resolved to a place in the project that can be opened. */
    record Located(VirtualFile file, CodeReview.Finding finding, String label) {}

    static void show(Project project, List<Located> findings) {
        onEDT(() -> {
            if (findings.isEmpty()) {
                Messages.showInfoMessage(project, "Nothing worth raising.", "LLM Copilot Review");
                return;
            }
            SimpleListCellRenderer<Located> renderer = new SimpleListCellRenderer<>() {
                @Override public void customize(@NotNull JList<? extends Located> list, Located value,
                                                int index, boolean selected, boolean focused) {
                    setText(value == null ? "" : value.label());
                }
            };
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(findings)
                .setTitle("LLM Copilot review — " + findings.size() + " comment" + (findings.size() == 1 ? "" : "s"))
                .setRenderer(renderer)
                .setItemChosenCallback(f ->
                    new OpenFileDescriptor(project, f.file(), Math.max(0, f.finding().line() - 1), 0).navigate(true))
                .setNamerForFiltering(Located::label)
                .setResizable(true)
                .setMovable(true)
                .createPopup()
                .showCenteredInCurrentWindow(project);
        });
    }

    static String label(String file, CodeReview.Finding f) {
        return f.severity().name().toLowerCase(Locale.ROOT) + "  " + file + ":" + f.line() + "  " + f.message();
    }
}

/** Review the selection, or the whole file when nothing is selected. */
class ReviewSelectionAction extends ReviewAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || editor == null || vf == null) return;

        Document doc = editor.getDocument();
        var sel = editor.getSelectionModel();
        int firstLine = sel.hasSelection() ? doc.getLineNumber(sel.getSelectionStart()) : 0;
        int lastLine = sel.hasSelection() ? doc.getLineNumber(sel.getSelectionEnd()) : doc.getLineCount() - 1;
        String code = doc.getText().substring(doc.getLineStartOffset(firstLine), doc.getLineEndOffset(Math.max(firstLine, lastLine)));
        String language = LanguageUtils.getLanguageId(editor);
        String name = vf.getName();

        inBackground(project, "LLM Copilot: reviewing", () -> {
            List<Located> found = CodeReview.parseFindings(
                    ask(project, CodeReview.selectionPrompt(code, language, name, firstLine + 1))).stream()
                .filter(f -> f.line() >= firstLine + 1 && f.line() <= lastLine + 1)
                .map(f -> new Located(vf, f, label(name, f)))
                .toList();
            show(project, found);
        });
    }
}

/** Review everything not yet committed. */
class ReviewChangesAction extends ReviewAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.getBasePath() == null) return;
        String base = project.getBasePath();

        inBackground(project, "LLM Copilot: reviewing changes", () -> {
            String root = Git.run(base, "rev-parse", "--show-toplevel").trim();
            String diff = Git.uncommittedDiff(root);
            if (diff.isBlank()) {
                onEDT(() -> Messages.showInfoMessage(project, "There are no uncommitted changes to review.", "LLM Copilot"));
                return;
            }
            String annotated = CodeReview.annotateDiff(diff);
            if (annotated.length() > 30000) annotated = annotated.substring(0, 30000);

            List<Located> found = CodeReview.parseFindings(ask(project, CodeReview.changesPrompt(annotated))).stream()
                .filter(f -> f.file() != null)
                .map(f -> {
                    VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(Path.of(root, f.file()));
                    return vf == null ? null : new Located(vf, f, label(f.file(), f));
                })
                .filter(java.util.Objects::nonNull)
                .toList();
            show(project, found);
        });
    }
}

// ════════════════════════════════════════════════════════════════════════════
// PULL REQUEST DESCRIPTION
// ════════════════════════════════════════════════════════════════════════════

class GeneratePullRequestDescriptionAction extends WorkflowAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.getBasePath() == null) return;
        String dir = project.getBasePath();

        inBackground(project, "LLM Copilot: writing pull request description", () -> {
            String branch = Git.run(dir, "rev-parse", "--abbrev-ref", "HEAD").trim();
            String base = Git.baseBranch(dir);
            String log = Git.quiet(dir, "log", "--no-color", "--format=%s%n%b", base + "..HEAD");
            String stat = Git.quiet(dir, "diff", "--no-color", "--stat", base + "...HEAD");
            String diff = Git.quiet(dir, "diff", "--no-color", base + "...HEAD");
            if (log.isBlank() && diff.isBlank()) {
                onEDT(() -> Messages.showInfoMessage(project, branch + " has no changes against " + base + ".", "LLM Copilot"));
                return;
            }

            String text = ask(project, AssistPrompts.pullRequest(branch, base, log, stat, diff, 12000)).trim();
            onEDT(() -> {
                CopyPasteManager.getInstance().setContents(new StringSelection(text));
                LightVirtualFile file = new LightVirtualFile("pull-request.md", text);
                FileEditorManager.getInstance(project).openFile(file, true);
            });
        });
    }
}

// ════════════════════════════════════════════════════════════════════════════
// TERMINAL COMMAND
// ════════════════════════════════════════════════════════════════════════════

class SuggestTerminalCommandAction extends WorkflowAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        String request = Messages.showInputDialog(project,
            "Describe the command you need:", "Suggest Terminal Command", Messages.getQuestionIcon(), "", null);
        if (request == null || request.isBlank()) return;

        String os = System.getProperty("os.name", "");
        boolean windows = os.toLowerCase(Locale.ROOT).contains("win");
        String shellPath = windows ? "powershell" : System.getenv().getOrDefault("SHELL", "bash");
        String shell = shellPath.substring(shellPath.lastIndexOf('/') + 1);
        String platform = os.toLowerCase(Locale.ROOT).contains("mac") ? "macOS" : windows ? "Windows" : "Linux";

        inBackground(project, "LLM Copilot: writing command", () -> {
            AssistPrompts.TerminalSuggestion suggestion = AssistPrompts.parseTerminalSuggestion(
                ask(project, AssistPrompts.terminalCommand(request, shell, platform, project.getBasePath())));
            if (suggestion.command().isEmpty()) {
                onEDT(() -> Messages.showWarningDialog(project, "No command came back for that request.", "LLM Copilot"));
                return;
            }
            onEDT(() -> {
                String body = suggestion.command() + (suggestion.explanation().isEmpty() ? "" : "\n\n" + suggestion.explanation());
                int choice = Messages.showDialog(project, body, "Suggested Command",
                    new String[] { "Copy and Open Terminal", "Copy", "Cancel" }, 0, Messages.getInformationIcon());
                if (choice == 2 || choice < 0) return;
                CopyPasteManager.getInstance().setContents(new StringSelection(suggestion.command()));
                if (choice == 0) {
                    var terminal = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
                    if (terminal != null) terminal.activate(null);
                }
            });
        });
    }
}
