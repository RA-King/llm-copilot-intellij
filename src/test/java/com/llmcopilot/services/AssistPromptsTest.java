package com.llmcopilot.services;

import com.llmcopilot.services.LLMClient.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the problem-fix, terminal and pull request prompts. Mirrors assistPrompts.test.ts. */
class AssistPromptsTest {

    @Test void namesTheLineRangeToReturnAndNumbersTheCode() {
        List<ChatMessage> msgs = AssistPrompts.problemFix("java", "src/A.java", List.of("line 5 error: cannot find symbol x"),
            4, "int a = 1;\nreturn x;", "// Declarations found elsewhere");
        assertTrue(msgs.get(0).content().contains("lines 4–5"));
        assertTrue(msgs.get(1).content().contains("5 | return x;"));
        assertTrue(msgs.get(1).content().contains("cannot find symbol x"));
        assertTrue(msgs.get(1).content().contains("Declarations found elsewhere"));
    }

    @Test void takesTheFirstFencedBlock() {
        assertEquals("int a = 1;", AssistPrompts.extractCodeBlock("Here:\n```java\nint a = 1;\n```\nmore"));
        assertEquals("int a = 1;", AssistPrompts.extractCodeBlock("int a = 1;\n\n"));
    }

    @Test void statesTheShellAndPlatform() {
        String system = AssistPrompts.terminalCommand("list ports", "zsh", "macOS", "/repo").get(0).content();
        assertTrue(system.contains("zsh command for macOS"));
        assertTrue(system.contains("/repo"));
    }

    @Test void readsAFencedCommandAndItsExplanation() {
        assertEquals(new AssistPrompts.TerminalSuggestion("lsof -i :3000", "Shows what listens on port 3000."),
            AssistPrompts.parseTerminalSuggestion("```bash\n$ lsof -i :3000\n```\nShows what listens on port 3000."));
        assertEquals("du -sh * | sort -h",
            AssistPrompts.parseTerminalSuggestion("du -sh * | sort -h\nSizes, smallest first.").command());
    }

    @Test void includesTheCommitsAndTruncatesALongDiff() {
        String user = AssistPrompts.pullRequest("feature", "origin/main", "Add retry", " a.ts | 2 +-", "x".repeat(50), 10)
            .get(1).content();
        assertTrue(user.contains("Branch feature into origin/main"));
        assertTrue(user.contains("Add retry"));
        assertTrue(user.contains("(diff truncated)"));
    }
}
