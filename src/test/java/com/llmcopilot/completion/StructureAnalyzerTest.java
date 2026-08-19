package com.llmcopilot.completion;

import com.llmcopilot.completion.StructureAnalyzer.StructureContext;
import com.llmcopilot.completion.StructureAnalyzer.StructureKind;
import com.llmcopilot.completion.StructureAnalyzer.SuggestionKind;
import com.llmcopilot.testsupport.FakeEditor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the backward brace scan that decides what kind of container the
 * caret sits in, and therefore what the model should be asked to suggest.
 *
 * The {@code |} in each fixture marks the caret.
 */
class StructureAnalyzerTest {

    private static StructureContext analyse(String textWithCaret) {
        FakeEditor.Fixture f = FakeEditor.withCaret(textWithCaret);
        return StructureAnalyzer.analyse(f.editor(), f.offset());
    }

    @Nested
    @DisplayName("container classification")
    class ContainerClassification {

        @Test
        void caretInAClassBodyIsRecognised() {
            StructureContext ctx = analyse("class Person {\n    |\n}");

            assertEquals(StructureKind.CLASS_BODY, ctx.kind);
            assertEquals("class", ctx.containerType);
            assertEquals("Person", ctx.containerName);
        }

        @Test
        void caretInAnInterfaceBodyIsRecognised() {
            StructureContext ctx = analyse("interface Shape {\n    |\n}");

            assertEquals(StructureKind.INTERFACE_BODY, ctx.kind);
            assertEquals("Shape", ctx.containerName);
        }

        @Test
        void caretInAnEnumBodyIsRecognised() {
            StructureContext ctx = analyse("enum Color {\n    |\n}");

            assertEquals(StructureKind.ENUM_BODY, ctx.kind);
            assertEquals("Color", ctx.containerName);
        }

        @Test
        void caretInAFunctionBodyIsRecognised() {
            StructureContext ctx = analyse("function compute() {\n    |\n}");

            assertEquals(StructureKind.FUNCTION_BODY, ctx.kind);
            assertEquals("compute", ctx.containerName);
        }

        @Test
        void caretOutsideAnyBraceIsTopLevel() {
            StructureContext ctx = analyse("import os\n|");

            assertEquals(StructureKind.TOP_LEVEL, ctx.kind);
            assertEquals("", ctx.containerName);
            assertEquals("", ctx.containerType);
        }

        @Test
        void anEmptyDocumentIsTopLevelAndEmpty() {
            StructureContext ctx = StructureAnalyzer.analyse(FakeEditor.withText(""), 0);

            assertEquals(StructureKind.TOP_LEVEL, ctx.kind);
            assertEquals(SuggestionKind.NEXT_DECLARATION, ctx.suggestion);
            assertTrue(ctx.isEmpty);
            assertEquals("", ctx.surroundingCode);
        }
    }

    @Nested
    @DisplayName("suggestion selection")
    class SuggestionSelection {

        @Test
        void anEmptyClassAsksForAConstructor() {
            assertEquals(SuggestionKind.CONSTRUCTOR, analyse("class Person {\n    |\n}").suggestion);
        }

        @Test
        void aClassThatAlreadyHasFieldsAsksForAccessors() {
            StructureContext ctx = analyse("class Person {\n    private String name;\n    |\n}");

            assertEquals(SuggestionKind.GETTER_SETTER, ctx.suggestion);
        }

        @Test
        void aClassThatAlreadyHasAConstructorAsksForTheNextMethod() {
            StructureContext ctx = analyse("class P {\n    constructor() {}\n    |\n}");

            assertEquals(SuggestionKind.NEXT_METHOD, ctx.suggestion);
        }

        @Test
        void anInterfaceAsksForTheNextMethod() {
            assertEquals(SuggestionKind.NEXT_METHOD, analyse("interface Shape {\n    |\n}").suggestion);
        }

        @Test
        void anEnumAsksForTheNextCase() {
            assertEquals(SuggestionKind.ENUM_CASE, analyse("enum Color {\n    |\n}").suggestion);
        }

        @Test
        void aFunctionBodyAsksForTheNextStatement() {
            assertEquals(SuggestionKind.NEXT_STATEMENT, analyse("function compute() {\n    |\n}").suggestion);
        }

        @Test
        void topLevelAsksForTheNextDeclaration() {
            assertEquals(SuggestionKind.NEXT_DECLARATION, analyse("import os\n|").suggestion);
        }
    }

    @Nested
    @DisplayName("captured context")
    class CapturedContext {

        @Test
        void signatureStartsAtTheContainerDeclaration() {
            StructureContext ctx = analyse("class Person {\n    private String name;\n    |\n}");

            assertTrue(ctx.signature.startsWith("class Person {"),
                () -> "unexpected signature: " + ctx.signature);
        }

        @Test
        void surroundingCodeStopsAtTheCaret() {
            StructureContext ctx = analyse("class Person {\n    private String name;\n    |\n}");

            assertTrue(ctx.surroundingCode.contains("private String name;"));
            assertFalse(ctx.surroundingCode.contains("}"),
                "context must not include text from after the caret");
        }

        @Test
        void anOffsetPastTheEndOfTheDocumentIsClamped() {
            StructureContext ctx = assertDoesNotThrow(
                () -> StructureAnalyzer.analyse(FakeEditor.withText("class A {\n}"), 9_999));

            assertEquals(StructureKind.CLASS_BODY, ctx.kind);
        }
    }
}
