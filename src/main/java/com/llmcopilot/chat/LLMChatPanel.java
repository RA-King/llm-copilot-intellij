package com.llmcopilot.chat;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.llmcopilot.actions.ProblemsAtCaret;
import com.llmcopilot.errors.CapturedError;
import com.llmcopilot.errors.ErrorCaptureService;
import com.llmcopilot.index.ProjectIndexService;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.services.PromptBuilder;
import com.llmcopilot.util.Git;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full AI Chat panel — Junie AI Assistant level.
 *
 * Features:
 *  - Context bar: shows active file + selection info at all times
 *  - Slash commands: /fix /explain /refactor /test /doc /commit /help, plus the
 *    repository's own .github/prompts/*.prompt.md files
 *  - # references: #file:path #selection #codebase #problems #changes #terminalLastCommand
 *  - Repository instructions (.github/copilot-instructions.md and friends) in every request
 *  - Automatic selection context included in every message
 *  - Code proposals shown with Accept / Discard / Copy buttons
 *  - Accept replaces selection or inserts at caret in the active editor
 *  - Full conversation history with multi-turn context
 *  - Replies stream in as they are written, and can be stopped
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
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean running = false;
    private List<PromptFiles.PromptFile> promptFiles = List.of();

    /** Characters of any one attachment sent with a message. */
    private static final int ATTACHMENT_BUDGET = 24_000;

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
        messagesPanel = new MessagesPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(Color.WHITE);
        messagesPanel.setBorder(JBUI.Borders.empty(8, 8, 8, 8));

        scrollPane = new JScrollPane(messagesPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        // Bubbles re-wrap to fit, so there is never anything to scroll to sideways.
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        // Widths are derived from the viewport, so every resize needs a fresh layout pass.
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                messagesPanel.revalidate();
                messagesPanel.repaint();
            }
        });

        appendSystemMessage("LLM Copilot ready. Type a message or use:\n" +
            "/fix — fix bugs\n/explain — explain code\n/refactor <instruction> — refactor\n" +
            "/test — generate tests\n/doc — generate doc comment\n/commit — commit message\n" +
            "/help — commands, prompt files and # references");

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
                    e.consume();
                    if (!running) send();
                }
            }
        });

        sendBtn  = new JButton("Send ↵");
        clearBtn = new JButton("Clear");
        sendBtn.setBackground(new Color(60, 120, 220));
        sendBtn.setForeground(Color.WHITE);
        sendBtn.setFocusPainted(false);
        sendBtn.setFont(sendBtn.getFont().deriveFont(Font.BOLD));
        sendBtn.addActionListener(e -> { if (running) cancelled.set(true); else send(); });
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

    /**
     * Starts a turn the user did not type. The error pane composes its own
     * question — the failure, the source around it and the approach chosen —
     * and hands it over whole; {@code display} is the shorter version that
     * appears in the bubble. The reply joins the conversation like any other,
     * so the user can carry straight on asking about it.
     */
    public void ask(String display, List<LLMClient.ChatMessage> messages) {
        appendUserBubble(display);
        streamReply(() -> messages, EditorContextProvider.getActive(project), true);
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
        if (raw.isEmpty() || running) return;
        inputArea.setText("");

        // Capture editor context at send time
        EditorContextProvider.EditorContext ctx = includeContext
            ? EditorContextProvider.getActive(project)
            : EditorContextProvider.EditorContext.EMPTY;

        ChatReferences.Parsed parsed = ChatReferences.parse(raw);

        // ── Slash command dispatch ─────────────────────────────────────────────
        String question = parsed.text();
        if (parsed.command() != null) {
            question = handleSlashCommand(raw, parsed, ctx);
            if (question == null) return;
        }

        // ── Normal chat with context ───────────────────────────────────────────
        String userDisplay = raw;
        String contextBlock;

        if (ctx.hasSelection) {
            userDisplay = raw + "\n\n[Context: " + ctx.fileName + " · " + countLines(ctx.selectedText) + " lines selected]";
            contextBlock = "Selected code in " + ctx.fileName + " (" + ctx.language + "):\n```" +
                ctx.language + "\n" + ctx.selectedText + "\n```";
        } else if (!ctx.surroundingContext.isEmpty()) {
            contextBlock = "File: " + ctx.fileName + " (" + ctx.language + ")\nContext:\n```" +
                ctx.language + "\n" + ctx.surroundingContext + "\n```";
        } else {
            contextBlock = "";
        }

        appendUserBubble(userDisplay);

        // Whatever the references need from the editor is read now, on the EDT;
        // the slow parts — files, git, the index — are read in the background.
        List<ChatReferences.Reference> refs = parsed.references();
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        final String finalQuestion = question;
        final EditorContextProvider.EditorContext capturedCtx = ctx;

        streamReply(() -> {
            List<ChatReferences.Attachment> attachments = resolve(refs, finalQuestion, capturedCtx, editor);
            if (!attachments.isEmpty()) {
                String used = String.join(" · ", attachments.stream().map(ChatReferences.Attachment::label).toList());
                ApplicationManager.getApplication().invokeLater(() ->
                    appendSystemMessage("Used " + attachments.size() + " reference" + (attachments.size() == 1 ? "" : "s") + ": " + used));
            }
            StringBuilder forLLM = new StringBuilder(finalQuestion);
            if (!contextBlock.isEmpty()) forLLM.append("\n\n").append(contextBlock);
            String rendered = ChatReferences.render(attachments);
            if (!rendered.isEmpty()) forLLM.append("\n\n").append(rendered);
            return buildMessages(forLLM.toString());
        }, capturedCtx, true);
    }

    /**
     * Runs a request and streams the reply into a bubble that grows as it is
     * written; the finished reply is then re-rendered with its code proposals.
     * {@code remember} records the question and the reply in the conversation.
     */
    private void streamReply(Callable<List<LLMClient.ChatMessage>> request,
                             EditorContextProvider.EditorContext ctx, boolean remember) {
        String relPath = activeRelativePath();
        JLabel thinking = appendThinkingBubble();
        setRunning(true);

        StringBuffer streamed = new StringBuffer();
        AtomicBoolean updateQueued = new AtomicBoolean(false);
        Component[] live = { null };

        BG.submit(() -> {
            try {
                List<LLMClient.ChatMessage> msgs = CustomInstructions.withInstructions(request.call(),
                    CustomInstructions.forFile(project.getBasePath(), relPath));
                if (remember) history.add(msgs.get(msgs.size() - 1));

                String resp = LLMClient.chatStream(msgs, delta -> {
                    streamed.append(delta);
                    if (updateQueued.compareAndSet(false, true)) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            updateQueued.set(false);
                            if (!running) return;
                            removeThinkingBubble(thinking);
                            live[0] = replaceLive(live[0], streamed.toString());
                        });
                    }
                }, cancelled::get);

                boolean stopped = cancelled.get();
                if (resp == null || resp.isBlank()) resp = stopped ? "" : "(No response — check provider settings)";
                if (remember && !resp.isEmpty()) history.add(new LLMClient.ChatMessage("assistant", resp));
                final String finalResp = resp;
                ApplicationManager.getApplication().invokeLater(() -> {
                    setRunning(false);
                    removeThinkingBubble(thinking);
                    removeLive(live[0]);
                    if (!finalResp.isEmpty()) appendAssistantMessage(finalResp, ctx);
                    if (stopped) appendSystemMessage("Stopped.");
                });
            } catch (Exception ex) {
                final String err = "Error: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                ApplicationManager.getApplication().invokeLater(() -> {
                    setRunning(false);
                    removeThinkingBubble(thinking);
                    removeLive(live[0]);
                    appendErrorBubble(err);
                });
            }
        });
    }

    private void setRunning(boolean value) {
        running = value;
        if (value) cancelled.set(false);
        sendBtn.setText(value ? "Stop ■" : "Send ↵");
        sendBtn.setEnabled(true);
    }

    /** Swaps the streaming bubble for one holding the text so far, in the same place. */
    private Component replaceLive(Component previous, String text) {
        Component next = makeBubble(text, "ASSISTANT", COL_ASST_BG, COL_ASST_FG, FlowLayout.LEFT);
        int at = previous == null ? -1 : indexOf(previous);
        if (at < 0) {
            addMessage(next);
        } else {
            messagesPanel.remove(at);
            messagesPanel.add(next, at);
            messagesPanel.revalidate();
            JScrollBar sb = scrollPane.getVerticalScrollBar();
            if (sb.getValue() + sb.getVisibleAmount() >= sb.getMaximum() - 60) {
                SwingUtilities.invokeLater(() -> sb.setValue(sb.getMaximum()));
            }
        }
        return next;
    }

    private void removeLive(Component live) {
        int at = live == null ? -1 : indexOf(live);
        if (at < 0) return;
        messagesPanel.remove(at);
        // and the spacer addMessage put after it
        if (at < messagesPanel.getComponentCount()) messagesPanel.remove(at);
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    private int indexOf(Component c) {
        Component[] all = messagesPanel.getComponents();
        for (int i = 0; i < all.length; i++) if (all[i] == c) return i;
        return -1;
    }

    // ── References ────────────────────────────────────────────────────────────

    private List<ChatReferences.Attachment> resolve(List<ChatReferences.Reference> refs, String question,
                                                    EditorContextProvider.EditorContext ctx, Editor editor) {
        List<ChatReferences.Attachment> out = new ArrayList<>();
        for (ChatReferences.Reference ref : refs) {
            try {
                ChatReferences.Attachment a = resolveOne(ref, question, ctx, editor);
                if (a == null) continue;
                String content = a.content().length() > ATTACHMENT_BUDGET
                    ? a.content().substring(0, ATTACHMENT_BUDGET) + "\n… (truncated)" : a.content();
                out.add(new ChatReferences.Attachment(a.label(), a.language(), content));
            } catch (Exception ex) {
                String what = ref.kind().name().toLowerCase(Locale.ROOT) + (ref.arg() != null ? " " + ref.arg() : "");
                ApplicationManager.getApplication().invokeLater(() ->
                    appendSystemMessage("Could not attach " + what + ": " + ex.getMessage()));
            }
        }
        return out;
    }

    private ChatReferences.Attachment resolveOne(ChatReferences.Reference ref, String question,
                                                 EditorContextProvider.EditorContext ctx, Editor editor) throws Exception {
        String base = project.getBasePath();
        switch (ref.kind()) {
            case SELECTION -> {
                EditorContextProvider.EditorContext active = ctx.hasSelection ? ctx
                    : ReadAction.compute(() -> EditorContextProvider.getActive(project));
                if (!active.hasSelection) return null;
                return new ChatReferences.Attachment("Selection — " + active.fileName, active.language, active.selectedText);
            }
            case FILE -> {
                VirtualFile vf = findFile(ref.arg());
                if (vf == null) throw new Exception("no such file in the project");
                String text = ReadAction.compute(() -> {
                    var doc = FileDocumentManager.getInstance().getDocument(vf);
                    return doc != null ? doc.getText() : new String(vf.contentsToByteArray(), vf.getCharset());
                });
                String ext = vf.getExtension() != null ? vf.getExtension() : "";
                return new ChatReferences.Attachment("File — " + relative(vf), ext, text);
            }
            case CODEBASE -> {
                ProjectIndexService index = ProjectIndexService.getInstance(project);
                if (!index.isReady()) throw new Exception("the project index is not built yet");
                String found = index.renderSearch(ChatReferences.searchTerms(question), 4000);
                String digest = index.renderDigest();
                return new ChatReferences.Attachment("Codebase — matching declarations", "",
                    digest + (found.isEmpty() ? "" : "\n\n" + found));
            }
            case PROBLEMS -> {
                if (editor == null) return null;
                List<ProblemsAtCaret.Problem> problems = ReadAction.compute(() -> ProblemsAtCaret.inFile(project, editor.getDocument()));
                StringBuilder text = new StringBuilder();
                for (ProblemsAtCaret.Problem p : problems) text.append(p.describe()).append('\n');
                return new ChatReferences.Attachment("Problems — " + ctx.fileName, "text",
                    text.length() == 0 ? "No problems reported." : text.toString());
            }
            case CHANGES -> {
                if (base == null) return null;
                String root = Git.run(base, "rev-parse", "--show-toplevel").trim();
                String diff = Git.uncommittedDiff(root);
                return new ChatReferences.Attachment("Uncommitted changes", "diff", diff.isBlank() ? "No uncommitted changes." : diff);
            }
            case TERMINAL_LAST_COMMAND -> {
                CapturedError last = ErrorCaptureService.getInstance(project).latest();
                if (last == null) throw new Exception("nothing has failed in a run, debug or terminal console yet");
                return new ChatReferences.Attachment("Last failure — " + last.origin(), "text", last.text());
            }
        }
        return null;
    }

    private VirtualFile findFile(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        String base = project.getBasePath();
        java.nio.file.Path path = java.nio.file.Path.of(spec);
        if (!path.isAbsolute() && base != null) path = java.nio.file.Path.of(base).resolve(spec);
        VirtualFile direct = LocalFileSystem.getInstance().findFileByNioFile(path.normalize());
        if (direct != null) return direct;
        String name = spec.substring(spec.lastIndexOf('/') + 1);
        return ReadAction.compute(() -> {
            for (VirtualFile vf : FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))) {
                if (vf.getPath().endsWith(spec.replaceFirst("^\\./", ""))) return vf;
            }
            return null;
        });
    }

    private String relative(VirtualFile vf) {
        String base = project.getBasePath();
        String path = vf.getPath();
        return base != null && path.startsWith(base + "/") ? path.substring(base.length() + 1) : vf.getName();
    }

    private String activeRelativePath() {
        VirtualFile[] open = FileEditorManager.getInstance(project).getSelectedFiles();
        return open.length > 0 ? relative(open[0]) : null;
    }

    // ── Slash command handler ─────────────────────────────────────────────────

    /** Commands answered as an ordinary chat turn, with these words in front of the question. */
    private static final Map<String, String> CHAT_COMMANDS = Map.of(
        "optimize", "Improve the performance of this code without changing its behaviour. Show the new code and explain the gain.",
        "new", "Lay out the files needed for what follows. For each one give its path as a heading and its full contents in a fenced block.");

    /**
     * Handles a slash command. Returns null when the command has been dealt with
     * here, or the question to send as an ordinary chat turn — for the chat
     * commands, the repository's prompt files, and anything unrecognised.
     */
    private String handleSlashCommand(String raw, ChatReferences.Parsed parsed, EditorContextProvider.EditorContext ctx) {
        String   cmd     = parsed.command();
        String   arg     = parsed.text();
        String   code    = ctx.hasSelection ? ctx.selectedText : ctx.surroundingContext;
        String   lang    = ctx.language.isBlank() ? "text" : ctx.language;

        if (cmd.equals("clear")) {
            clearAll();
            return null;
        }

        if (cmd.equals("help")) {
            promptFiles = PromptFiles.load(project.getBasePath());
            StringBuilder help = new StringBuilder(
                "Available commands:\n" +
                "/fix           — fix bugs in selected code\n" +
                "/explain       — explain selected code\n" +
                "/refactor <instruction>  — refactor with instruction\n" +
                "/test [framework]        — generate unit tests\n" +
                "/doc           — generate doc comment\n" +
                "/optimize      — make the selected code faster or leaner\n" +
                "/new <what>    — sketch the files for something new\n" +
                "/commit        — generate commit message from git diff\n" +
                "/clear         — start a new conversation\n" +
                "/help          — show this message");
            if (!promptFiles.isEmpty()) {
                help.append("\n\nFrom .github/prompts:");
                for (PromptFiles.PromptFile p : promptFiles) help.append("\n/").append(p.name()).append(" — ").append(p.description());
            }
            help.append("\n\nReferences, anywhere in a message:\n" +
                "#file:path — attach a file\n#selection — attach the selection\n" +
                "#codebase — search the project index for what the question is about\n" +
                "#problems — the errors and warnings in the active file\n" +
                "#changes — the uncommitted git diff\n" +
                "#terminalLastCommand — the last failure from a run, debug or terminal console");
            appendSystemMessage(help.toString());
            return null;
        }

        if (CHAT_COMMANDS.containsKey(cmd)) {
            String prompt = CHAT_COMMANDS.get(cmd);
            return arg.isEmpty() ? prompt : prompt + "\n\n" + arg;
        }

        if (!Set.of("fix", "explain", "refactor", "test", "tests", "doc", "commit").contains(cmd)) {
            promptFiles = PromptFiles.load(project.getBasePath());
            for (PromptFiles.PromptFile p : promptFiles) {
                if (!p.name().equals(cmd)) continue;
                String file = activeRelativePath();
                return PromptFiles.expand(p.body(), new PromptFiles.Variables(
                    ctx.selectedText, file != null ? file : "",
                    project.getBasePath() != null ? project.getBasePath() : "", arg));
            }
            return raw;
        }

        if (code.isBlank() && !cmd.equals("commit")) {
            appendErrorBubble("/" + cmd + " requires a selected code snippet. Select some code first.");
            return null;
        }

        appendUserBubble("/" + cmd + (arg.isEmpty() ? "" : " " + arg));
        boolean isCodeResp = !cmd.equals("explain") && !cmd.equals("commit");

        streamReply(() -> switch (cmd) {
            case "fix"      -> PromptBuilder.fix(code, lang);
            case "explain"  -> PromptBuilder.explain(code, lang);
            case "refactor" -> arg.isEmpty()
                ? PromptBuilder.refactor(code, lang, "improve code quality and readability")
                : PromptBuilder.refactor(code, lang, arg);
            case "test", "tests" -> PromptBuilder.generateTests(code, lang, arg.isEmpty() ? null : arg);
            case "doc"      -> PromptBuilder.generateDocComment(code, lang);
            default         -> PromptBuilder.commitMessage(getGitDiff());
        }, isCodeResp ? ctx : null, false);
        return null;
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
     * Renders an assistant reply as one bubble per section, per
     * {@link AssistantMessageParser}. With an editor context in hand each code block
     * becomes a CodeProposalPanel carrying Accept/Discard; without one it is shown
     * read-only, since there is no target to apply it to.
     */
    private void appendAssistantMessage(String text, EditorContextProvider.EditorContext ctx) {
        List<AssistantMessageParser.Segment> segments = AssistantMessageParser.parse(text);

        for (int i = 0; i < segments.size(); i++) {
            AssistantMessageParser.Segment seg = segments.get(i);

            if (!seg.isCode()) {
                // Only a reply that opens with prose carries the ASSISTANT header.
                addMessage(makeBubble(seg.text(), i == 0 ? "ASSISTANT" : "",
                    COL_ASST_BG, COL_ASST_FG, FlowLayout.LEFT));
                continue;
            }

            String blockLang = seg.language().isBlank()
                ? (ctx != null ? ctx.language : "text")
                : seg.language();

            if (ctx == null) {
                // No editor context — plain code display, nothing to apply it to.
                addMessage(makeCodeBlock(seg.text(), blockLang));
                continue;
            }

            final EditorContextProvider.EditorContext capturedCtx = ctx;
            CodeProposalPanel proposal = new CodeProposalPanel(
                project, seg.text(), blockLang, ctx,
                accepted -> {
                    EditorContextProvider.applyToEditor(project, accepted, capturedCtx);
                    appendSystemMessage("✓ Code applied to " + capturedCtx.fileName);
                },
                () -> appendSystemMessage("Discarded.")
            );
            proposal.setAlignmentX(Component.LEFT_ALIGNMENT);
            proposal.setMaximumSize(new Dimension(Integer.MAX_VALUE, proposal.getPreferredSize().height));
            addMessage(proposal);
        }
    }

    // ── Bubble factory ────────────────────────────────────────────────────────

    private JPanel makeBubble(String text, String label, Color bg, Color fg, int align) {
        JTextPane body = new JTextPane();

        MessageBubble inner = new MessageBubble(body);
        inner.setBackground(bg);
        inner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.darker(), 1, true),
            JBUI.Borders.empty(6, 10)));

        if (!label.isEmpty()) {
            JLabel lbl = new JLabel(label);
            lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 9f));
            lbl.setForeground(fg.darker());
            inner.add(lbl, BorderLayout.NORTH);
        }

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
        return new BubbleRow(inner, align == FlowLayout.RIGHT);
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
        // A bubble owns its own width and alignment; anything else (code blocks, proposals)
        // spans the full width and is pinned to its preferred height so BoxLayout cannot
        // stretch it into the leftover vertical space.
        if (c instanceof JPanel p && !(c instanceof BubbleRow)) {
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
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
        cancelled.set(true);
        history.clear();
        messagesPanel.removeAll();
        messagesPanel.revalidate();
        messagesPanel.repaint();
        appendSystemMessage("Chat cleared. Ready.");
    }

    /**
     * The message list. Tracking the viewport width is what makes bubbles wrap at all:
     * without it the box is as wide as its widest child asks to be, and the scroll pane
     * offers a horizontal scrollbar instead of re-flowing the text.
     */
    private static final class MessagesPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public boolean getScrollableTracksViewportWidth()     { return true; }
        @Override public boolean getScrollableTracksViewportHeight()    { return false; }

        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }
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
