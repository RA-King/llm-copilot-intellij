package com.llmcopilot.services;

import com.llmcopilot.services.StreamDecoder.Delta;
import com.llmcopilot.services.StreamDecoder.Dialect;
import com.llmcopilot.services.StreamDecoder.Framing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for decoding streamed replies. Mirrors streamDecoder.test.ts. */
class StreamDecoderTest {

    @Test void holdsALineSplitAcrossChunksUntilItCompletes() {
        StreamDecoder decoder = new StreamDecoder(Framing.SSE);
        assertEquals(List.of(), decoder.push("data: {\"a\""));
        assertEquals(List.of("{\"a\":1}", "[DONE]"), decoder.push(":1}\n\ndata: [DONE]\n"));
    }

    @Test void skipsSseEventAndCommentLines() {
        assertEquals(List.of("{}"), new StreamDecoder(Framing.SSE).push("event: content_block_delta\n: ping\ndata: {}\n"));
    }

    @Test void passesNdjsonLinesThroughAndFlushesTheTail() {
        StreamDecoder decoder = new StreamDecoder(Framing.NDJSON);
        assertEquals(List.of("{\"x\":1}"), decoder.push("{\"x\":1}\n{\"x\":"));
        assertEquals(List.of(), decoder.push("2}"));
        assertEquals(List.of("{\"x\":2}"), decoder.flush());
    }

    @Test void readsOpenAiDeltas() {
        assertEquals(new Delta("Hel", false, null),
            StreamDecoder.read("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}", Dialect.OPENAI));
        assertTrue(StreamDecoder.read("[DONE]", Dialect.OPENAI).done());
        assertEquals("", StreamDecoder.read("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}", Dialect.OPENAI).text());
    }

    @Test void readsAnthropicDeltasAndTheStopEvent() {
        assertEquals("lo", StreamDecoder.read(
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}", Dialect.ANTHROPIC).text());
        assertTrue(StreamDecoder.read("{\"type\":\"message_stop\"}", Dialect.ANTHROPIC).done());
        assertEquals(new Delta("", false, null), StreamDecoder.read("{\"type\":\"ping\"}", Dialect.ANTHROPIC));
    }

    @Test void readsOllamaChatLines() {
        assertEquals("!", StreamDecoder.read("{\"message\":{\"content\":\"!\"},\"done\":false}", Dialect.OLLAMA).text());
        assertTrue(StreamDecoder.read("{\"message\":{\"content\":\"\"},\"done\":true}", Dialect.OLLAMA).done());
    }

    @Test void surfacesAnErrorReportedMidStream() {
        assertEquals("overloaded", StreamDecoder.read(
            "{\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}", Dialect.ANTHROPIC).error());
    }

    @Test void ignoresAPayloadThatIsNotJson() {
        assertEquals(new Delta("", false, null), StreamDecoder.read("garbage", Dialect.OPENAI));
    }
}
