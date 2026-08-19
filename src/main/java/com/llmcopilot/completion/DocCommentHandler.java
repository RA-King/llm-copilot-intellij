package com.llmcopilot.completion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.ui.components.JBScrollPane;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.services.PromptBuilder;
import com.llmcopilot.settings.LLMCopilotSettings;
import com.llmcopilot.util.LanguageUtils;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Watches for // or /** typed as the ONLY content on a line.
 *
 * Rules:
 *  1. Immediate next line (line+1) must be a recognisable declaration — no gaps.
 *  2. A comment must NOT already exist above that declaration.
 *  3. After the LLM generates the comment, a PREVIEW DIALOG is shown.
 *     The user must explicitly click Accept before anything is written to the editor.
 *     Discard leaves the trigger line unchanged.
 */
public class DocCommentHandler implements DocumentListener {

    private final Editor editor;

    private static final Pattern TRIGGER = Pattern.compile(
        "^\\s*(?://\\s*$|/\\*\\*?\\s*(?:\\*/)?\\s*$|///\\s*$|#\\s*$)");
    private static final Pattern IS_COMMENT = Pattern.compile(
        "^\\s*(?://|/\\*|\\*|#|--|;)");
    private static final Pattern[] DECLARATION_PATTERNS = {
        Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|abstract|override|async|export|pub|open|suspend)\\s+)*(?:fun|func|function|def|fn|class|struct|interface|enum|record|trait|impl|type|object|module|namespace)\\b"),
        Pattern.compile("^\\s*(?:const|let|var|val)\\s+\\w+"),
        Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized|async|override|open|suspend|inline)\\s+)*[\\w<>\\[\\]]+\\s+\\w+\\s*\\("),
    };

    private static final ScheduledExecutorService BG = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "llm-doc-gen"); t.setDaemon(true); return t;
    });

    public DocCommentHandler(Editor editor) {
        this.editor = editor;
        editor.getDocument().addDocumentListener(this);
    }

    @Override
    public void documentChanged(DocumentEvent event) {
        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
        if (!s.isEnabled()) return;
        if (event.getNewFragment().length() > 5) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed()) return;
            Document doc    = editor.getDocument();
            if (doc.getTextLength() == 0 || doc.getLineCount() == 0) return;
            int offset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), doc.getTextLength()));
            int curLine = doc.getLineNumber(offset);
            if (curLine < 0 || curLine >= doc.getLineCount()) return;
            int lineStart   = doc.getLineStartOffset(curLine);
            int lineEnd     = doc.getLineEndOffset(curLine);
            String lineText = doc.getCharsSequence().subSequence(lineStart, lineEnd).toString();

            if (!TRIGGER.matcher(lineText).matches()) return;

            // Immediate next line must be a declaration
            int nextLine = curLine + 1;
            if (nextLine >= doc.getLineCount()) return;
            String nextText    = doc.getCharsSequence()
                .subSequence(doc.getLineStartOffset(nextLine), doc.getLineEndOffset(nextLine)).toString();
            String nextTrimmed = nextText.trim();
            if (nextTrimmed.isEmpty()) return;
            if (IS_COMMENT.matcher(nextTrimmed).find()) return;

            boolean isDecl = false;
            for (Pattern p : DECLARATION_PATTERNS) {
                if (p.matcher(nextTrimmed).find()) { isDecl = true; break; }
            }
            if (!isDecl) return;

            // Check that no comment already exists above the trigger line
            if (curLine > 0) {
                String lineAbove = doc.getCharsSequence()
                    .subSequence(doc.getLineStartOffset(curLine - 1),
                                 doc.getLineEndOffset(curLine - 1)).toString().trim();
                if (IS_COMMENT.matcher(lineAbove).find()) return;
            }
            for (int lb = curLine - 1; lb >= Math.max(0, curLine - 3); lb--) {
                String look = doc.getCharsSequence()
                    .subSequence(doc.getLineStartOffset(lb),
                                 doc.getLineEndOffset(lb)).toString().trim();
                if (look.isEmpty()) break;
                if (IS_COMMENT.matcher(look).find()) return;
                break;
            }

            final int    triggerLine = curLine;
            final String triggerText = lineText;
            final String declText    = nextText;

            // Debounce 300ms
            BG.schedule(() -> ApplicationManager.getApplication().invokeLater(() -> {
                if (editor.isDisposed()) return;
                Document d2 = editor.getDocument();
                if (triggerLine >= d2.getLineCount()) return;
                String nowLine = d2.getCharsSequence()
                    .subSequence(d2.getLineStartOffset(triggerLine),
                                 d2.getLineEndOffset(triggerLine)).toString();
                if (!nowLine.trim().equals(triggerText.trim())) return;
                generateAndPreview(triggerLine, declText, lineText);
            }), 300, TimeUnit.MILLISECONDS);
        });
    }

    // ── Generate then show preview dialog ────────────────────────────────────

    private void generateAndPreview(int triggerLine, String declText, String triggerLineText) {
        String lang  = LanguageUtils.getLanguageId(editor);
        Document doc = editor.getDocument();
        int declLine = triggerLine + 1;
        int endLine  = Math.min(declLine + 20, doc.getLineCount() - 1);
        StringBuilder snippet = new StringBuilder(declText).append("\n");
        for (int i = declLine + 1; i <= endLine; i++) {
            snippet.append(doc.getCharsSequence()
                .subSequence(doc.getLineStartOffset(i), doc.getLineEndOffset(i))).append("\n");
        }
        String indent = triggerLineText.replaceAll("\\S.*$", "");

        BG.submit(() -> {
            try {
                List<LLMClient.ChatMessage> msgs = PromptBuilder.generateDocComment(snippet.toString(), lang);
                String raw = LLMClient.chat(msgs);
                if (raw == null || raw.isBlank()) return;
                String comment = formatDocComment(raw.trim(), lang, indent);

                // Show preview dialog on EDT — user must Accept before anything is written
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (editor.isDisposed()) return;
                    showPreviewDialog(comment, triggerLine);
                });
            } catch (Exception ex) {
                System.err.println("[LLM Copilot] doc comment error: " + ex.getMessage());
            }
        });
    }

    /**
     * Show the generated comment in a preview dialog.
     * Only writes to the editor when the user clicks Accept.
     */
    private void showPreviewDialog(String comment, int triggerLine) {
        // ── Preview panel ─────────────────────────────────────────────────────
        JTextArea preview = new JTextArea(comment);
        preview.setEditable(false);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        preview.setBackground(new Color(30, 30, 30));
        preview.setForeground(new Color(212, 212, 212));
        preview.setCaretColor(new Color(212, 212, 212));
        preview.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JBScrollPane scroll = new JBScrollPane(preview);
        int lines = Math.min(comment.split("\n").length + 1, 15);
        scroll.setPreferredSize(new Dimension(600, lines * 18 + 20));
        scroll.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80)));

        JLabel hint = new JLabel("<html><small>Press <b>Accept</b> to insert above the declaration, "
            + "or <b>Discard</b> to keep the comment trigger line as-is.</small></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.add(scroll, BorderLayout.CENTER);
        content.add(hint,   BorderLayout.SOUTH);

        // ── Dialog ────────────────────────────────────────────────────────────
        int choice = JOptionPane.showOptionDialog(
            null,
            content,
            "LLM Copilot — Generated Doc Comment (preview)",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            new Object[]{ "✓  Accept", "✗  Discard" },
            "✓  Accept"
        );

        if (choice == 0) { // Accept
            // Apply in a write action so it's undoable (Ctrl+Z)
            WriteCommandAction.runWriteCommandAction(editor.getProject(),
                "LLM Copilot: Insert Doc Comment", null, () -> {
                    Document d = editor.getDocument();
                    if (triggerLine >= d.getLineCount()) return;
                    int ls = d.getLineStartOffset(triggerLine);
                    int le = d.getLineEndOffset(triggerLine);
                    d.replaceString(ls, le, comment);
                    editor.getCaretModel().moveToOffset(
                        Math.min(ls + comment.length(), d.getTextLength()));
                });
        }
        // Discard: do nothing — trigger line stays as // or /**
    }

    // ── Comment formatter (unchanged) ────────────────────────────────────────


    /**
     * Strip any comment markers the LLM may have already placed on a line.
     * Prevents double-markers like "/// /// text" or "# # text".
     * Handles: ///  //!  //  /**  /*  *  *&#47;  #  --
     */
    private static String stripCommentMarkers(String line) {
        return line
            // Rust/C# ///  or  //!
            .replaceFirst("^///\\s?", "")
            .replaceFirst("^//!\\s?", "")
            // Block comment close  */
            .replaceFirst("^\\*/\\s?", "")
            // Block comment body   *
            .replaceFirst("^\\*\\s?", "")
            // Block comment open  /**  or  /*
            .replaceFirst("^/\\*\\*?\\s?", "")
            // Single-line  //
            .replaceFirst("^//\\s?", "")
            // Python / Ruby  #
            .replaceFirst("^#\\s?", "")
            // SQL / Lua  --
            .replaceFirst("^--\\s?", "")
            .trim();
    }

    private static String formatDocComment(String raw, String lang, String indent) {
        raw = raw.replaceAll("(?s)^```\\w*\\r?\\n?", "").replaceAll("\\r?\\n?```\\s*$", "").trim();
        String[] rawLines = raw.split("\\n");
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String rl : rawLines) {
            // Strip any comment markers the LLM pre-applied to avoid double-prefixes
            String t = stripCommentMarkers(rl.trim());
            if (t.isEmpty()) { lines.add(""); continue; }
            String[] words = t.split("\\s+");
            if (words.length <= 15) { lines.add(t); continue; }
            StringBuilder cur = new StringBuilder(); int count = 0;
            for (String w : words) {
                if (count > 0 && count % 15 == 0) { lines.add(cur.toString().trim()); cur.setLength(0); }
                cur.append(w).append(" "); count++;
            }
            if (!cur.toString().isBlank()) lines.add(cur.toString().trim());
        }
        return switch (lang) {
            case "python", "ruby" -> {
                StringBuilder sb = new StringBuilder();
                for (String l : lines) sb.append(indent).append("# ").append(l).append("\n");
                yield sb.toString().stripTrailing();
            }
            case "rust" -> {
                StringBuilder sb = new StringBuilder();
                for (String l : lines) sb.append(indent).append("/// ").append(l).append("\n");
                yield sb.toString().stripTrailing();
            }
            case "go" -> {
                StringBuilder sb = new StringBuilder();
                for (String l : lines) sb.append(indent).append("// ").append(l).append("\n");
                yield sb.toString().stripTrailing();
            }
            default -> {
                StringBuilder sb = new StringBuilder(indent).append("/**\n");
                for (String l : lines) {
                    if (l.isEmpty()) sb.append(indent).append(" *\n");
                    else             sb.append(indent).append(" * ").append(l).append("\n");
                }
                sb.append(indent).append(" */");
                yield sb.toString();
            }
        };
    }

    public void dispose() {
        editor.getDocument().removeDocumentListener(this);
    }
}
