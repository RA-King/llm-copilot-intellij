package com.llmcopilot.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.llmcopilot.completion.InlineCompletionHandler;
import com.llmcopilot.index.ProjectIndexService;
import org.jetbrains.annotations.NotNull;

/**
 * The two actions for the whole-project index: rebuild it, and see what it
 * currently holds.
 *
 * <p>Neither is needed in normal use — the index builds itself when the
 * project opens and re-reads files as they are saved. They exist for the two
 * cases where that is not enough: a project changed outside the IDE, and the
 * question "is it actually using my code, or is it guessing".
 */

// ════════════════════════════════════════════════════════════════════════════
// REBUILD
// ════════════════════════════════════════════════════════════════════════════

class RebuildProjectIndexAction extends AnAction {

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ProjectIndexService index = ProjectIndexService.getInstance(project);
        index.clear();
        InlineCompletionHandler.clearCache();

        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "LLM Copilot: indexing the project", true) {
                @Override public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    index.build();

                    ProjectIndexService.Status status = index.status();
                    String message = "Indexed " + status.files() + " files and "
                        + status.symbols() + " symbols in " + status.builtInMs() + "ms"
                        + (status.truncated()
                            ? " (stopped at the file ceiling — raise it in Settings)." : ".");

                    com.intellij.openapi.application.ApplicationManager.getApplication()
                        .invokeLater(() -> {
                            if (!project.isDisposed()) {
                                Messages.showInfoMessage(project, message, "LLM Copilot");
                            }
                        });
                }
            });
    }
}

// ════════════════════════════════════════════════════════════════════════════
// STATUS
// ════════════════════════════════════════════════════════════════════════════

class ShowProjectIndexStatusAction extends AnAction {

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ProjectIndexService index = ProjectIndexService.getInstance(project);
        ProjectIndexService.Status status = index.status();
        long[] pacing = InlineCompletionHandler.pacingStats();

        StringBuilder report = new StringBuilder();
        report.append("State:        ").append(status.state()).append('\n');
        report.append("Files:        ").append(status.files())
              .append(status.truncated() ? " (ceiling reached)" : "").append('\n');
        report.append("Symbols:      ").append(status.symbols()).append('\n');
        report.append("Last build:   ").append(status.builtInMs()).append("ms\n\n");

        report.append("Ghost text\n");
        report.append("  Median round trip: ").append(pacing[0]).append("ms\n");
        report.append("  Last round trip:   ").append(pacing[1]).append("ms\n");
        report.append("  Current debounce:  ").append(pacing[2]).append("ms\n\n");

        String digest = index.renderDigest();
        report.append(digest.isEmpty() ? "Nothing indexed yet." : digest);

        Messages.showMessageDialog(project, report.toString(),
            "LLM Copilot — Project Index", Messages.getInformationIcon());
    }
}
