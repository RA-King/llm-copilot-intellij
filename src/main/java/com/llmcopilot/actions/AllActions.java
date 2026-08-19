package com.llmcopilot.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindowManager;
import com.llmcopilot.chat.LLMChatPanel;
import com.llmcopilot.completion.InlineCompletionHandler;
import com.llmcopilot.completion.LLMEditorFactoryListener;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.services.PromptBuilder;
import com.llmcopilot.settings.LLMCopilotSettings;
import com.llmcopilot.util.LanguageUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ════════════════════════════════════════════════════════════════════════════
// BASE
// ════════════════════════════════════════════════════════════════════════════

abstract class BaseAction extends AnAction {

    protected static final ExecutorService BG = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-action");
        t.setDaemon(true);   // daemon threads don't block IDE shutdown
        t.setUncaughtExceptionHandler((thread, ex) ->
            System.err.println("[LLM Copilot] uncaught in " + thread.getName() + ": " + ex.getMessage()));
        return t;
    });

    // ── Editor helpers ────────────────────────────────────────────────────────

    protected static Editor  getEditor(AnActionEvent e)  { return e.getData(CommonDataKeys.EDITOR); }
    protected static Project getProject(AnActionEvent e) { return e.getProject(); }

    protected static String getSelectedText(AnActionEvent e) {
        Editor ed = getEditor(e);
        if (ed == null) return "";
        try {
            String t = ed.getSelectionModel().getSelectedText();
            return t != null ? t : "";
        } catch (Exception ex) { return ""; }
    }

    protected static String getLang(AnActionEvent e) {
        Editor ed = getEditor(e);
        return ed != null ? LanguageUtils.getLanguageId(ed) : "text";
    }

    protected static String cleanCode(String s) {
        return s.replaceAll("(?s)^```\\w*\\r?\\n?", "").replaceAll("\\r?\\n?```\\s*$", "").trim();
    }

    // ── Chat helpers ──────────────────────────────────────────────────────────

    /** Show the chat panel and send a slash command or free message. */
    protected static void askInChat(Project project, String message) {
        if (project == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            var tw = ToolWindowManager.getInstance(project).getToolWindow("LLM Copilot");
            if (tw != null) tw.show();
            LLMChatPanel panel = LLMChatPanel.getInstance(project);
            if (panel != null) panel.askWith(message);
        });
    }

    /** Show the chat panel and append a pre-generated assistant message. */
    protected static void showInChat(Project project, String text) {
        if (project == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            var tw = ToolWindowManager.getInstance(project).getToolWindow("LLM Copilot");
            if (tw != null) tw.show();
            LLMChatPanel panel = LLMChatPanel.getInstance(project);
            if (panel != null) panel.appendAssistant(text);
        });
    }

    // ── Editor write helpers ──────────────────────────────────────────────────

    protected static void insertAtCaret(Editor editor, String text) {
        WriteCommandAction.runWriteCommandAction(editor.getProject(), "LLM Insert", null, () -> {
            int offset = editor.getCaretModel().getOffset();
            editor.getDocument().insertString(offset, "\n" + text + "\n");
        });
    }

    protected static void replaceSelection(Editor editor, String text) {
        WriteCommandAction.runWriteCommandAction(editor.getProject(), "LLM Replace", null, () -> {
            SelectionModel sel = editor.getSelectionModel();
            editor.getDocument().replaceString(sel.getSelectionStart(), sel.getSelectionEnd(), text);
        });
    }

    protected void runInBG(Runnable r) { BG.submit(r); }

    protected static String classContext(Editor ed, int linesBefore, int linesAfter) {
        Document doc  = ed.getDocument();
        int      line = doc.getLineNumber(ed.getCaretModel().getOffset());
        int      s    = doc.getLineStartOffset(Math.max(0, line - linesBefore));
        int      end  = doc.getLineEndOffset(Math.min(doc.getLineCount() - 1, line + linesAfter));
        return doc.getText().substring(s, end);
    }

    protected static void onEDT(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r);
    }

    protected static void showError(Project p, Exception ex) {
        if (ex == null) return;
        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        onEDT(() -> {
            if (p != null && !p.isDisposed()) {
                Messages.showErrorDialog(p, msg, "LLM Copilot");
            }
        });
    }
}

