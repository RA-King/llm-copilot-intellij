package com.llmcopilot.errors;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.SimpleListCellRenderer;
import com.llmcopilot.chat.LLMChatPanel;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.services.PromptBuilder;
import com.llmcopilot.settings.LLMCopilotSettings;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;

import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The pane the user actually sees. An error in a run, debug or terminal console
 * goes in; a short list of candidate fixes comes back, each one a line long.
 * Choosing one carries the whole thing — the output, the resolved source, and
 * the approach chosen — into the chat tool window, where the answer arrives
 * with the code in it and the conversation can continue.
 *
 * <p>The shortlist exists because a stack trace usually has more than one
 * plausible cause, and reading four one-line hypotheses is faster than reading
 * one long answer that guessed wrong. Nothing longer is generated until the
 * user has said which hypothesis is worth pursuing.
 */
public final class ErrorAssistant {

    private ErrorAssistant() { }

    private static final String TOOL_WINDOW = "LLM Copilot";
    private static final String NOTIFICATION_GROUP = "LLM Copilot";

    // ── Entry points ─────────────────────────────────────────────────────────

    /**
     * Offers a failure the moment it happens, for the user who is watching the
     * console it appeared in. Everything is still captured when this is off —
     * only the notification goes away.
     */
    public static void offer(Project project, CapturedError captured) {
        if (!LLMCopilotSettings.getInstance().isErrorAssistAutoOffer()) return;

        String where = captured.isDebug() ? "Debugger" : "Run";
        // Called from the process's own thread as it terminates.
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(where + ": " + captured.origin(), captured.firstLine(), NotificationType.WARNING)
                .addAction(NotificationAction.createSimple("Show solutions", () -> analyse(project, captured)))
                .notify(project);
        });
    }

    /** Reads the capture, asks for candidate fixes in the background, shows them. */
    public static void analyse(Project project, CapturedError captured) {
        ErrorParser.ParsedError parsed = ErrorParser.parse(captured.text(), captured.source());
        if (parsed == null) {
            askRawly(project, captured);
            return;
        }

        LLMCopilotSettings settings = LLMCopilotSettings.getInstance();
        int count = settings.getErrorSolutionCount();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Reading the error", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Resolving the files the trace names…");
                List<SourceLookup.FrameContext> frames =
                    SourceLookup.gather(project, parsed, settings.getErrorContextLines());

                // What the rest of the project says about the names in this
                // failure. It is the difference between a fix written against
                // the real signatures and one written against plausible ones,
                // so it is gathered before the first ask rather than only when
                // the user drills in.
                indicator.setText("Reading the project around it…");
                DeepContext.Gathered deep;
                try {
                    deep = DeepContext.gather(project, parsed, frames);
                } catch (Exception ex) {
                    deep = DeepContext.Gathered.EMPTY;
                }

                PromptBuilder.ErrorContext context = toContext(project, captured, parsed, frames, deep);

                indicator.setText("Asking for candidate fixes…");
                List<ErrorSolutions.Solution> solutions;
                try {
                    String reply = LLMClient.chat(PromptBuilder.errorSolutions(context, count));
                    solutions = ErrorSolutions.parse(reply, count);
                } catch (Exception ex) {
                    showError(project, ex);
                    return;
                }

                DeepContext.Gathered gathered = deep;
                List<ErrorSolutions.Solution> found = solutions;
                ApplicationManager.getApplication().invokeLater(() ->
                    showPane(project, parsed, context, frames, found, gathered));
            }
        });
    }

    // ── The pane ─────────────────────────────────────────────────────────────

    private enum Kind { SOLUTION, DIAGNOSE, EXPLAIN, ASK, OPEN, GOTO, COPY }

    private record PaneItem(Kind kind, String title, String detail, ErrorSolutions.Solution solution) {

        /** Two lines in the popup: the title, and the reasoning under it in grey. */
        String display() {
            String head = "<b>" + escape(title) + "</b>";
            if (detail == null || detail.isBlank()) return "<html>" + head + "</html>";
            String tail = detail.length() > 140 ? detail.substring(0, 137) + "…" : detail;
            return "<html>" + head + "<br><span style='color:gray'>" + escape(tail) + "</span></html>";
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static void showPane(Project project,
                                 ErrorParser.ParsedError parsed,
                                 PromptBuilder.ErrorContext context,
                                 List<SourceLookup.FrameContext> frames,
                                 List<ErrorSolutions.Solution> solutions,
                                 DeepContext.Gathered deep) {
        List<PaneItem> items = new ArrayList<>();
        for (int i = 0; i < solutions.size(); i++) {
            ErrorSolutions.Solution solution = solutions.get(i);
            // Where the fix lands is more use in the second line than a
            // ranking the user can already see from the order.
            String detail = describeSolution(solution, i);
            items.add(new PaneItem(Kind.SOLUTION, solution.title(), detail, solution));
        }

        items.add(new PaneItem(Kind.DIAGNOSE, "Work it through properly",
            deep.isEmpty()
                ? "Full diagnosis: the sequence, the cause, the change, the fallout"
                : "Full diagnosis against " + deep.resolvedNames().size() + " resolved name"
                  + (deep.resolvedNames().size() == 1 ? "" : "s")
                  + (deep.dependents().isEmpty() ? ""
                     : " and " + deep.dependents().size() + " caller"
                       + (deep.dependents().size() == 1 ? "" : "s")),
            null));
        items.add(new PaneItem(Kind.EXPLAIN, "Explain this error",
            "What it means and how the program got here — no fix yet", null));
        items.add(new PaneItem(Kind.ASK, "Ask something about it…",
            "Your own question, with the error and its code attached", null));

        SourceLookup.FrameContext top = frames.isEmpty() ? null : frames.get(0);
        if (top != null) {
            items.add(new PaneItem(Kind.OPEN,
                "Open " + top.file().getName() + (top.frame().line() > 0 ? ":" + top.frame().line() : ""),
                top.file().getPath(), null));
        }

        // Anywhere else the answer named, reachable without leaving the pane.
        for (ErrorSolutions.Solution target : namedTargets(project, solutions, top)) {
            items.add(new PaneItem(Kind.GOTO,
                "Open " + target.file() + (target.line() > 0 ? ":" + target.line() : ""),
                "Named by one of the fixes above", target));
        }

        items.add(new PaneItem(Kind.COPY, "Copy the error text", null, null));

        SimpleListCellRenderer<PaneItem> renderer = new SimpleListCellRenderer<>() {
            @Override public void customize(@NotNull JList<? extends PaneItem> list, PaneItem item,
                                            int index, boolean selected, boolean focused) {
                setText(item == null ? "" : item.display());
            }
        };

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(ErrorParser.describe(parsed))
            .setRenderer(renderer)
            .setNamerForFiltering(PaneItem::title)
            .setItemChosenCallback(item -> handle(project, item, context, top))
            .setMovable(true)
            .setResizable(true)
            .createPopup()
            .showCenteredInCurrentWindow(project);
    }

    private static void handle(Project project, PaneItem item,
                               PromptBuilder.ErrorContext context,
                               SourceLookup.FrameContext top) {
        switch (item.kind()) {
            case SOLUTION -> toChat(project,
                seedText(context, "**Fix to try:** " + item.solution().title()),
                PromptBuilder.errorWalkthrough(context, item.solution().title(), item.solution().detail()));

            case DIAGNOSE -> toChat(project,
                seedText(context, "Work this through properly — cause, change, and what else it touches."),
                PromptBuilder.errorDiagnosis(context));

            case EXPLAIN -> toChat(project,
                seedText(context, "What is this error telling me?"),
                PromptBuilder.errorExplain(context));

            case ASK -> {
                String question = Messages.showInputDialog(project,
                    "Ask about this error:", "LLM Copilot", Messages.getQuestionIcon(), "", null);
                if (question == null || question.isBlank()) return;
                toChat(project, seedText(context, question), PromptBuilder.errorQuestion(context, question));
            }

            case OPEN -> {
                if (top == null) return;
                int line = Math.max(0, top.frame().line() - 1);
                int column = Math.max(0, top.frame().column() - 1);
                new OpenFileDescriptor(project, top.file(), line, column).navigate(true);
            }

            case GOTO -> {
                ErrorSolutions.Solution target = item.solution();
                if (target == null || !target.hasTarget()) return;
                VirtualFile file = findNamedFile(project, target.file());
                if (file == null) {
                    Messages.showWarningDialog(project,
                        "Could not find " + target.file() + " in this project.", "LLM Copilot");
                    return;
                }
                new OpenFileDescriptor(project, file, Math.max(0, target.line() - 1), 0).navigate(true);
            }

            case COPY -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(context.errorText()), null);
        }
    }

    // ── Chat hand-off ────────────────────────────────────────────────────────

    /**
     * What the chat window shows as the user's turn. The model gets the whole
     * context; the bubble gets the question and enough of the output to
     * recognise it, since the console it came from may already have scrolled.
     */
    private static String seedText(PromptBuilder.ErrorContext context, String question) {
        String[] lines = context.errorText().split("\n");
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 14); i++) {
            shown.append(lines[i]).append("\n");
        }
        if (lines.length > 14) shown.append("… ").append(lines.length - 14).append(" more lines\n");

        String where = switch (context.source()) {
            case "debug" -> "debug session";
            case "terminal" -> "terminal";
            default -> "console";
        };
        return question + "\n\nFrom the " + where + " (" + context.origin() + "):\n```text\n"
             + shown.toString().stripTrailing() + "\n```";
    }

    private static void toChat(Project project, String display, List<LLMClient.ChatMessage> messages) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW);
            if (window == null) return;
            window.show(() -> {
                LLMChatPanel panel = LLMChatPanel.getInstance(project);
                if (panel != null) panel.ask(display, messages);
            });
        });
    }

    // ── Fallbacks ────────────────────────────────────────────────────────────

    /** Nothing recognisable in the output — offer to hand it over as it stands. */
    private static void askRawly(Project project, CapturedError captured) {
        ApplicationManager.getApplication().invokeLater(() -> {
            int choice = Messages.showYesNoDialog(project,
                "No error could be read out of that output. Ask about it anyway?",
                "LLM Copilot", "Ask in Chat", "Cancel", Messages.getQuestionIcon());
            if (choice != Messages.YES) return;

            PromptBuilder.ErrorContext context = new PromptBuilder.ErrorContext(
                captured.source(), captured.origin(), "unknown",
                captured.firstLine(), captured.text(), "");
            toChat(project, seedText(context, "What went wrong here?"),
                PromptBuilder.errorQuestion(context, "What went wrong here?"));
        });
    }

    private static void showError(Project project, Exception ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) Messages.showErrorDialog(project, message, "LLM Copilot");
        });
    }

    // ── Prompt context ───────────────────────────────────────────────────────

    private static PromptBuilder.ErrorContext toContext(Project project,
                                                        CapturedError captured,
                                                        ErrorParser.ParsedError parsed,
                                                        List<SourceLookup.FrameContext> frames,
                                                        DeepContext.Gathered deep) {
        StringBuilder code = new StringBuilder();
        String base = project.getBasePath();

        for (SourceLookup.FrameContext frame : frames) {
            if (code.length() > 0) code.append("\n\n");

            String path = frame.file().getPath();
            if (base != null && path.startsWith(base + "/")) path = path.substring(base.length() + 1);

            int lines = frame.snippet().split("\n", -1).length;
            code.append(path)
                .append(", lines ").append(frame.startLine() + 1)
                .append("-").append(frame.startLine() + lines);
            if (frame.frame().line() > 0) {
                code.append(" (the trace names line ").append(frame.frame().line()).append(")");
            }
            code.append(":\n```").append(frame.language()).append("\n")
                .append(frame.snippet()).append("\n```");
        }

        return new PromptBuilder.ErrorContext(
            captured.source(), captured.origin(), parsed.origin().id(),
            parsed.headline(), parsed.text(), code.toString(), deep.text());
    }

    // ── Pane helpers ─────────────────────────────────────────────────────────

    /** The pane's second line: where the fix lands, and how sure it is. */
    private static String describeSolution(ErrorSolutions.Solution solution, int index) {
        List<String> parts = new ArrayList<>();
        if (solution.hasTarget()) {
            String name = solution.file().substring(solution.file().lastIndexOf('/') + 1);
            parts.add(name + (solution.line() > 0 ? ":" + solution.line() : ""));
        }
        if (!solution.confidence().isEmpty()) parts.add(solution.confidence());
        else if (index == 0) parts.add("most likely");

        String head = String.join(" \u00b7 ", parts);
        if (solution.detail() == null || solution.detail().isBlank()) return head;
        return head.isEmpty() ? solution.detail() : head + " — " + solution.detail();
    }

    /**
     * Files the candidate fixes named that are not the one the trace already
     * pointed at. A cause that lives one file away from the throw is common,
     * and without this the pane makes the user go and find it.
     */
    private static List<ErrorSolutions.Solution> namedTargets(
        Project project, List<ErrorSolutions.Solution> solutions, SourceLookup.FrameContext top
    ) {
        String already = top == null ? "" : top.file().getPath();
        List<ErrorSolutions.Solution> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (ErrorSolutions.Solution solution : solutions) {
            if (!solution.hasTarget()) continue;
            if (!seen.add(solution.file() + ":" + solution.line())) continue;
            if (!already.isEmpty() && already.endsWith(solution.file())) continue;
            out.add(solution);
            if (out.size() >= 3) break;
        }
        return out;
    }

    /** A path the answer named, turned back into a file that exists. */
    private static VirtualFile findNamedFile(Project project, String named) {
        String cleaned = named.replace('\\', '/').replaceAll("^\\./", "");

        VirtualFile base = com.intellij.openapi.project.ProjectUtil.guessProjectDir(project);
        if (base != null) {
            VirtualFile direct = base.findFileByRelativePath(cleaned);
            if (direct != null) return direct;
        }

        String name = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        Collection<VirtualFile> matches = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(
            name, com.intellij.psi.search.GlobalSearchScope.projectScope(project));

        VirtualFile fallback = null;
        for (VirtualFile match : matches) {
            if (match.getPath().endsWith(cleaned)) return match;
            if (fallback == null) fallback = match;
        }
        return fallback;
    }
}
