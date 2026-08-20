package com.llmcopilot.completion;

import com.intellij.navigation.ItemPresentation;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;

import com.llmcopilot.settings.LLMCopilotSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link CodeContext} from the language's own parse tree.
 *
 * <p>Uses only the language-agnostic PSI layer, so it works for every language whose
 * plugin provides a parser rather than just Java. Two things are read: the chain of
 * named declarations enclosing the caret, and the declarations that references near
 * the caret resolve to — which is what pulls signatures in from other files.
 *
 * <p>Everything is best-effort. A file with no PSI, an uncommitted document, or a
 * language without reference resolution simply yields {@link CodeContext#EMPTY} and
 * the caller falls back to {@link StructureAnalyzer}.
 */
public final class PsiCodeContextCollector {

    /** Lines either side of the caret whose references are followed. */
    private static final int  SCAN_LINES     = 20;
    /** Upper bound on resolved declarations, before de-duplication. */
    private static final int  MAX_DECLS      = 12;
    /** Longest signature kept; anything longer is truncated. */
    private static final int  MAX_SIG_CHARS  = 200;
    /** Depth of the enclosing-declaration chain reported to the model. */
    private static final int  MAX_CHAIN      = 4;
    /**
     * Wall-clock ceiling. Resolution can touch other files and this runs on the EDT while
     * the user types, so it is deliberately small: a partial context beats a dropped frame.
     */
    private static final long TIME_BUDGET_NS = 12_000_000L; // 12ms

    /**
     * Resolved context, keyed on the caret line and a fingerprint of the lines around it.
     * Typing on the caret line leaves that fingerprint unchanged, so the expensive
     * resolution happens once per region rather than once per keystroke.
     */
    private static final Map<String, CodeContext> CACHE = Collections.synchronizedMap(
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CodeContext> e) {
                return size() > 64;
            }
        }
    );

    private PsiCodeContextCollector() {}

    public static void clearCache() { CACHE.clear(); }

    /**
     * Collects context for {@code offset}. Safe to call from the EDT; returns
     * {@link CodeContext#EMPTY} rather than throwing when PSI is unavailable.
     */
    public static CodeContext collect(Editor editor, int offset) {
        if (!LLMCopilotSettings.getInstance().isPsiContext()) return CodeContext.EMPTY;

        Project project = editor.getProject();
        if (project == null || project.isDisposed()) return CodeContext.EMPTY;

        Document doc = editor.getDocument();
        int safeOffset = Math.max(0, Math.min(offset, doc.getTextLength()));

        String key = cacheKey(doc, safeOffset);
        CodeContext hit = CACHE.get(key);
        if (hit != null) return hit;

        try {
            CodeContext ctx = ReadAction.compute(() -> doCollect(project, editor, safeOffset));
            // Only a real result is worth caching; EMPTY usually means "PSI not ready yet",
            // and the next keystroke should retry rather than reuse the miss.
            if (!ctx.isEmpty()) CACHE.put(key, ctx);
            return ctx;
        } catch (Exception | LinkageError e) {
            // A language plugin can throw from resolve(); never let that break typing.
            return CodeContext.EMPTY;
        }
    }

    /**
     * Fingerprints the scan window while ignoring edits to the caret line itself, since
     * those do not change what the surrounding references resolve to. Lines carrying a
     * brace are kept intact, as those can move the caret into a different declaration.
     */
    private static String cacheKey(Document doc, int offset) {
        if (doc.getLineCount() == 0) return "empty";
        int line     = doc.getLineNumber(offset);
        int fromLine = Math.max(0, line - SCAN_LINES);
        int toLine   = Math.min(doc.getLineCount() - 1, line + SCAN_LINES);

        CharSequence all = doc.getImmutableCharSequence();
        int hash = 7;
        for (int i = fromLine; i <= toLine; i++) {
            int start = doc.getLineStartOffset(i);
            int end   = doc.getLineEndOffset(i);

            // Hash the characters directly: a CharSequence slice has no value-based
            // hashCode contract, and this avoids copying the window on every keystroke.
            int lineHash = 7;
            boolean braced = false;
            for (int c = start; c < end; c++) {
                char ch = all.charAt(c);
                if (ch == '{' || ch == '}') braced = true;
                lineHash = 31 * lineHash + ch;
            }
            hash = 31 * hash + (i == line && !braced ? 0 : lineHash);
        }
        return System.identityHashCode(doc) + ":" + line + ":" + hash;
    }

    private static CodeContext doCollect(Project project, Editor editor, int offset) {
        Document doc = editor.getDocument();
        PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);

        // An uncommitted document means PSI still describes older text. Reparsing here
        // would stall the EDT, so skip this round instead - the next keystroke retries.
        if (!pdm.isCommitted(doc)) return CodeContext.EMPTY;

        PsiFile file = pdm.getPsiFile(doc);
        if (file == null) return CodeContext.EMPTY;

        long deadline = System.nanoTime() + TIME_BUDGET_NS;

        PsiElement leaf = leafAt(file, offset);
        List<String> chain = enclosingChain(leaf);
        List<CodeContext.Declaration> related = relatedDeclarations(file, doc, offset, leaf, deadline);

        return chain.isEmpty() && related.isEmpty() ? CodeContext.EMPTY : new CodeContext(chain, related);
    }

    /** The caret often sits just past the last token, so step back one when needed. */
    private static PsiElement leafAt(PsiFile file, int offset) {
        PsiElement leaf = file.findElementAt(offset);
        if (leaf == null && offset > 0) leaf = file.findElementAt(offset - 1);
        return leaf;
    }

    // ── Enclosing declarations ────────────────────────────────────────────────

    /** Named declarations containing the caret, outermost first. */
    private static List<String> enclosingChain(PsiElement leaf) {
        List<String> chain = new ArrayList<>();
        for (PsiElement el = leaf; el != null && !(el instanceof PsiFile); el = el.getParent()) {
            if (!(el instanceof PsiNameIdentifierOwner)) continue;
            String desc = describe(el);
            if (desc != null && !desc.isBlank()) chain.add(desc);
            if (chain.size() >= MAX_CHAIN) break;
        }
        java.util.Collections.reverse(chain); // outermost first
        return chain;
    }

    // ── Cross-file declarations ───────────────────────────────────────────────

    /**
     * Follows references in the lines around the caret to whatever they declare. This is
     * the step that reaches into other files: {@link PsiReference#resolve()} lands on the
     * declaring element wherever it lives.
     */
    private static List<CodeContext.Declaration> relatedDeclarations(
            PsiFile file, Document doc, int offset, PsiElement leaf, long deadline) {

        int line     = doc.getLineNumber(offset);
        int fromLine = Math.max(0, line - SCAN_LINES);
        int toLine   = Math.min(doc.getLineCount() - 1, line + SCAN_LINES);
        int from     = doc.getLineStartOffset(fromLine);
        int to       = doc.getLineEndOffset(toLine);

        PsiElement enclosing = leaf == null ? null : nearestDeclaration(leaf);
        Set<PsiElement> targets = new LinkedHashSet<>();

        int cursor = from;
        while (cursor < to && targets.size() < MAX_DECLS) {
            if (System.nanoTime() > deadline) break;

            PsiElement el = file.findElementAt(cursor);
            if (el == null) { cursor++; continue; }

            // References hang off the identifier's parent as often as the leaf itself.
            collectTargets(el, enclosing, targets);
            PsiElement parent = el.getParent();
            if (parent != null && !(parent instanceof PsiFile)) collectTargets(parent, enclosing, targets);

            int end = el.getTextRange().getEndOffset();
            cursor  = end > cursor ? end : cursor + 1;
        }

        List<CodeContext.Declaration> out = new ArrayList<>();
        for (PsiElement target : targets) {
            String sig = describe(target);
            if (sig == null || sig.isBlank()) continue;
            out.add(new CodeContext.Declaration(originOf(target, file), sig));
        }
        return out;
    }

    private static void collectTargets(PsiElement el, PsiElement enclosing, Set<PsiElement> targets) {
        for (PsiReference ref : el.getReferences()) {
            if (targets.size() >= MAX_DECLS) return;
            PsiElement resolved;
            try {
                resolved = ref.resolve();
            } catch (Exception | LinkageError e) {
                continue; // a resolver that misbehaves must not break completion
            }
            if (resolved == null || resolved == enclosing) continue;
            if (!(resolved instanceof PsiNameIdentifierOwner)) continue;
            targets.add(resolved);
        }
    }

    private static PsiElement nearestDeclaration(PsiElement leaf) {
        for (PsiElement el = leaf; el != null && !(el instanceof PsiFile); el = el.getParent()) {
            if (el instanceof PsiNameIdentifierOwner) return el;
        }
        return null;
    }

    /** Where a declaration came from, so the model can tell local from imported. */
    private static String originOf(PsiElement target, PsiFile current) {
        PsiFile owner = target.getContainingFile();
        if (owner == null) return "";
        return owner.equals(current) ? "this file" : owner.getName();
    }

    // ── Signature extraction ──────────────────────────────────────────────────

    /**
     * Reduces a declaration to one line. Prefers the presentation the language plugin
     * supplies - the same text the IDE shows in Structure View, so it already reads as
     * a signature - and falls back to the first line of the declaration's own text.
     */
    private static String describe(PsiElement el) {
        if (el instanceof NavigationItem ni) {
            try {
                ItemPresentation p = ni.getPresentation();
                if (p != null) {
                    String text = p.getPresentableText();
                    if (text != null && !text.isBlank()) {
                        String where = p.getLocationString();
                        return truncate(where == null || where.isBlank() ? text : text + " " + where);
                    }
                }
            } catch (Exception | LinkageError ignored) {
                // fall through to the text-based description
            }
        }

        // Reading the text of a whole class is wasteful; only small elements are worth it.
        if (el.getTextLength() > 0 && el.getTextLength() <= 4000) {
            String text = el.getText();
            if (text != null && !text.isBlank()) return truncate(firstLine(text));
        }
        return el instanceof PsiNamedElement named ? named.getName() : null;
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return (nl < 0 ? text : text.substring(0, nl)).trim();
    }

    private static String truncate(String s) {
        String flat = s.replace('\n', ' ').trim();
        return flat.length() <= MAX_SIG_CHARS ? flat : flat.substring(0, MAX_SIG_CHARS) + "…";
    }
}
