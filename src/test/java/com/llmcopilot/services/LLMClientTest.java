package com.llmcopilot.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for reading non-streamed replies. Mirrors the anthropicText cases in chatStream.test.ts. */
class LLMClientTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test void joinsAnthropicTextBlocksAndSkipsThinking() {
        assertEquals("ab", LLMClient.anthropicText(json(
            "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hmm\"},{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}")));
    }

    @Test void anAnthropicReplyWithNoContentIsEmpty() {
        assertEquals("", LLMClient.anthropicText(json("{}")));
    }

    @Test void readsOpenAIContent() {
        assertEquals("hi", LLMClient.extractOpenAIContent("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
    }

    @Test void nullOpenAIContentIsEmptyRatherThanACrash() {
        assertEquals("", LLMClient.extractOpenAIContent("{\"choices\":[{\"message\":{\"content\":null}}]}"));
        assertEquals("", LLMClient.extractOpenAIContent("{\"choices\":[]}"));
    }

    @Test void extractContentHandlesBothShapes() {
        assertEquals("x", LLMClient.extractContent("{\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"));
        assertEquals("y", LLMClient.extractContent("{\"choices\":[{\"message\":{\"content\":\"y\"}}]}"));
    }
}