// ════════════════════════════════════════════════════════════════════════════
// INLINE COMPLETION
// ════════════════════════════════════════════════════════════════════════════

/** Ctrl+Shift+Space — manually trigger / accept ghost text */
class TriggerCompletionAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Editor ed = getEditor(e);
        if (ed == null) return;
        // If ghost text is visible, accept it; otherwise trigger a new completion
        if (!LLMEditorFactoryListener.acceptSuggestion(ed)) {
            InlineCompletionHandler h = LLMEditorFactoryListener.getHandler(ed);
            if (h != null) h.triggerCompletion();
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// CHAT
// ════════════════════════════════════════════════════════════════════════════

/** Ctrl+Alt+I — open the AI Chat panel */
class OpenChatAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = getProject(e);
        if (p == null) return;
        var tw = ToolWindowManager.getInstance(p).getToolWindow("LLM Copilot");
        if (tw != null) tw.show();
    }
}

/** Ctrl+I — inline chat with optional selection context */
class InlineChatAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Editor  ed = getEditor(e);
        Project p  = getProject(e);
        if (ed == null || p == null) return;

        String instruction = Messages.showInputDialog(
            p, "Ask LLM Copilot:", "Inline Chat", Messages.getQuestionIcon(), "", null);
        if (instruction == null || instruction.isBlank()) return;

        // Delegate to chat panel; it will auto-include the active selection
        askInChat(p, instruction);
    }
}

// ════════════════════════════════════════════════════════════════════════════
// CODE ACTIONS  (all route through the chat panel for consistent UX)
// ════════════════════════════════════════════════════════════════════════════

/** Ctrl+Shift+E — explain selected code */
class ExplainCodeAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        if (getSelectedText(e).isBlank()) {
            Messages.showInfoMessage(getProject(e), "Select code to explain.", "LLM Copilot");
            return;
        }
        askInChat(getProject(e), "/explain");
    }
}

/** Fix bugs in selected code */
class FixCodeAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        if (getSelectedText(e).isBlank()) {
            Messages.showInfoMessage(getProject(e), "Select code to fix.", "LLM Copilot");
            return;
        }
        askInChat(getProject(e), "/fix");
    }
}

/** Ctrl+Shift+R — refactor with custom instruction */
class RefactorCodeAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = getProject(e);
        if (getSelectedText(e).isBlank()) {
            Messages.showInfoMessage(p, "Select code to refactor.", "LLM Copilot");
            return;
        }
        String instr = Messages.showInputDialog(
            p, "Refactor instruction:", "Refactor", Messages.getQuestionIcon(), "", null);
        if (instr == null || instr.isBlank()) return;
        askInChat(p, "/refactor " + instr);
    }
}

/** Ctrl+Shift+D — generate doc comment for declaration at cursor */
class GenerateDocCommentAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Editor  ed   = getEditor(e);
        Project p    = getProject(e);
        String  lang = getLang(e);
        if (ed == null) return;

        Document doc  = ed.getDocument();
        int      line = doc.getLineNumber(ed.getCaretModel().getOffset());
        int      end  = doc.getLineEndOffset(Math.min(doc.getLineCount() - 1, line + 20));
        String snippet = doc.getText().substring(doc.getLineStartOffset(line), end);

        runInBG(() -> {
            try {
                String comment = cleanCode(LLMClient.chat(PromptBuilder.generateDocComment(snippet, lang)));
                onEDT(() -> WriteCommandAction.runWriteCommandAction(p, "Insert Doc Comment", null, () -> {
                    int at = doc.getLineStartOffset(line);
                    doc.insertString(at, comment + "\n");
                }));
            } catch (Exception ex) { showError(p, ex); }
        });
    }
}

/** Ctrl+Shift+T — generate unit tests for selected code */
class GenerateTestsAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        String code = getSelectedText(e);
        if (code.isBlank()) {
            Messages.showInfoMessage(getProject(e), "Select code to generate tests for.", "LLM Copilot");
            return;
        }
        // Ask framework then delegate to chat (tests get Accept/Discard proposal)
        String fw = Messages.showInputDialog(
            getProject(e), "Test framework? (blank = auto)", "Generate Tests",
            Messages.getQuestionIcon(), "", null);
        if (fw == null) return;
        askInChat(getProject(e), "/test" + (fw.isBlank() ? "" : " " + fw));
    }
}

