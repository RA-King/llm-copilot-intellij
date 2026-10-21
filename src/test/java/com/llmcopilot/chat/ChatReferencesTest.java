package com.llmcopilot.chat;

import com.llmcopilot.chat.ChatReferences.Attachment;
import com.llmcopilot.chat.ChatReferences.Kind;
import com.llmcopilot.chat.ChatReferences.Parsed;
import com.llmcopilot.chat.ChatReferences.Reference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for slash commands and # references in chat input. Mirrors chatReferences.test.ts. */
class ChatReferencesTest {

    @Test void readsALeadingSlashCommand() {
        Parsed parsed = ChatReferences.parse("/Fix the null check");
        assertEquals("fix", parsed.command());
        assertEquals("the null check", parsed.text());
    }

    @Test void leavesASlashLaterInTheMessageAlone() {
        assertNull(ChatReferences.parse("what does a/b mean").command());
    }

    @Test void pullsOutFileReferencesKeepingThePathInTheSentence() {
        Parsed parsed = ChatReferences.parse("compare #file:src/a.ts with #file:\"docs/my notes.md\".");
        assertEquals(List.of(new Reference(Kind.FILE, "src/a.ts"), new Reference(Kind.FILE, "docs/my notes.md")),
            parsed.references());
        assertEquals("compare src/a.ts with docs/my notes.md.", parsed.text());
    }

    @Test void readsTheNamedReferencesAndDropsThemFromTheText() {
        Parsed parsed = ChatReferences.parse("#codebase where is retry handled? #problems #terminal");
        assertEquals(List.of(Kind.CODEBASE, Kind.PROBLEMS, Kind.TERMINAL_LAST_COMMAND),
            parsed.references().stream().map(Reference::kind).toList());
        assertEquals("where is retry handled?", parsed.text());
    }

    @Test void ignoresUnknownHashesAndRepeats() {
        Parsed parsed = ChatReferences.parse("issue #42 #changes #changes");
        assertEquals(List.of(Reference.of(Kind.CHANGES)), parsed.references());
        assertEquals("issue #42", parsed.text());
    }

    @Test void fencesCodeAndLeavesProseBare() {
        String out = ChatReferences.render(List.of(
            new Attachment("File — a.ts", "typescript", "const a = 1;\n\n"),
            new Attachment("Codebase", "", "src/a.ts"),
            new Attachment("Empty", "text", "  ")));
        assertEquals("### File — a.ts\n```typescript\nconst a = 1;\n```\n\n### Codebase\nsrc/a.ts", out);
    }

    @Test void keepsIdentifiersAndSplitsCompoundNames() {
        List<String> terms = ChatReferences.searchTerms("where is the RetryPolicy and max_attempts used?");
        assertTrue(terms.containsAll(List.of("RetryPolicy", "retry", "policy", "max_attempts", "attempts")));
        assertFalse(terms.contains("where"));
        assertFalse(terms.contains("used"));
    }
}
