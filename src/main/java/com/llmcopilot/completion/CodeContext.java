package com.llmcopilot.completion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the IDE knows about the code around the caret: the chain of declarations the
 * caret sits inside, and the declarations that the surrounding references resolve to.
 *
 * <p>Populated from PSI by {@link PsiCodeContextCollector}, but deliberately free of
 * PSI itself — everything here is plain data and string shaping, so the rendering
 * rules that decide what reaches the model can be tested without an IDE fixture.
 */
public final class CodeContext {

    /** A declaration pulled in from somewhere else, reduced to one line. */
    public record Declaration(String origin, String signature) {}

    public static final CodeContext EMPTY = new CodeContext(List.of(), List.of());

    /** Enclosing declarations, outermost first; the last entry is the closest one. */
    private final List<String>      containerChain;
    private final List<Declaration> related;

    public CodeContext(List<String> containerChain, List<Declaration> related) {
        this.containerChain = List.copyOf(containerChain);
        this.related        = List.copyOf(related);
    }

    public boolean isEmpty() { return containerChain.isEmpty() && related.isEmpty(); }

    public List<String>      containerChain() { return containerChain; }
    public List<Declaration> related()        { return related; }

    /** The declaration immediately containing the caret, or {@code null} at file scope. */
    public String enclosingSignature() {
        return containerChain.isEmpty() ? null : containerChain.get(containerChain.size() - 1);
    }

    /**
     * The structural guide for the prompt: where the caret sits, per the language's own
     * parser, followed by {@code suggestionHint} describing what to produce there.
     * Returns {@code null} when no enclosing declaration was resolved, which tells the
     * caller to fall back to the text-based {@link StructureAnalyzer}.
     */
    public String structuralGuide(String suggestionHint) {
        if (containerChain.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("The caret is inside: ")
            .append(String.join(" > ", containerChain))
            .append(".\n");

        String enclosing = enclosingSignature();
        sb.append("Enclosing declaration: ").append(enclosing).append(".\n");
        sb.append("Honour that signature — its parameters and return type are in scope.\n");

        if (suggestionHint != null && !suggestionHint.isBlank()) sb.append(suggestionHint).append("\n");
        return sb.toString();
    }

    /**
     * The related declarations, one per line, trimmed to {@code budgetChars}. Duplicate
     * signatures collapse. Returns {@code null} when nothing survives, so the caller can
     * omit the prompt section entirely.
     */
    public String relatedBlock(int budgetChars) {
        Set<String> seen  = new LinkedHashSet<>();
        List<String> rows = new ArrayList<>();

        for (Declaration d : related) {
            if (d.signature() == null || d.signature().isBlank()) continue;
            String row = "  " + (d.origin() == null || d.origin().isBlank()
                ? d.signature()
                : d.origin() + ": " + d.signature());
            if (seen.add(row)) rows.add(row);
        }
        if (rows.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (String row : rows) {
            if (sb.length() + row.length() + 1 > budgetChars) break;
            sb.append(row).append("\n");
        }
        return sb.length() == 0 ? null : sb.toString().stripTrailing();
    }
}
