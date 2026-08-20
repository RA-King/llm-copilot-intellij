package com.llmcopilot.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.llmcopilot.chat.AssistantMessageParser.Kind;
import static com.llmcopilot.chat.AssistantMessageParser.Segment;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for splitting an assistant reply into prose and code-block sections. */
class AssistantMessageParserTest {

    @Nested
    @DisplayName("replies without code blocks")
    class PlainProse {

        @Test
        void prosePassesThroughAsASingleSegment() {
            List<Segment> segments = AssistantMessageParser.parse("Just a sentence.");

            assertEquals(1, segments.size());
            assertEquals(Kind.PROSE, segments.get(0).kind());
            assertEquals("Just a sentence.", segments.get(0).text());
        }

        /** The duplication bug: prose was emitted once as the tail and once as a fallback. */
        @Test
        void proseIsNotEmittedTwice() {
            assertEquals(1, AssistantMessageParser.parse("Hello there.").size());
        }

        @Test
        void surroundingBlankLinesAreTrimmed() {
            assertEquals("Hello.", AssistantMessageParser.parse("\n\n  Hello.  \n\n").get(0).text());
        }

        @Test
        void aBlankReplyYieldsNothingToRender() {
            assertTrue(AssistantMessageParser.parse("   \n  ").isEmpty());
        }

        @Test
        void nullIsToleratedAsEmpty() {
            assertTrue(AssistantMessageParser.parse(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("replies with code blocks")
    class WithCode {

        @Test
        void proseAndCodeAlternateInDocumentOrder() {
            List<Segment> segments = AssistantMessageParser.parse(
                "Before.\n```java\nfoo();\n```\nAfter.");

            assertEquals(3, segments.size());
            assertEquals("Before.", segments.get(0).text());
            assertEquals(Kind.CODE, segments.get(1).kind());
            assertEquals("foo();", segments.get(1).text());
            assertEquals("After.", segments.get(2).text());
        }

        @Test
        void theFenceTagBecomesTheSegmentLanguage() {
            assertEquals("python",
                AssistantMessageParser.parse("```python\nx = 1\n```").get(0).language());
        }

        @Test
        void aBareFenceLeavesTheLanguageEmptyForTheCallerToDefault() {
            assertEquals("", AssistantMessageParser.parse("```\nx = 1\n```").get(0).language());
        }

        @Test
        void severalBlocksAreEachKeptSeparate() {
            List<Segment> segments = AssistantMessageParser.parse(
                "```java\none();\n```\nmiddle\n```java\ntwo();\n```");

            assertEquals(3, segments.size());
            assertEquals("one();", segments.get(0).text());
            assertEquals("middle", segments.get(1).text());
            assertEquals("two();", segments.get(2).text());
        }

        @Test
        void anEmptyBlockIsDropped() {
            List<Segment> segments = AssistantMessageParser.parse("Text.\n```java\n\n```");

            assertEquals(1, segments.size());
            assertEquals("Text.", segments.get(0).text());
        }

        @Test
        void codeKeepsItsInternalIndentation() {
            assertEquals("if (x) {\n    y();\n}",
                AssistantMessageParser.parse("```java\nif (x) {\n    y();\n}\n```").get(0).text());
        }

        @Test
        void anUnterminatedFenceStaysProse() {
            List<Segment> segments = AssistantMessageParser.parse("Here:\n```java\nfoo();");

            assertEquals(1, segments.size());
            assertEquals(Kind.PROSE, segments.get(0).kind());
        }
    }

    @Nested
    @DisplayName("the ASSISTANT header position")
    class HeaderPosition {

        /** The panel labels segment 0, so a leading code block means no header. */
        @Test
        void aReplyOpeningWithProseHasProseFirst() {
            assertEquals(Kind.PROSE,
                AssistantMessageParser.parse("Note.\n```java\nfoo();\n```").get(0).kind());
        }

        @Test
        void aReplyOpeningWithCodeHasCodeFirst() {
            assertEquals(Kind.CODE,
                AssistantMessageParser.parse("```java\nfoo();\n```\nNote.").get(0).kind());
        }
    }
}