/** Generate constructor for the class at cursor */
class GenerateConstructorAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Editor  ed   = getEditor(e);
        Project p    = getProject(e);
        String  lang = getLang(e);
        if (ed == null) return;

        String code = getSelectedText(e).isBlank() ? classContext(ed, 5, 30) : getSelectedText(e);
        runInBG(() -> {
            try {
                String result = cleanCode(LLMClient.chat(PromptBuilder.generateConstructor(code, lang)));
                onEDT(() -> insertAtCaret(ed, result));
            } catch (Exception ex) { showError(p, ex); }
        });
    }
}

/** Generate getter/setter for a field */
class GenerateGettersSettersAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Editor  ed   = getEditor(e);
        Project p    = getProject(e);
        String  lang = getLang(e);
        if (ed == null) return;

        String fieldSpec = Messages.showInputDialog(
            p, "Field name (blank = all):", "Generate Getters/Setters",
            Messages.getQuestionIcon(), "", null);
        if (fieldSpec == null) return;

        String ctx = classContext(ed, 10, 50);
        runInBG(() -> {
            try {
                List<LLMClient.ChatMessage> msgs = fieldSpec.isBlank()
                    ? PromptBuilder.generateGetterSetter(ctx, lang, "*", "all fields")
                    : PromptBuilder.generateGetterSetter(ctx, lang, fieldSpec.trim(), "auto-detect");
                String result = cleanCode(LLMClient.chat(msgs));
                onEDT(() -> insertAtCaret(ed, result));
            } catch (Exception ex) { showError(p, ex); }
        });
    }
}

