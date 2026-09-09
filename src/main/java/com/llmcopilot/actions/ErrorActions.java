package com.llmcopilot.actions;

import com.intellij.ide.CopyProvider;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.llmcopilot.errors.CapturedError;
import com.llmcopilot.errors.ErrorAssistant;
import com.llmcopilot.errors.ErrorCaptureService;
import com.llmcopilot.errors.ErrorParser;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Actions for the error pane: one that reads whatever the user is looking at in
 * a console or terminal, and one that lists every failure captured so far.
 */

// ════════════════════════════════════════════════════════════════════════════
// EXPLAIN THE ERROR IN FRONT OF ME
// ════════════════════════════════════════════════════════════════════════════

/**
 * Ctrl+Alt+E — read the error the user is looking at and offer fixes for it.
 *
 * <p>Where the text comes from, in order:
 *
 * <ol>
 *   <li>A selection in a console or editor. Run and debug consoles are editors,
 *       so this covers both, and it costs nothing.</li>
 *   <li>The focused component's own copy handler. The terminal is not an editor
 *       and offers no text to the data context, but it does supply a
 *       {@link CopyProvider} — the same one Ctrl+C uses. The clipboard is put
 *       back the way it was found.</li>
 *   <li>The last failure the run listener captured, for the user who has
 *       already scrolled past it.</li>
 * </ol>
 */
final class ExplainErrorAction extends AnAction {

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ErrorCaptureService captures = ErrorCaptureService.getInstance(project);

        String selection = selectedText(e);
        if (!selection.isBlank()) {
            String source = e.getData(CommonDataKeys.EDITOR) != null
                ? CapturedError.RUN
                : CapturedError.TERMINAL;
            CapturedError captured = captures.record(source, "selected output", selection, null);
            if (captured != null) {
                ErrorAssistant.analyse(project, captured);
                return;
            }
        }

        CapturedError latest = captures.latest();
        if (latest == null) {
            Messages.showInfoMessage(project,
                "Nothing has failed yet. Select the error text in the console or terminal and try again.",
                "LLM Copilot");
            return;
        }
        ErrorAssistant.analyse(project, latest);
    }

    /** A console or editor selection, else whatever the focused component copies. */
    private static String selectedText(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            String selected = editor.getSelectionModel().getSelectedText();
            if (selected != null && !selected.isBlank()) return selected;
        }
        return copiedText(e.getDataContext());
    }

    /**
     * Lifts the terminal's selection out through its copy handler, which is the
     * only route the platform offers to a terminal's own text.
     */
    private static String copiedText(DataContext context) {
        CopyProvider provider = PlatformDataKeys.COPY_PROVIDER.getData(context);
        if (provider == null || !provider.isCopyEnabled(context)) return "";

        CopyPasteManager clipboard = CopyPasteManager.getInstance();
        String previous = clipboardText(clipboard);
        try {
            provider.performCopy(context);
            String copied = clipboardText(clipboard);
            // Nothing was selected — the clipboard still holds what it held before.
            return copied.equals(previous) ? "" : copied;
        } catch (Exception ignored) {
            return "";
        } finally {
            if (!previous.isEmpty()) clipboard.setContents(new StringSelection(previous));
        }
    }

    private static String clipboardText(CopyPasteManager clipboard) {
        try {
            String text = clipboard.getContents(DataFlavor.stringFlavor);
            return text != null ? text : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// PICK FROM WHAT HAS ALREADY FAILED
// ════════════════════════════════════════════════════════════════════════════

/** Lists every failure still held, newest first, so a scrolled-away one is reachable. */
final class AnalyseRecentErrorAction extends AnAction {

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        List<CapturedError> recent = ErrorCaptureService.getInstance(project).recent();
        if (recent.isEmpty()) {
            Messages.showInfoMessage(project,
                "No errors have been captured yet from a run, debug or terminal console.",
                "LLM Copilot");
            return;
        }

        // The label is read out of the trace, so it is worked out once here
        // rather than on every repaint of the list.
        List<Entry> entries = new ArrayList<>();
        for (CapturedError captured : recent) entries.add(new Entry(captured, label(captured)));

        SimpleListCellRenderer<Entry> renderer = new SimpleListCellRenderer<>() {
            @Override public void customize(@NotNull JList<? extends Entry> list, Entry entry,
                                            int index, boolean selected, boolean focused) {
                setText(entry == null ? "" : entry.label());
            }
        };

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries)
            .setTitle("Which failure should be looked at?")
            .setRenderer(renderer)
            .setItemChosenCallback(entry -> ErrorAssistant.analyse(project, entry.captured()))
            .setMovable(true)
            .setResizable(true)
            .createPopup()
            .showCenteredInCurrentWindow(project);
    }

    /** One captured failure with the label already read out of its trace. */
    private record Entry(CapturedError captured, String label) { }

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");

    private static String label(CapturedError captured) {
        ErrorParser.ParsedError parsed = ErrorParser.parse(captured.text(), captured.source());
        String headline = parsed != null ? parsed.headline() : captured.firstLine();
        return "<html><b>" + escape(headline) + "</b><br><span style='color:gray'>"
             + escape(captured.origin()) + " · " + TIME.format(new Date(captured.at()))
             + "</span></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
