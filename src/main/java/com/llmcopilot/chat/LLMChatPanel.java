package com.llmcopilot.chat;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.services.PromptBuilder;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Full AI Chat panel — Junie AI Assistant level.
 *
 * Features:
 *  - Context bar: shows active file + selection info at all times
 *  - Slash commands: /fix /explain /refactor /test /doc /commit /help
 *  - Automatic selection context included in every message
 *  - Code proposals shown with Accept / Discard / Copy buttons
 *  - Accept replaces selection or inserts at caret in the active editor
 *  - Full conversation history with multi-turn context
 *  - Streaming-style status updates
 */
public class LLMChatPanel extends JPanel {

    private static final Map<String, LLMChatPanel> INSTANCES = new ConcurrentHashMap<>();

    private final Project   project;
    private final JPanel    messagesPanel;   // vertical box of message bubbles
    private final JScrollPane scrollPane;
    private final JTextArea inputArea;
    private final JButton   sendBtn, clearBtn, contextBtn;
    private final JLabel    contextLabel;    // shows "📄 file.java · 3 lines selected"

    private final List<LLMClient.ChatMessage> history = new ArrayList<>();
    private boolean includeContext = true;   // toggled by context button

    private static final ExecutorService BG = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-chat-bg"); t.setDaemon(true); return t;
    });

    // Styles
    private static final Color COL_USER_BG   = new Color(232, 240, 255);
    private static final Color COL_USER_FG   = new Color(20, 60, 140);
    private static final Color COL_ASST_BG   = new Color(245, 245, 245);
    private static final Color COL_ASST_FG   = new Color(30, 30, 30);
    private static final Color COL_SYS_BG    = new Color(255, 250, 220);
    private static final Color COL_SYS_FG    = new Color(100, 80, 0);
    private static final Color COL_ERR_BG    = new Color(255, 235, 235);
    private static final Color COL_ERR_FG    = new Color(160, 0, 0);
    private static final Color COL_CODE_BG   = new Color(30, 30, 30);
    private static final Color COL_CODE_FG   = new Color(212, 212, 212);

    // ── Constructor ───────────────────────────────────────────────────────────

    public LLMChatPanel(Project project) {
        super(new BorderLayout(0, 0));
        this.project = project;
        INSTANCES.put(projectKey(project), this);

        // ── Context bar (top) ─────────────────────────────────────────────────
        contextLabel = new JLabel("  No file open");
        contextLabel.setFont(contextLabel.getFont().deriveFont(11f));
        contextLabel.setForeground(new Color(80, 80, 80));

        contextBtn = new JButton("⊞ Context ON");
        contextBtn.setFont(contextBtn.getFont().deriveFont(10f));
        contextBtn.setFocusPainted(false);
        contextBtn.setMargin(JBUI.insets(2, 6, 2, 6));
        contextBtn.addActionListener(e -> toggleContext());

        JPanel contextBar = new JPanel(new BorderLayout(4, 0));
        contextBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 200, 200)),
            JBUI.Borders.empty(4, 6)));
        contextBar.setBackground(new Color(248, 248, 248));
        contextBar.add(contextLabel, BorderLayout.CENTER);
        contextBar.add(contextBtn,   BorderLayout.EAST);

        // ── Messages panel (scrollable vertical list) ─────────────────────────
        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(Color.WHITE);
        messagesPanel.setBorder(JBUI.Borders.empty(8, 8, 8, 8));

        scrollPane = new JScrollPane(messagesPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        appendSystemMessage("LLM Copilot ready. Type a message or use:\n" +
            "/fix — fix bugs\n/explain — explain code\n/refactor <instruction> — refactor\n" +
            "/test — generate tests\n/doc — generate doc comment\n/commit — commit message\n" +
            "/help — show commands");

        // ── Input area ────────────────────────────────────────────────────────
        inputArea = new JTextArea(3, 50);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        inputArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(180, 180, 200), 1, true),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        inputArea.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume(); send();
                }
            }
        });

        sendBtn  = new JButton("Send ↵");
        clearBtn = new JButton("Clear");
        sendBtn.setBackground(new Color(60, 120, 220));
        sendBtn.setForeground(Color.WHITE);
        sendBtn.setFocusPainted(false);
        sendBtn.setFont(sendBtn.getFont().deriveFont(Font.BOLD));
        sendBtn.addActionListener(e -> send());
        clearBtn.addActionListener(e -> clearAll());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnRow.add(clearBtn);
        btnRow.add(sendBtn);

        JPanel inputRow = new JPanel(new BorderLayout(0, 4));
        inputRow.setBorder(JBUI.Borders.empty(6));
        inputRow.add(new JBScrollPane(inputArea), BorderLayout.CENTER);
        inputRow.add(btnRow, BorderLayout.SOUTH);

        // ── Assemble ──────────────────────────────────────────────────────────
        add(contextBar,  BorderLayout.NORTH);
        add(scrollPane,  BorderLayout.CENTER);
        add(inputRow,    BorderLayout.SOUTH);

        // Refresh context bar every 2 seconds to track editor changes
        java.util.Timer refreshTimer = new java.util.Timer("llm-ctx-refresh", true);
        refreshTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() {
                ApplicationManager.getApplication().invokeLater(() -> refreshContextBar());
            }
        }, 1000, 2000);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static LLMChatPanel getInstance(Project p) {
        return p != null ? INSTANCES.get(projectKey(p)) : null;
    }

    /** Pre-fill the input box and optionally trigger send (used from actions) */
    public void askWith(String userMessage) {
        inputArea.setText(userMessage);
        send();
    }

    /** Called from actions that just want to show a result without requesting */
    public void appendAssistant(String text) {
        appendAssistantMessage(text, null);
    }

    // ── Context bar ───────────────────────────────────────────────────────────

    private void refreshContextBar() {
        EditorContextProvider.EditorContext ctx = EditorContextProvider.getActive(project);
        if (ctx.fileName.isEmpty()) {
            contextLabel.setText("  No file open");
        } else {
            String sel = ctx.hasSelection
                ? " · " + countLines(ctx.selectedText) + " line(s) selected"
                : "";
            contextLabel.setText("  📄 " + ctx.fileName + " (" + ctx.language + ")" + sel);
        }
    }

    private void toggleContext() {
        includeContext = !includeContext;
        contextBtn.setText(includeContext ? "⊞ Context ON" : "⊟ Context OFF");
        contextBtn.setForeground(includeContext ? new Color(0, 120, 0) : Color.GRAY);
    }

    // ── Send logic ────────────────────────────────────────────────────────────

    private void send() {
        String raw = inputArea.getText().trim();
        if (raw.isEmpty()) return;
        inputArea.setText("");
        sendBtn.setEnabled(false);

        // Capture editor context at send time
        EditorContextProvider.EditorContext ctx = includeContext
            ? EditorContextProvider.getActive(project)
            : EditorContextProvider.EditorContext.EMPTY;

        // ── Slash command dispatch ─────────────────────────────────────────────
        if (raw.startsWith("/")) {
            handleSlashCommand(raw, ctx);
            return;
        }

        // ── Normal chat with context ───────────────────────────────────────────
        String userDisplay = raw;
        String userForLLM;

        if (ctx.hasSelection) {
            userDisplay = raw + "\n\n[Context: " + ctx.fileName + " · " + countLines(ctx.selectedText) + " lines selected]";
            userForLLM  = raw + "\n\nSelected code in " + ctx.fileName + " (" + ctx.language + "):\n```" +
                ctx.language + "\n" + ctx.selectedText + "\n```";
        } else if (!ctx.surroundingContext.isEmpty()) {
            userForLLM = raw + "\n\nFile: " + ctx.fileName + " (" + ctx.language + ")\nContext:\n```" +
                ctx.language + "\n" + ctx.surroundingContext + "\n```";
        } else {
            userForLLM = raw;
        }

        appendUserBubble(userDisplay);
        final String forLLM = userForLLM;
        final EditorContextProvider.EditorContext capturedCtx = ctx;

        List<LLMClient.ChatMessage> msgs = buildMessages(forLLM);
        history.add(new LLMClient.ChatMessage("user", forLLM));

        JLabel thinking = appendThinkingBubble();

        BG.submit(() -> {
            try {
                String resp = LLMClient.chat(msgs);
                if (resp == null || resp.isBlank()) resp = "(No response — check provider settings)";
                history.add(new LLMClient.ChatMessage("assistant", resp));
                final String finalResp = resp;
                ApplicationManager.getApplication().invokeLater(() -> {
                    removeThinkingBubble(thinking);
                    appendAssistantMessage(finalResp, capturedCtx);
                    sendBtn.setEnabled(true);
                });
            } catch (Exception ex) {
                final String err = "Error: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                ApplicationManager.getApplication().invokeLater(() -> {
                    removeThinkingBubble(thinking);
                    appendErrorBubble(err);
                    sendBtn.setEnabled(true);
                });
            }
        });
    }

    // ── Slash command handler ─────────────────────────────────────────────────

    private void handleSlashCommand(String raw, EditorContextProvider.EditorContext ctx) {
        String[] parts   = raw.substring(1).split("\\s+", 2);
        String   cmd     = parts[0].toLowerCase();
        String   arg     = parts.length > 1 ? parts[1].trim() : "";
        String   code    = ctx.hasSelection ? ctx.selectedText : ctx.surroundingContext;
        String   lang    = ctx.language.isBlank() ? "text" : ctx.language;

        if (cmd.equals("help")) {
            appendSystemMessage(
                "Available commands:\n" +
                "/fix           — fix bugs in selected code\n" +
                "/explain       — explain selected code\n" +
                "/refactor <instruction>  — refactor with instruction\n" +
                "/test [framework]        — generate unit tests\n" +
                "/doc           — generate doc comment\n" +
                "/commit        — generate commit message from git diff\n" +
                "/help          — show this message"
            );
            sendBtn.setEnabled(true);
            return;
        }

        if (code.isBlank() && !cmd.equals("commit")) {
            appendErrorBubble("/" + cmd + " requires a selected code snippet. Select some code first.");
            sendBtn.setEnabled(true);
            return;
        }

        appendUserBubble("/" + cmd + (arg.isEmpty() ? "" : " " + arg));
        JLabel thinking = appendThinkingBubble();
        final EditorContextProvider.EditorContext capturedCtx = ctx;

        BG.submit(() -> {
            try {
                List<LLMClient.ChatMessage> msgs;
                boolean isCodeResp;
                msgs = switch (cmd) {
                    case "fix"      -> { isCodeResp = true;  yield PromptBuilder.fix(code, lang); }
                    case "explain"  -> { isCodeResp = false; yield PromptBuilder.explain(code, lang); }
                    case "refactor" -> { isCodeResp = true;  yield arg.isEmpty()
                        ? PromptBuilder.refactor(code, lang, "improve code quality and readability")
                        : PromptBuilder.refactor(code, lang, arg); }
                    case "test"     -> { isCodeResp = true;  yield PromptBuilder.generateTests(code, lang, arg.isEmpty() ? null : arg); }
                    case "doc"      -> { isCodeResp = true;  yield PromptBuilder.generateDocComment(code, lang); }
                    case "commit"   -> { isCodeResp = false; yield PromptBuilder.commitMessage(getGitDiff()); }
                    default         -> { isCodeResp = false;
                        yield List.of(new LLMClient.ChatMessage("user", raw)); }
                };

                // Re-assign isCodeResp as effectively-final
                final boolean showProposal = isCodeResp;
                String resp = LLMClient.chat(msgs);
                if (resp == null || resp.isBlank()) resp = "(No response)";
                final String finalResp = resp;

                ApplicationManager.getApplication().invokeLater(() -> {
                    removeThinkingBubble(thinking);
                    appendAssistantMessage(finalResp, showProposal ? capturedCtx : null);
                    sendBtn.setEnabled(true);
                });
            } catch (Exception ex) {
                final String err = "Error: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                ApplicationManager.getApplication().invokeLater(() -> {
                    removeThinkingBubble(thinking);
                    appendErrorBubble(err);
                    sendBtn.setEnabled(true);
                });
            }
        });
    }

    // ── Message rendering ─────────────────────────────────────────────────────

    private void appendUserBubble(String text) {
        JPanel bubble = makeBubble(text, "YOU", COL_USER_BG, COL_USER_FG, FlowLayout.RIGHT);
        addMessage(bubble);
    }

    private void appendSystemMessage(String text) {
        JPanel bubble = makeBubble(text, "SYSTEM", COL_SYS_BG, COL_SYS_FG, FlowLayout.LEFT);
        addMessage(bubble);
    }

    private void appendErrorBubble(String text) {
        JPanel bubble = makeBubble(text, "ERROR", COL_ERR_BG, COL_ERR_FG, FlowLayout.LEFT);
        addMessage(bubble);
    }

    private JLabel appendThinkingBubble() {
        JLabel label = new JLabel("  ● Thinking…");
        label.setForeground(Color.GRAY);
        label.setFont(label.getFont().deriveFont(Font.ITALIC, 12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        addMessage(label);
        return label;
    }

    private void removeThinkingBubble(JLabel label) {
        messagesPanel.remove(label);
        messagesPanel.revalidate();
    }

    /**
     * Render an assistant message. If capturedCtx is non-null, extract code blocks
     * and render each one as a CodeProposalPanel (with Accept/Discard buttons).
     */
    private void appendAssistantMessage(String text, EditorContextProvider.EditorContext ctx) {
        // Split text into alternating prose and code-block sections
        Pattern fence = Pattern.compile("(?s)```(\\w*)\n(.*?)```");
        Matcher m     = fence.matcher(text);

        int lastEnd = 0;
        boolean hadCode = false;

        while (m.find()) {
            // Prose before this code block
            String prose = text.substring(lastEnd, m.start()).trim();
            if (!prose.isEmpty()) {
                addMessage(makeBubble(prose, lastEnd == 0 ? "ASSISTANT" : "", COL_ASST_BG, COL_ASST_FG, FlowLayout.LEFT));
            }
            // Code block
            String blockLang = m.group(1).isBlank() ? (ctx != null ? ctx.language : "text") : m.group(1);
            String code      = m.group(2).trim();
            if (!code.isEmpty()) {
                hadCode = true;
                if (ctx != null) {
                    // Show as interactive proposal with Accept/Discard
                    final String finalCode = code;
                    final EditorContextProvider.EditorContext capturedCtx = ctx;
                    CodeProposalPanel proposal = new CodeProposalPanel(
                        project, code, blockLang, ctx,
                        accepted -> {
                            EditorContextProvider.applyToEditor(project, accepted, capturedCtx);
                            appendSystemMessage("✓ Code applied to " + capturedCtx.fileName);
                        },
                        () -> appendSystemMessage("Discarded.")
                    );
                    proposal.setAlignmentX(Component.LEFT_ALIGNMENT);
                    proposal.setMaximumSize(new Dimension(Integer.MAX_VALUE, proposal.getPreferredSize().height));
                    addMessage(proposal);
                } else {
                    // No context — plain code display
                    addMessage(makeCodeBlock(code, blockLang));
                }
            }
            lastEnd = m.end();
        }

        // Remaining prose after last code block
        String tail = text.substring(lastEnd).trim();
        if (!tail.isEmpty()) {
            addMessage(makeBubble(tail, hadCode ? "" : "ASSISTANT", COL_ASST_BG, COL_ASST_FG, FlowLayout.LEFT));
        }

        // If no code blocks at all, just show full text as prose
        if (lastEnd == 0) {
            addMessage(makeBubble(text, "ASSISTANT", COL_ASST_BG, COL_ASST_FG, FlowLayout.LEFT));
        }
    }

    // ── Bubble factory ────────────────────────────────────────────────────────

    private JPanel makeBubble(String text, String label, Color bg, Color fg, int align) {
        JPanel outer = new JPanel(new FlowLayout(align, 0, 2));
        outer.setOpaque(false);
        outer.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel inner = new JPanel(new BorderLayout(0, 2));
        inner.setBackground(bg);
        inner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.darker(), 1, true),
            JBUI.Borders.empty(6, 10)));
        inner.setMaximumSize(new Dimension(700, Integer.MAX_VALUE));

        if (!label.isEmpty()) {
            JLabel lbl = new JLabel(label);
            lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 9f));
            lbl.setForeground(fg.darker());
            inner.add(lbl, BorderLayout.NORTH);
        }

        JTextPane body = new JTextPane();
        body.setEditable(false);
        body.setOpaque(false);
        body.setForeground(fg);
        body.setFont(UIManager.getFont("Label.font").deriveFont(13f));

        // Render inline code `…` in monospace
        StyledDocument doc = body.getStyledDocument();
        Style normal = doc.addStyle("n", null);
        StyleConstants.setFontFamily(normal, Font.SANS_SERIF);
        StyleConstants.setFontSize(normal, 13);
        StyleConstants.setForeground(normal, fg);

        Style mono = doc.addStyle("m", normal);
        StyleConstants.setFontFamily(mono, Font.MONOSPACED);
        StyleConstants.setBackground(mono, new Color(220, 220, 220));
        StyleConstants.setFontSize(mono, 12);

        renderInlineText(doc, text, normal, mono);
        inner.add(body, BorderLayout.CENTER);

        outer.add(inner);
        return outer;
    }

    private JPanel makeCodeBlock(String code, String lang) {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(COL_CODE_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 60), 1, true),
            JBUI.Borders.empty(2)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Header
        JPanel hdr = new JPanel(new BorderLayout());
        hdr.setBackground(new Color(50, 50, 50));
        hdr.setBorder(JBUI.Borders.empty(2, 8));
        JLabel langLabel = new JLabel(lang.isEmpty() ? "code" : lang);
        langLabel.setForeground(new Color(180, 180, 180));
        langLabel.setFont(langLabel.getFont().deriveFont(Font.BOLD, 10f));
        JButton copyBtn = new JButton("Copy");
        copyBtn.setFont(copyBtn.getFont().deriveFont(9f));
        copyBtn.setFocusPainted(false);
        copyBtn.addActionListener(e ->
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(code), null));
        hdr.add(langLabel, BorderLayout.WEST);
        hdr.add(copyBtn,   BorderLayout.EAST);

        JTextArea codeArea = new JTextArea(code);
        codeArea.setEditable(false);
        codeArea.setBackground(COL_CODE_BG);
        codeArea.setForeground(COL_CODE_FG);
        codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        codeArea.setMargin(JBUI.insets(8));
        codeArea.setTabSize(4);

        int lines = Math.min(code.split("\n").length, 15);
        codeArea.setRows(lines);

        panel.add(hdr, BorderLayout.NORTH);
        panel.add(new JScrollPane(codeArea), BorderLayout.CENTER);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height + 60));
        return panel;
    }

    private void renderInlineText(StyledDocument doc, String text, Style normal, Style mono) {
        // Split on `inline code` markers
        String[] parts = text.split("`", -1);
        try {
            for (int i = 0; i < parts.length; i++) {
                doc.insertString(doc.getLength(), parts[i], i % 2 == 0 ? normal : mono);
            }
        } catch (BadLocationException ignored) {}
    }

    private void addMessage(Component c) {
        if (c instanceof JPanel p) {
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getMaximumSize().height));
        }
        messagesPanel.add(c);
        messagesPanel.add(Box.createVerticalStrut(6));
        messagesPanel.revalidate();
        // Scroll to bottom
        SwingUtilities.invokeLater(() -> {
            JScrollBar sb = scrollPane.getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
    }

    private void clearAll() {
        history.clear();
        messagesPanel.removeAll();
        messagesPanel.revalidate();
        messagesPanel.repaint();
        appendSystemMessage("Chat cleared. Ready.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<LLMClient.ChatMessage> buildMessages(String userMessage) {
        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new LLMClient.ChatMessage("system",
            "You are an expert AI programming assistant embedded in IntelliJ IDEA. " +
            "Help with code, debugging, architecture, and best practices. " +
            "When proposing code changes, always wrap the code in ```language\\n...``` fences " +
            "so the IDE can offer to apply them directly. " +
            "Be concise and precise. Reference the user's actual code when relevant."));
        msgs.addAll(history);
        msgs.add(new LLMClient.ChatMessage("user", userMessage));
        return msgs;
    }

    private String getGitDiff() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--cached");
            pb.directory(new java.io.File(Objects.requireNonNull(project.getBasePath())));
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes());
            if (out.isBlank()) {
                pb = new ProcessBuilder("git", "diff");
                pb.directory(new java.io.File(project.getBasePath()));
                proc = pb.start();
                out = new String(proc.getInputStream().readAllBytes());
            }
            return out.isBlank() ? "No git diff available." : out;
        } catch (Exception e) {
            return "Could not retrieve git diff: " + e.getMessage();
        }
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        return s.split("\n").length;
    }

    private static String projectKey(Project p) {
        String key = p.getBasePath();
        return key != null ? key : p.getName();
    }
}
