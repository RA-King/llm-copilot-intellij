package com.llmcopilot.agent;

import com.google.gson.JsonParser;
import com.llmcopilot.agent.AgentProtocol.Reply;
import com.llmcopilot.agent.AgentProtocol.ToolCall;
import com.llmcopilot.services.LLMClient.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the agent's tool-call protocol. Mirrors agentProtocol.test.ts. */
class AgentProtocolTest {

    @Test void systemPromptListsEveryToolAndTheCallFormat() {
        String prompt = AgentProtocol.systemPrompt("Workspace root: /repo");
        for (AgentProtocol.ToolSpec tool : AgentProtocol.TOOLS) assertTrue(prompt.contains("- " + tool.name() + ":"));
        assertTrue(prompt.contains("start_line?: first line"));
        assertTrue(prompt.contains("<tool name=\"TOOL_NAME\">"));
        assertTrue(prompt.contains("Workspace root: /repo"));
    }

    @Test void readsATaggedCallAndTheTextBeforeIt() {
        Reply reply = AgentProtocol.parse("Reading the config.\n<tool name=\"read_file\">\n{\"path\": \"src/Config.java\", \"start_line\": 10}\n</tool>");
        assertEquals("Reading the config.", reply.text());
        assertEquals("read_file", reply.call().name());
        assertEquals("src/Config.java", reply.call().args().get("path").getAsString());
        assertEquals(10, reply.call().args().get("start_line").getAsInt());
    }

    @Test void acceptsAMissingClosingTagOrFencedJson() {
        assertEquals("list_files", AgentProtocol.parse("<tool name=\"list_files\">\n{}").call().name());
        assertEquals("a.ts", AgentProtocol.parse("<tool name=\"get_problems\">\n```json\n{\"path\": \"a.ts\"}\n```\n</tool>")
            .call().args().get("path").getAsString());
    }

    @Test void reportsArgumentsThatAreNotAJsonObject() {
        assertTrue(AgentProtocol.parse("<tool name=\"read_file\">{path: a.ts</tool>").error().contains("not valid JSON"));
        assertTrue(AgentProtocol.parse("<tool name=\"read_file\">[1]</tool>").error().contains("JSON object"));
    }

    @Test void fallsBackToAFencedToolObject() {
        ToolCall call = AgentProtocol.parse("Searching.\n```json\n{\"tool\": \"search_text\", \"args\": {\"query\": \"retry\"}}\n```").call();
        assertEquals("search_text", call.name());
        assertEquals("retry", call.args().get("query").getAsString());
    }

    @Test void treatsAReplyWithNoCallAsTheFinalAnswer() {
        Reply reply = AgentProtocol.parse("  Done: added the check.  ");
        assertEquals("Done: added the check.", reply.text());
        assertNull(reply.call());
        assertNull(reply.error());
    }

    @Test void visibleTextDropsTheCallBlock() {
        assertEquals("Looking.", AgentProtocol.visibleText("Looking.\n<tool name=\"read_file\">{\"pa"));
    }

    @Test void readsArgumentsAndNamesWhatIsMissing() {
        ToolCall call = new ToolCall("read_file", JsonParser.parseString("{\"path\":\"a.ts\",\"start_line\":\"12\",\"bad\":\"x\"}").getAsJsonObject());
        assertEquals("a.ts", AgentProtocol.stringArg(call, "path", true));
        assertEquals(12, AgentProtocol.intArg(call, "start_line"));
        assertNull(AgentProtocol.intArg(call, "end_line"));
        assertNull(AgentProtocol.stringArg(call, "other", false));
        assertEquals("read_file needs \"other\"",
            assertThrows(IllegalArgumentException.class, () -> AgentProtocol.stringArg(call, "other", true)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> AgentProtocol.intArg(call, "bad"));
    }

    @Test void truncateKeepsTheHeadAndTheTail() {
        String out = AgentProtocol.truncate("a".repeat(100) + "b".repeat(100), 50);
        assertTrue(out.startsWith("a".repeat(30)));
        assertTrue(out.endsWith("b".repeat(20)));
        assertTrue(out.contains("150 characters omitted"));
        assertEquals("short", AgentProtocol.truncate("short", 50));
    }

    private static final List<ChatMessage> MESSAGES = List.of(
        new ChatMessage("system", "S"),
        new ChatMessage("user", "task"),
        new ChatMessage("assistant", "reading"),
        new ChatMessage("user", "Result of read_file:\n" + "x".repeat(1000)),
        new ChatMessage("assistant", "reading more"),
        new ChatMessage("user", "Result of read_file:\n" + "y".repeat(1000)));

    @Test void compactBlanksTheOldestToolResultsFirstAndNeverTheLatest() {
        List<ChatMessage> out = AgentProtocol.compact(MESSAGES, 1500);
        assertTrue(out.get(3).content().startsWith("Result of read_file:\n(earlier result omitted"));
        assertEquals(MESSAGES.get(5), out.get(5));
        assertEquals("reading", out.get(2).content());
        assertEquals(MESSAGES, AgentProtocol.compact(MESSAGES, 10_000));
    }

    private static final String TEXT = "const a = 1;\nconst b = 2;\nconst a = 1;\n";

    @Test void findUniqueFindsAPassageThatOccursOnce() {
        AgentProtocol.Match m = AgentProtocol.findUnique(TEXT, "const b = 2;");
        assertEquals(13, m.start());
        assertEquals(25, m.end());
        assertNull(m.error());
    }

    @Test void findUniqueRefusesAPassageThatOccursMoreThanOnce() {
        assertTrue(AgentProtocol.findUnique(TEXT, "const a = 1;").error().contains("matches 2 places"));
    }

    @Test void findUniqueToleratesTrailingWhitespaceAndCrlfButNothingElse() {
        AgentProtocol.Match m = AgentProtocol.findUnique("x = 1;  \r\ny = 2;\r\n", "x = 1;\ny = 2;");
        assertEquals(0, m.start());
        assertEquals(16, m.end());
        assertTrue(AgentProtocol.findUnique(TEXT, "const c = 3;").error().contains("not found"));
        assertEquals("search is empty", AgentProtocol.findUnique(TEXT, "").error());
    }

    @Test void safeRelativePathNormalisesPathsInsideTheWorkspace() {
        assertEquals("src/b.ts", AgentProtocol.safeRelativePath("./src//a/../b.ts", null));
        assertEquals("src/a.ts", AgentProtocol.safeRelativePath("/repo/src/a.ts", "/repo"));
        assertEquals("src/win/a.ts", AgentProtocol.safeRelativePath("src\\win\\a.ts", null));
    }

    @Test void safeRelativePathRefusesAnythingOutsideIt() {
        assertThrows(IllegalArgumentException.class, () -> AgentProtocol.safeRelativePath("../secrets", null));
        assertThrows(IllegalArgumentException.class, () -> AgentProtocol.safeRelativePath("/etc/passwd", "/repo"));
        assertThrows(IllegalArgumentException.class, () -> AgentProtocol.safeRelativePath("C:/Windows/x", null));
        assertThrows(IllegalArgumentException.class, () -> AgentProtocol.safeRelativePath(".", null));
    }
}
