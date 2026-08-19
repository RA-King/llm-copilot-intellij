package com.llmcopilot.completion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.llmcopilot.services.LLMClient;
import com.llmcopilot.services.PromptBuilder;
import com.llmcopilot.settings.LLMCopilotSettings;
import com.llmcopilot.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Inline completion handler.
 *
 * Fix 3 — Context window:
 *  Ghost text is dismissed and no new suggestion fires when the cursor moves
 *  more than CONTEXT_WINDOW lines away from the line where the last completion
 *  was triggered. This mirrors the VS Code "5 lines up, 5 lines down" rule.
 *
 *  Implementation uses two mechanisms:
 *   a) CaretListener dismisses ghost text immediately when the cursor jumps >5 lines.
 *   b) runTriggerLogic() guards: if the trigger line differs >5 from the current
 *      caret line, suppress silently (handles scheduled debounce that fires late).
 */
public class InlineCompletionHandler implements DocumentListener {

    private static final int CONTEXT_WINDOW = 5; // lines up and down

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

    private static final Pattern DOC_TRIGGER = Pattern.compile(
        "^\\s*(?://\\s*$|/\\*\\*?\\s*(?:\\*/)?\\s*$|///\\s*$|#\\s*$)");

    // ── Caret listener: dismiss ghost text when cursor moves > CONTEXT_WINDOW lines ──
    private final CaretListener caretListener = new CaretListener() {
        @Override
        public void caretPositionChanged(CaretEvent event) {
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
        if (event.getNewFragment().length() > 50) return; // paste

        if (pendingTask != null) { pendingTask.cancel(false); }
        int gen = generation.incrementAndGet();

        pendingTask = DEBOUNCE.schedule(() ->
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!editor.isDisposed()) runTriggerLogic(gen);
            }), s.getDebounceMs(), TimeUnit.MILLISECONDS);
    }

    public void triggerCompletion() {
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
        String prefix   = lineText.substring(0, Math.min(offset - lineStart, lineText.length()));
        int charsAfter  = lineEnd - offset;

        // ── Fix 3b: guard — if cursor drifted >5 lines since last trigger, suppress
        if (lastTriggerLine >= 0 && Math.abs(line - lastTriggerLine) > CONTEXT_WINDOW) {
            ghost.dismiss();
            return;
        }

        // ── Suppress: pure doc-comment trigger
        if (DOC_TRIGGER.matcher(prefix).matches()) return;

        // ── Suppress: cursor inside existing code
        if (prefix.trim().length() > 10 && charsAfter > 2) return;

        String  keyword   = LanguageUtils.extractKeyword(prefix, editor);
        boolean isKeyword = keyword != null;

        if (!isKeyword && prefix.trim().length() > 15 && charsAfter == 0) return;

        // ── Determine intent and structural guide
        final String intent;
        final String structGuide;
        boolean isBlankLine = prefix.trim().isEmpty();

        if (isKeyword) {
            intent = "completing-started"; structGuide = null;
        } else if (isBlankLine) {
            StructureAnalyzer.StructureContext ctx = StructureAnalyzer.analyse(editor, offset);
            intent = ctx.kind == StructureAnalyzer.StructureKind.TOP_LEVEL ? "new-block" : "new-statement";
            structGuide = buildStructGuide(ctx);
        } else {
            intent = "completing-started"; structGuide = null;
        }

        // Record trigger line for context-window tracking
        lastTriggerLine = line;

        // ── Build context
        int ctxLines  = s.getContextLines();
        int prefStart = doc.getLineStartOffset(Math.max(0, line - ctxLines));
        int sufEnd    = doc.getLineEndOffset(Math.min(doc.getLineCount() - 1, line + ctxLines / 4));
        // Capture as String copies on EDT before handing to background thread.
        // getCharsSequence() is only safe to call on the EDT or under read lock.
        String fp  = doc.getText(new com.intellij.openapi.util.TextRange(prefStart, offset));
        String fs  = doc.getText(new com.intellij.openapi.util.TextRange(offset, sufEnd));
        String lang   = LanguageUtils.getLanguageId(editor);
        String fname  = getFilename();
        String lp     = prefix;
        String kw     = keyword;
        int    ins    = offset;
        String sg     = structGuide;
        String intent2= intent;

        // ── Cache check (key includes line number to prevent cross-line hits)
        String cacheKey = ("L" + line + ":" + fp).length() > 400
            ? "L" + line + ":" + fp.substring(fp.length() - 380)
            : "L" + line + ":" + fp;
        String cached = CACHE.get(cacheKey);
        if (cached != null && generation.get() == gen) {
            ghost.showSuggestion(cached, ins);
            return;
        }

        LLM_POOL.submit(() -> {
            if (generation.get() != gen) return;
            try {
                String prompt = PromptBuilder.completionPrompt(fp, fs, lang, fname, intent2, 0, sg, null, kw);
                String raw    = LLMClient.complete(prompt);
                if (raw == null || raw.isBlank()) return;
                if (generation.get() != gen) return;

                String formatted = IndentUtils.reindent(raw, lp, editor);
                String guarded   = DuplicateGuard.guard(formatted, fp, fs, lp);
                if (guarded == null || guarded.isBlank()) return;
                if (generation.get() != gen) return;

                CACHE.put(cacheKey, guarded);
                ghost.showSuggestion(guarded, ins);
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                if (!msg.contains("405") && !msg.contains("404") && !msg.contains("cancelled")) {
                    System.err.println("[LLM Copilot] inline: " + msg);
                }
            }
        });
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

    public static void clearCache() { CACHE.clear(); }

    public void dispose() {
        editor.getDocument().removeDocumentListener(this);
        editor.getCaretModel().removeCaretListener(caretListener);
        if (pendingTask != null) pendingTask.cancel(false);
        generation.incrementAndGet();
    }
}
