package com.llmcopilot.completion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.llmcopilot.index.ProjectIndexService;
import com.llmcopilot.index.SourceSummary;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.services.PromptBuilder;
import com.llmcopilot.settings.LLMCopilotSettings;
import com.llmcopilot.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inline completion handler.
 *
 * <p>Whether a suggestion may appear at all is {@link SuggestionGate}'s
 * decision, not this class's. The rules about writing over the author's code,
 * about deletions, about strings and comments and about what has already been
 * dismissed are the same rules wherever they are asked, and keeping them in
 * one pure function is what stops the debounce timer firing for a caret the
 * trigger logic then declines.
 *
 * <p>What this class owns is the timing and the plumbing:
 *
 * <ul>
 *   <li><b>Context window</b> — ghost text is dismissed and no new suggestion
 *       fires when the caret moves more than {@link #CONTEXT_WINDOW} lines from
 *       where the last one was triggered. A caret listener handles the jump;
 *       {@code runTriggerLogic} guards again, for a debounce that fires late.</li>
 *   <li><b>Typing through</b> — when the author types the characters a
 *       suggestion was proposing, the rest of that suggestion is shown straight
 *       from the continuation cache, with no round trip at all.</li>
 *   <li><b>Pacing</b> — the wait before asking is set from how fast the model
 *       has actually been answering, rather than from a fixed guess.</li>
 * </ul>
 */
public class InlineCompletionHandler implements DocumentListener {

    private static final int CONTEXT_WINDOW = 5; // lines up and down

    /** Ceiling on the resolved-declaration block; keeps prompts from bloating while typing. */
    private static final int RELATED_BUDGET_CHARS = 2000;

    private final Editor         editor;
    private final GhostTextManager ghost;

    private ScheduledFuture<?>   pendingTask;
    private final AtomicInteger  generation    = new AtomicInteger(0);
    private volatile int         lastTriggerLine = -1; // line where last suggestion was triggered

    private static final ScheduledExecutorService DEBOUNCE =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "llm-debounce"); t.setDaemon(true); return t;
        });
    private static final ExecutorService LLM_POOL =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "llm-inline"); t.setDaemon(true); return t;
        });

    private static final Map<String, String> CACHE = Collections.synchronizedMap(
        new LinkedHashMap<>(100, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                return size() > 100;
            }
        }
    );

    // ── Shared ghost-text state ──────────────────────────────────────────────
    //
    // Not per-editor. What the author dismissed, whether they are typing
    // forward and how fast the model is are properties of the session, and the
    // pacing only means anything measured across every completion in it.

    private static final GhostTextPacing.TypingTracker     TYPING       = new GhostTextPacing.TypingTracker();
    private static final GhostTextPacing.DismissalMemory   DISMISSALS   = new GhostTextPacing.DismissalMemory();
    private static final GhostTextPacing.ContinuationCache CONTINUATION = new GhostTextPacing.ContinuationCache();
    private static final GhostTextPacing.AdaptiveDebounce  PACING       = new GhostTextPacing.AdaptiveDebounce(150, 600);

    private static volatile long lastLatencyMs = 0;

    private static final Pattern DOC_TRIGGER = Pattern.compile(
        "^\\s*(?://\\s*$|/\\*\\*?\\s*(?:\\*/)?\\s*$|///\\s*$|#\\s*$)");
    private static final Pattern WORD = Pattern.compile("\\b([A-Za-z_$][\\w$]{2,})\\b");

    // ── Caret listener: dismiss ghost text when cursor moves > CONTEXT_WINDOW lines ──
    private final CaretListener caretListener = new CaretListener() {
        @Override
        public void caretPositionChanged(CaretEvent event) {
            // A caret that moves without the document changing has left
            // whatever was proposed behind.
            CONTINUATION.clear();

            int curLine = event.getNewPosition().line;
            if (lastTriggerLine >= 0 && Math.abs(curLine - lastTriggerLine) > CONTEXT_WINDOW) {
                // Cancel any pending debounce — cursor is too far from trigger point
                if (pendingTask != null) { pendingTask.cancel(false); }
                generation.incrementAndGet(); // invalidate any in-flight LLM call
                lastTriggerLine = -1;
                ghost.dismiss();
            }
        }
    };

    public InlineCompletionHandler(Editor editor, GhostTextManager ghost) {
        this.editor = editor;
        this.ghost  = ghost;
        editor.getDocument().addDocumentListener(this);
        editor.getCaretModel().addCaretListener(caretListener);
    }

    // ── DocumentListener ──────────────────────────────────────────────────────

    @Override
    public void documentChanged(DocumentEvent event) {
        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
        if (!s.isEnabled() || !s.isAutoTrigger()) return;

        // Ahead of every other decision about this change: a deletion has to
        // suppress the suggestion it would otherwise trigger.
        TYPING.note(event.getNewFragment().length(), event.getOldFragment().length());

        if (event.getNewFragment().length() > 50) { CONTINUATION.clear(); return; } // paste

        // Deleting is not a request for a suggestion.
        if (!TYPING.isTypingForward()) {
            if (pendingTask != null) { pendingTask.cancel(false); }
            CONTINUATION.clear();
            return;
        }

        if (pendingTask != null) { pendingTask.cancel(false); }
        int gen = generation.incrementAndGet();

        PACING.configure(s.getMinDebounceMs(), s.getDebounceMs());
        int wait = s.isAdaptiveDebounce() ? PACING.currentMs() : s.getDebounceMs();

        pendingTask = DEBOUNCE.schedule(() ->
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!editor.isDisposed()) runTriggerLogic(gen);
            }), wait, TimeUnit.MILLISECONDS);
    }

    public void triggerCompletion() {
        // An explicit invoke is the author asking, so it overrides the typing
        // and dismissal rules — but not the rules about writing over code.
        TYPING.noteExplicitInvoke();
        DISMISSALS.clear();

        int gen = generation.incrementAndGet();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!editor.isDisposed()) runTriggerLogic(gen);
        });
    }

    // ── Core trigger logic (EDT) ──────────────────────────────────────────────

    private void runTriggerLogic(int gen) {
        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
        if (!s.isEnabled()) return;
        if (generation.get() != gen) return;

        Document doc   = editor.getDocument();
        // Guard: empty document — nothing to complete
        if (doc.getTextLength() == 0 || doc.getLineCount() == 0) return;

        int offset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), doc.getTextLength()));

        int line = doc.getLineNumber(offset);
        if (line < 0 || line >= doc.getLineCount()) return;

        int lineStart  = doc.getLineStartOffset(line);
        int lineEnd    = doc.getLineEndOffset(line);
        offset         = Math.max(lineStart, Math.min(offset, lineEnd));
        String lineText = doc.getCharsSequence().subSequence(lineStart, lineEnd).toString();
        int    caretInLine = Math.min(offset - lineStart, lineText.length());
        String prefix   = lineText.substring(0, caretInLine);
        String suffix   = lineText.substring(caretInLine);

        // ── Guard — if the caret drifted past the window since the last
        // trigger, the debounce fired late and the suggestion is stale.
        if (lastTriggerLine >= 0 && Math.abs(line - lastTriggerLine) > CONTEXT_WINDOW) {
            ghost.dismiss();
            return;
        }

        // ── Suppress: pure doc-comment trigger
        if (DOC_TRIGGER.matcher(prefix).matches()) return;

        String fileKey = getFilename();

        // ── Typed through an existing suggestion ─────────────────────────────
        // Answered before anything else is computed: it is the one path that
        // owes the author no latency at all.
        String carried = CONTINUATION.continuation(fileKey, line, prefix);
        if (carried != null) {
            lastTriggerLine = line;
            ghost.showSuggestion(carried, offset);
            return;
        }

        String  keyword   = LanguageUtils.extractKeyword(prefix, editor);
        boolean isKeyword = keyword != null;

        // ── Determine intent and structural guide
        //
        // The two analyses answer different questions. StructureAnalyzer reads the shape of
        // the text to decide WHAT belongs here (a constructor, the next method, an enum case).
        // PSI reads the language's own parse tree to establish WHERE the caret is, with the
        // real enclosing signature. PSI leads when it resolved something and the text analysis
        // carries the suggestion; when PSI is unavailable the text analysis stands alone.
        final String intent;
        boolean isBlankLine = prefix.trim().isEmpty();

        String textGuide = null;
        if (isBlankLine && !isKeyword) {
            StructureAnalyzer.StructureContext sc = StructureAnalyzer.analyse(editor, offset);
            intent    = sc.kind == StructureAnalyzer.StructureKind.TOP_LEVEL ? "new-block" : "new-statement";
            textGuide = buildStructGuide(sc);
        } else {
            intent = "completing-started";
        }

        // What the code so far is working towards. Text-based and cheap, so unlike the PSI
        // collector it answers even when the document is uncommitted or the language has no
        // reference resolution.
        String language = LanguageUtils.getLanguageId(editor);
        IntentInference.Reading reading = s.isIntentInference()
            ? IntentInference.read(editor, offset, language)
            : IntentInference.Reading.EMPTY;
        final String intentReading = IntentInference.render(reading);

        // ── The gate ─────────────────────────────────────────────────────────
        // Every rule about whether a suggestion belongs here, and how much of
        // one, in a single verdict.
        String previousLine = line > 0
            ? doc.getText(new TextRange(doc.getLineStartOffset(line - 1), doc.getLineEndOffset(line - 1)))
            : "";

        SuggestionGate.Verdict verdict = SuggestionGate.evaluate(new SuggestionGate.Input(
            language, prefix, suffix, previousLine, reading.shape(),
            editor.getCaretModel().getCaretCount() == 1 && !editor.getSelectionModel().hasSelection(),
            TYPING.isTypingForward(),
            DISMISSALS.isDismissed(fileKey, line, prefix),
            new SuggestionGate.Limits(
                s.getMaxStatementLines(), s.getMaxBlockLines(), s.getMinIdentifierChars())
        ));

        if (!verdict.show()) return;

        CodeContext psiCtx      = PsiCodeContextCollector.collect(editor, offset);
        String      psiGuide    = psiCtx.structuralGuide(textGuide);
        final String structGuide = psiGuide != null ? psiGuide : textGuide;
        final String relatedCtx  = psiCtx.relatedBlock(RELATED_BUDGET_CHARS);

        // What the rest of the project declares about the names in play here.
        // Answered from the in-memory index, which is what makes cross-file
        // context affordable on every keystroke rather than only on invoke.
        final String projectCtx = projectContext(doc, line, offset, s);

        // Record trigger line for context-window tracking
        lastTriggerLine = line;

        // ── Build context
        int ctxLines  = s.getContextLines();
        int prefStart = doc.getLineStartOffset(Math.max(0, line - ctxLines));
        int sufEnd    = doc.getLineEndOffset(Math.min(doc.getLineCount() - 1, line + ctxLines / 4));
        // Capture as String copies on EDT before handing to background thread.
        // getCharsSequence() is only safe to call on the EDT or under read lock.
        String fp  = doc.getText(new TextRange(prefStart, offset));
        String fs  = doc.getText(new TextRange(offset, sufEnd));
        String lang    = language;
        String fname   = fileKey;
        String lp      = prefix;
        String kw      = keyword;
        int    ins     = offset;
        int    ln      = line;
        String sg      = structGuide;
        String related = relatedCtx;
        String intent2 = intent;
        int    maxLines = verdict.maxLines();
        IntentInference.Shape shape = verdict.shape();

        // ── Cache check (key includes line number to prevent cross-line hits)
        String ctxTag   = "L" + line + "#"
            + Objects.hash(structGuide, relatedCtx, intentReading, projectCtx, maxLines) + ":";
        String cacheKey = (ctxTag + fp).length() > 400
            ? ctxTag + fp.substring(fp.length() - 380)
            : ctxTag + fp;
        String cached = CACHE.get(cacheKey);
        if (cached != null && generation.get() == gen) {
            CONTINUATION.remember(fname, ln, lp, cached);
            ghost.showSuggestion(cached, ins);
            return;
        }

        LLM_POOL.submit(() -> {
            if (generation.get() != gen) return;
            try {
                long started = System.currentTimeMillis();
                String prompt = PromptBuilder.completionPrompt(fp, fs, lang, fname, intent2, 0,
                                                              sg, related, kw, intentReading,
                                                              projectCtx, maxLines);
                String raw = LLMClient.complete(prompt, tokenBudget(shape));

                long latency = System.currentTimeMillis() - started;
                lastLatencyMs = latency;
                PACING.observe(latency);

                if (raw == null || raw.isBlank()) return;
                if (generation.get() != gen) return;

                String formatted = IndentUtils.reindent(raw, lp, editor);

                // The prompt asked for a bounded answer; this enforces it. A
                // model that wrote past the budget is cut at the last point the
                // snippet is balanced, and refused when there is no such point
                // — half a block is worse than nothing.
                formatted = SuggestionGate.trimToBudget(formatted, maxLines);
                if (formatted == null || formatted.isBlank()) return;

                String guarded = DuplicateGuard.guard(formatted, fp, fs, lp);
                if (guarded == null || guarded.isBlank()) return;
                if (generation.get() != gen) return;

                CACHE.put(cacheKey, guarded);
                CONTINUATION.remember(fname, ln, lp, guarded);
                ghost.showSuggestion(guarded, ins);
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                if (!msg.contains("405") && !msg.contains("404") && !msg.contains("cancelled")) {
                    System.err.println("[LLM Copilot] inline: " + msg);
                }
            }
        });
    }

    // ── Project context ───────────────────────────────────────────────────────

    /**
     * What the rest of the project declares about the names in play at the
     * caret: everything this file imports, plus the identifiers written just
     * above it. A few map lookups against the index, so it is affordable here.
     */
    private String projectContext(Document doc, int line, int offset, LLMCopilotSettings s) {
        if (!s.isProjectIndexEnabled() || s.getProjectCompletionChars() <= 0) return "";

        Project project = editor.getProject();
        if (project == null) return "";

        ProjectIndexService index = ProjectIndexService.getInstance(project);
        if (index == null || !index.isReady()) return "";

        VirtualFile file = FileDocumentManager.getInstance().getFile(doc);
        if (file == null) return "";

        String base = project.getBasePath();
        String path = file.getPath();
        String relative = base != null && path.startsWith(base + "/")
            ? path.substring(base.length() + 1) : path;

        Set<String> names = new LinkedHashSet<>();
        SourceSummary.FileSummary summary = index.get(relative);
        if (summary != null) names.addAll(summary.importedNames());

        int from = doc.getLineStartOffset(Math.max(0, line - 30));
        Matcher m = WORD.matcher(doc.getText(new TextRange(from, offset)));
        while (m.find() && names.size() < 40) names.add(m.group(1));

        try {
            return index.completionContext(relative, names, s.getProjectCompletionChars());
        } catch (Exception ex) {
            return "";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * How much room to give the model. Finishing a half-written expression needs a handful
     * of tokens; the body of a block that was just opened needs the configured maximum.
     */
    private static int tokenBudget(IntentInference.Shape shape) {
        return switch (shape) {
            case EXPRESSION -> 64;
            case STATEMENT  -> 160;
            case BLOCK      -> Integer.MAX_VALUE;
        };
    }

    private static String buildStructGuide(StructureAnalyzer.StructureContext ctx) {
        if (ctx.kind == StructureAnalyzer.StructureKind.TOP_LEVEL)
            return "Top level — suggest the next logical declaration.";
        String c = ctx.containerType + " \"" + ctx.containerName + "\"";
        return switch (ctx.suggestion) {
            case CONSTRUCTOR    -> "Inside " + c + " — no constructor yet. Generate one.";
            case GETTER_SETTER  -> "Inside " + c + " — suggest getter+setter for the next field.";
            case NEXT_METHOD    -> "Inside " + c + " — suggest the next logical method.";
            case NEXT_STATEMENT -> "Inside a function body — suggest the next statement(s).";
            case ENUM_CASE      -> "Inside enum " + ctx.containerName + " — suggest the next case.";
            default             -> "Inside " + c + " — suggest the next logical member.";
        };
    }

    private String getFilename() {
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return vf != null ? vf.getName() : "untitled";
    }

    // ── Dismissal ─────────────────────────────────────────────────────────────

    /**
     * Called when the author presses Escape on a suggestion. The IDE hides the
     * ghost text itself; what it cannot do is remember that they said no, which
     * is the part that stops the same suggestion reappearing on the very next
     * keystroke.
     */
    public static void recordDismissal(Editor editor) {
        if (editor == null || editor.isDisposed()) return;

        Document doc = editor.getDocument();
        if (doc.getLineCount() == 0) return;

        int offset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), doc.getTextLength()));
        int line = doc.getLineNumber(offset);
        String prefix = doc.getText(new TextRange(doc.getLineStartOffset(line), offset));

        VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
        DISMISSALS.record(vf != null ? vf.getName() : "untitled", line, prefix);
        CONTINUATION.clear();
    }

    /** Median round trip, last round trip and current wait, for the diagnostics action. */
    public static long[] pacingStats() {
        return new long[]{ PACING.medianMs(), lastLatencyMs, PACING.currentMs() };
    }

    public static void clearCache() {
        CACHE.clear();
        CONTINUATION.clear();
        DISMISSALS.clear();
        PsiCodeContextCollector.clearCache();
    }

    public void dispose() {
        editor.getDocument().removeDocumentListener(this);
        editor.getCaretModel().removeCaretListener(caretListener);
        if (pendingTask != null) pendingTask.cancel(false);
        generation.incrementAndGet();
    }
}
