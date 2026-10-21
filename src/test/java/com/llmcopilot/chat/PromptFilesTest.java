package com.llmcopilot.chat;

import com.llmcopilot.chat.PromptFiles.PromptFile;
import com.llmcopilot.chat.PromptFiles.Variables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for repository prompt files as chat commands. Mirrors promptFiles.test.ts. */
class PromptFilesTest {

    @Test void namesTheCommandAfterTheFileAndReadsTheDescription() {
        PromptFile prompt = PromptFiles.parse("/repo/.github/prompts/Add Endpoint.prompt.md",
            "---\ndescription: Scaffold a REST endpoint\nmode: agent\n---\nCreate an endpoint for ${input:resource}.", "x");
        assertEquals("add-endpoint", prompt.name());
        assertEquals("Scaffold a REST endpoint", prompt.description());
        assertEquals("Create an endpoint for ${input:resource}.", prompt.body());
    }

    @Test void fallsBackToTheFirstLineForADescription() {
        assertEquals("Review security", PromptFiles.parse("x.prompt.md", "# Review security\nCheck for injection.", "x").description());
    }

    private static final Variables VARS = new Variables("let a = 1;", "src\\app\\main.ts", "/repo", "orders");

    @Test void fillsCopilotStyleVariables() {
        assertEquals("main.ts in src/app: let a = 1; for orders",
            PromptFiles.expand("${fileBasename} in ${fileDirname}: ${selection} for ${input:name:thing}", VARS));
    }

    @Test void usesTheInputHintWhenNothingWasTyped() {
        assertEquals("Do the default", PromptFiles.expand("Do ${input:what:the default}", new Variables("", "", "", "")));
    }

    @Test void appendsTypedTextWhenThePromptHasNoInputVariable() {
        assertEquals("Review src/app/main.ts\n\norders", PromptFiles.expand("Review ${file}", VARS));
    }

    @Test void leavesUnknownVariablesAlone() {
        assertEquals("${nope}", PromptFiles.expand("${nope}", new Variables("", "", "", "")));
    }

    @Test void loadsPromptFilesSortedByName(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve(".github/prompts"));
        Files.writeString(dir.resolve("review.prompt.md"), "Review it.");
        Files.writeString(dir.resolve("add-test.prompt.md"), "---\ndescription: Add a test\n---\nWrite a test.");
        Files.writeString(dir.resolve("notes.md"), "Not a prompt.");

        List<PromptFile> prompts = PromptFiles.load(root.toString());
        assertEquals(List.of("add-test", "review"), prompts.stream().map(PromptFile::name).toList());
        assertEquals(".github/prompts/add-test.prompt.md", prompts.get(0).source());
    }
}
