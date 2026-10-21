package com.llmcopilot.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for answering #codebase from the index. Mirrors the ProjectIndex.search tests in projectIndex.test.ts. */
class ProjectSearchTest {

    private static SourceSummary.FileSummary summarise(String path, String text) {
        return SourceSummary.summarise(path, text, "typescript", 1L, text.length());
    }

    private static final List<SourceSummary.FileSummary> FILES = List.of(
        summarise("src/net/retry.ts", "export class RetryPolicy {}\nexport function backoff(n: number) {}"),
        summarise("src/orders.ts", "export function placeOrder() {}\nexport const retryCount = 3;"),
        summarise("src/ui/view.ts", "export function render() {}"));

    @Test void ranksExactNamesAndPathMatchesAbovePartialOnes() {
        List<ProjectSearch.Result> hits = ProjectSearch.search(FILES, List.of("RetryPolicy", "retry"), 8);
        assertEquals(List.of("src/net/retry.ts", "src/orders.ts"), hits.stream().map(ProjectSearch.Result::path).toList());
        assertTrue(hits.get(0).symbols().stream().anyMatch(s -> s.name().equals("RetryPolicy")));
    }

    @Test void rendersTheMatchingDeclarationsWithTheirLines() {
        assertEquals("src/net/retry.ts\n  2: export function backoff(n: number) {}",
            ProjectSearch.render(FILES, List.of("backoff"), 4000));
    }

    @Test void findsNothingForShortOrAbsentTerms() {
        assertEquals(List.of(), ProjectSearch.search(FILES, List.of("ab"), 8));
        assertEquals("", ProjectSearch.render(FILES, List.of("nothing"), 4000));
    }
}
