package com.llmcopilot.index;

import java.util.*;

/**
 * The files a question is about, by how many of its words they declare or are
 * named after. An exact symbol name counts most, a path segment next, a name
 * that merely contains the word least. Mirrors ProjectIndex.search in
 * projectIndex.ts.
 */
public final class ProjectSearch {

    private ProjectSearch() {}

    public record Result(String path, List<SourceSummary.Symbol> symbols, int score) {}

    public static List<Result> search(Collection<SourceSummary.FileSummary> files, List<String> terms, int limit) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String t : terms) if (t.length() >= 3) wanted.add(t.toLowerCase(Locale.ROOT));
        if (wanted.isEmpty()) return List.of();

        List<Result> results = new ArrayList<>();
        for (SourceSummary.FileSummary file : files) {
            String lowerPath = file.path().toLowerCase(Locale.ROOT);
            int score = 0;
            Set<SourceSummary.Symbol> matched = new LinkedHashSet<>();
            for (String term : wanted) {
                if (lowerPath.contains(term)) score += 3;
                for (SourceSummary.Symbol symbol : file.symbols()) {
                    String name = symbol.name().toLowerCase(Locale.ROOT);
                    if (name.equals(term)) { score += 5; matched.add(symbol); }
                    else if (name.contains(term)) { score += 2; matched.add(symbol); }
                }
            }
            if (score > 0) results.add(new Result(file.path(), List.copyOf(matched), score));
        }

        results.sort(Comparator.comparingInt(Result::score).reversed().thenComparing(Result::path));
        return results.size() > limit ? results.subList(0, limit) : results;
    }

    /** {@link #search} rendered for a prompt, bounded by characters. */
    public static String render(Collection<SourceSummary.FileSummary> files, List<String> terms, int budgetChars) {
        Map<String, SourceSummary.FileSummary> byPath = new HashMap<>();
        for (SourceSummary.FileSummary f : files) byPath.put(f.path(), f);

        StringJoiner out = new StringJoiner("\n\n");
        int spent = 0;
        for (Result hit : search(files, terms, 12)) {
            SourceSummary.FileSummary file = byPath.get(hit.path());
            List<SourceSummary.Symbol> shown = hit.symbols().isEmpty()
                ? file.symbols().stream().filter(SourceSummary.Symbol::exported).toList()
                : hit.symbols();
            StringBuilder block = new StringBuilder(hit.path());
            shown.stream().limit(8).forEach(s ->
                block.append("\n  ").append(s.line() + 1).append(": ").append(s.signature()));
            if (spent + block.length() > budgetChars) break;
            out.add(block);
            spent += block.length() + 2;
        }
        return out.toString();
    }
}