/** Implement interface methods one at a time */
class ImplementInterfaceAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Editor  ed   = getEditor(e);
        Project p    = getProject(e);
        String  lang = getLang(e);
        if (ed == null) return;

        String selected = getSelectedText(e);
        String ifaceCode = selected.isBlank()
            ? Messages.showInputDialog(p, "Paste the interface / abstract class declaration:",
                "Implement Interface", Messages.getQuestionIcon(), "", null)
            : selected;
        if (ifaceCode == null || ifaceCode.isBlank()) return;

        String classCtx = classContext(ed, 20, 5);

        runInBG(() -> {
            try {
                for (String line : ifaceCode.split("\n")) {
                    String t = line.trim();
                    if (!isMethodSig(t)) continue;
                    String name = extractMethodName(t);
                    String impl = cleanCode(LLMClient.chat(PromptBuilder.implementMethod(t, classCtx, lang)));
                    onEDT(() -> insertAtCaret(ed, impl));
                    // Ask to continue on EDT (blocking via invokeAndWait)
                    int[] choice = {Messages.YES};
                    ApplicationManager.getApplication().invokeAndWait(() ->
                        choice[0] = Messages.showYesNoDialog(
                            p, "\"" + name + "\" implemented. Continue?",
                            "Implement Interface", "Yes", "Stop", null));
                    if (choice[0] != Messages.YES) break;
                }
            } catch (Exception ex) { showError(p, ex); }
        });
    }

    private static boolean isMethodSig(String s) {
        return s.matches(".*\\w+\\s*\\([^)]*\\).*[;{]?") && !s.startsWith("//");
    }
    private static String extractMethodName(String s) {
        var m = java.util.regex.Pattern.compile("(\\w+)\\s*\\(").matcher(s);
        return m.find() ? m.group(1) : s.substring(0, Math.min(30, s.length()));
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SELECTION ACTIONS  (Ctrl+Space on selection)
// ════════════════════════════════════════════════════════════════════════════

class ShowSelectionActionsAction extends BaseAction {

    private static final String[] OPTIONS = {
        "💡 Explain", "🔧 Fix bugs", "✏️ Refactor…", "🧪 Generate Tests",
        "📝 Doc comment", "🏗️ Generate Constructor", "🔑 Getters/Setters",
        "💬 Ask in Chat…"
    };

    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project p    = getProject(e);
        String  code = getSelectedText(e);

        if (code.isBlank()) {
            Messages.showInfoMessage(p, "Select code first, then press Ctrl+Space.", "LLM Copilot");
            return;
        }

        int choice = Messages.showChooseDialog(
            p, "What would you like to do with the selected code?",
            "LLM Copilot — " + code.split("\n").length + " lines selected",
            Messages.getQuestionIcon(), OPTIONS, OPTIONS[0]);
        if (choice < 0) return;

        switch (choice) {
            case 0 -> askInChat(p, "/explain");
            case 1 -> askInChat(p, "/fix");
            case 2 -> {
                String instr = Messages.showInputDialog(
                    p, "Refactor instruction:", "Refactor",
                    Messages.getQuestionIcon(), "", null);
                if (instr != null && !instr.isBlank()) askInChat(p, "/refactor " + instr);
            }
            case 3 -> {
                String fw = Messages.showInputDialog(
                    p, "Test framework? (blank = auto):", "Generate Tests",
                    Messages.getQuestionIcon(), "", null);
                if (fw != null) askInChat(p, "/test" + (fw.isBlank() ? "" : " " + fw));
            }
            case 4 -> askInChat(p, "/doc");
            case 5 -> new GenerateConstructorAction().actionPerformed(e);
            case 6 -> new GenerateGettersSettersAction().actionPerformed(e);
            case 7 -> {
                String q = Messages.showInputDialog(
                    p, "Ask about this code:", "Ask in Chat",
                    Messages.getQuestionIcon(), "", null);
                if (q != null && !q.isBlank()) askInChat(p, q);
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// COMMIT MESSAGE
// ════════════════════════════════════════════════════════════════════════════

/** Ctrl+Shift+M — generate commit message from git diff */
class GenerateCommitMessageAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = getProject(e);
        if (p == null) return;

        String diff = gitDiff(p);
        if (diff.isBlank()) {
            diff = Messages.showInputDialog(
                p, "Paste your git diff:", "Generate Commit Message",
                Messages.getQuestionIcon(), "", null);
            if (diff == null || diff.isBlank()) return;
        }

        final String finalDiff = diff;
        runInBG(() -> {
            try {
                String msg = LLMClient.chat(PromptBuilder.commitMessage(finalDiff)).trim();
                onEDT(() -> {
                    Messages.showInfoMessage(
                        p, "Commit message:\n\n" + msg + "\n\n(copied to clipboard)", "LLM Copilot");
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(msg), null);
                });
            } catch (Exception ex) { showError(p, ex); }
        });
    }

    private static String gitDiff(Project p) {
        try {
            String base = p.getBasePath();
            if (base == null) return "";
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--cached");
            pb.directory(new java.io.File(base));
            String out = new String(pb.start().getInputStream().readAllBytes());
            if (out.isBlank()) {
                pb = new ProcessBuilder("git", "diff");
                pb.directory(new java.io.File(base));
                out = new String(pb.start().getInputStream().readAllBytes());
            }
            return out;
        } catch (Exception ex) { return ""; }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// TOGGLE / TEST
// ════════════════════════════════════════════════════════════════════════════

/** Toggle LLM Copilot on/off */
class ToggleEnabledAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
        s.setEnabled(!s.isEnabled());
        InlineCompletionHandler.clearCache();
        Messages.showInfoMessage(
            getProject(e),
            "LLM Copilot " + (s.isEnabled() ? "enabled ✓" : "disabled ✗"),
            "LLM Copilot");
    }
}

/** Test connection to the configured LLM provider */
class TestConnectionAction extends BaseAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = getProject(e);
        runInBG(() -> {
            String result = LLMClient.testConnection();
            onEDT(() -> Messages.showInfoMessage(p, result, "LLM Copilot: Connection Test"));
        });
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SHARED UTILITIES
// ════════════════════════════════════════════════════════════════════════════

// (accessible by inner action classes above via static call)
class ActionUtils {
    static String classContext(Editor ed, int before, int after) {
        Document doc  = ed.getDocument();
        int      line = doc.getLineNumber(ed.getCaretModel().getOffset());
        int      s    = doc.getLineStartOffset(Math.max(0, line - before));
        int      end  = doc.getLineEndOffset(Math.min(doc.getLineCount() - 1, line + after));
        return doc.getText().substring(s, end);
    }
}

// Pull classContext into BaseAction so all subclasses can use it
// (done inline since Java doesn't allow free functions)
// The pattern is: subclasses call the static helper directly.
