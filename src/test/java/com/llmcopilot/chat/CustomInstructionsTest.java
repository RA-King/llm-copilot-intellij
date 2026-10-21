package com.llmcopilot.chat;

import com.llmcopilot.chat.CustomInstructions.InstructionFile;
import com.llmcopilot.services.LLMClient.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for reading Copilot-style repository instructions. Mirrors customInstructions.test.ts. */
class CustomInstructionsTest {

    @Test void splitsTheHeaderFromTheBody() {
        CustomInstructions.FrontMatter fm = CustomInstructions.parseFrontMatter(
            "---\napplyTo: \"**/*.ts\"\ndescription: Types\n---\nUse strict types.\n");
        assertEquals(Map.of("applyTo", "**/*.ts", "description", "Types"), fm.fields());
        assertEquals("Use strict types.", fm.body());
    }

    @Test void treatsAFileWithoutAHeaderAsAllBody() {
        assertEquals("Just text", CustomInstructions.parseFrontMatter("Just text\r\n").body());
    }

    @Test void splitsACommaSeparatedApplyTo() {
        assertEquals(List.of("**/*.ts", "src/**"), CustomInstructions.splitGlobs("\"**/*.ts\", src/**"));
        assertEquals(List.of(), CustomInstructions.splitGlobs(null));
    }

    @Test void matchesAcrossDirectoriesOnlyWithDoubleStar() {
        assertTrue(CustomInstructions.globToPattern("**/*.ts").matcher("a/b/c.ts").matches());
        assertTrue(CustomInstructions.globToPattern("**/*.ts").matcher("c.ts").matches());
        assertFalse(CustomInstructions.globToPattern("src/*.ts").matcher("src/a/b.ts").matches());
        assertTrue(CustomInstructions.globToPattern("src/**").matcher("src/a/b.ts").matches());
        assertTrue(CustomInstructions.globToPattern("**/*.{js,jsx}").matcher("app/x.jsx").matches());
    }

    private static final List<InstructionFile> FILES = List.of(
        new InstructionFile(".github/instructions/py.instructions.md", List.of("**/*.py"), "Use type hints."),
        new InstructionFile(".github/copilot-instructions.md", List.of(), "Prefer small functions."));

    @Test void putsUnconditionalInstructionsFirstAndKeepsScopedOnesToTheirFiles() {
        String forPython = CustomInstructions.compose(FILES, "app/main.py");
        assertTrue(forPython.indexOf("Prefer small functions.") < forPython.indexOf("Use type hints."));
        assertFalse(CustomInstructions.compose(FILES, "app/Main.java").contains("type hints"));
    }

    @Test void appliesScopedInstructionsToNothingWithoutAFile() {
        assertFalse(CustomInstructions.appliesTo(FILES.get(0), null));
        assertTrue(CustomInstructions.compose(FILES, null).contains("Prefer small functions."));
        assertEquals("", CustomInstructions.compose(List.of(), "a.ts"));
    }

    @Test void foldsInstructionsIntoTheSystemMessage() {
        List<ChatMessage> withSystem = CustomInstructions.withInstructions(
            List.of(new ChatMessage("system", "Base."), new ChatMessage("user", "q")), "Rules.");
        assertEquals("Base.\n\nRules.", withSystem.get(0).content());

        List<ChatMessage> withoutSystem = CustomInstructions.withInstructions(List.of(new ChatMessage("user", "q")), "Rules.");
        assertEquals("system", withoutSystem.get(0).role());
        assertEquals(2, withoutSystem.size());
    }

    @Test void loadsTheFilesFromARepository(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".github/instructions/backend"));
        Files.writeString(root.resolve(".github/copilot-instructions.md"), "Be terse.");
        Files.writeString(root.resolve("AGENTS.md"), "Run the tests.");
        Files.writeString(root.resolve(".github/instructions/backend/java.instructions.md"),
            "---\napplyTo: '**/*.java'\n---\nUse records.");

        List<InstructionFile> files = CustomInstructions.load(root);
        assertEquals(3, files.size());
        InstructionFile scoped = files.get(2);
        assertEquals(".github/instructions/backend/java.instructions.md", scoped.source());
        assertEquals(List.of("**/*.java"), scoped.applyTo());
        assertTrue(CustomInstructions.compose(files, "src/A.java").contains("Use records."));
    }
}
