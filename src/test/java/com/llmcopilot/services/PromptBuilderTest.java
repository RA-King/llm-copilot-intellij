package com.llmcopilot.services;

import com.llmcopilot.services.LLMClient.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests that each prompt carries the right role structure and instructions. */
class PromptBuilderTest {

    private static String system(List<ChatMessage> msgs) {
        return msgs.get(0).content();
    }

    private static String user(List<ChatMessage> msgs) {
        return msgs.get(1).content();
    }

    @Nested
    @DisplayName("message envelope")
    class Envelope {

        @Test
        void everyPromptIsASystemMessageFollowedByAUserMessage() {
            List<List<ChatMessage>> prompts = List.of(
                PromptBuilder.explain("code", "java"),
                PromptBuilder.fix("code", "java"),
                PromptBuilder.refactor("code", "java", "extract a method"),
                PromptBuilder.generateTests("code", "java", "JUnit 5"),
                PromptBuilder.generateDocComment("code", "java"),
                PromptBuilder.generateConstructor("code", "java"),
                PromptBuilder.generateGetterSetter("code", "java", "name", "String"),
                PromptBuilder.implementMethod("void run();", "class A {}", "java"),
                PromptBuilder.inlineChat("do it", "code", "context", "java"),
                PromptBuilder.commitMessage("diff"));

            for (List<ChatMessage> p : prompts) {
                assertEquals(2, p.size());
                assertEquals("system", p.get(0).role());
                assertEquals("user", p.get(1).role());
            }
        }

        @Test
        void codeIsFencedWithTheLanguageTag() {
            String content = user(PromptBuilder.explain("int a = 1;", "java"));

            assertTrue(content.contains("```java"), content);
            assertTrue(content.contains("int a = 1;"), content);
        }
    }

    @Nested
    @DisplayName("test generation")
    class TestGeneration {

        @Test
        void anExplicitFrameworkIsRequested() {
            assertTrue(system(PromptBuilder.generateTests("code", "java", "JUnit 5"))
                .contains("Use the JUnit 5 testing framework."));
        }

        @Test
        void anAbsentFrameworkFallsBackToTheLanguageDefault() {
            String s = system(PromptBuilder.generateTests("code", "java", "  "));

            assertTrue(s.contains("most common testing framework"), s);
        }

        @Test
        void aNullFrameworkFallsBackToTheLanguageDefault() {
            String s = system(PromptBuilder.generateTests("code", "java", null));

            assertTrue(s.contains("most common testing framework"), s);
        }
    }

    @Nested
    @DisplayName("refactor and doc comment")
    class Instructions {

        @Test
        void theRefactorInstructionIsPassedThrough() {
            String content = user(PromptBuilder.refactor("code", "java", "split into two methods"));

            assertTrue(content.contains("split into two methods"), content);
        }

        @Test
        void docCommentPromptsForbidCommentMarkers() {
            String s = system(PromptBuilder.generateDocComment("code", "java"));

            assertTrue(s.contains("PLAIN TEXT"), s);
            assertTrue(s.contains("do NOT include"), s);
        }

        @Test
        void getterSetterPromptNamesExactlyOneField() {
            String content = user(
                PromptBuilder.generateGetterSetter("class A {}", "java", "name", "String"));

            assertTrue(content.contains("\"name\""), content);
            assertTrue(content.contains("String"), content);
            assertTrue(content.contains("ONE field only"), content);
        }
    }

    @Nested
    @DisplayName("commit messages")
    class CommitMessages {

        @Test
        void conventionalCommitFormatIsRequested() {
            assertTrue(system(PromptBuilder.commitMessage("diff")).contains("Conventional Commits"));
        }

        @Test
        void aLargeDiffIsTruncatedToThePromptBudget() {
            String content = user(PromptBuilder.commitMessage("x".repeat(5_000)));

            assertEquals(3_000, content.chars().filter(c -> c == 'x').count());
        }

        @Test
        void aSmallDiffIsSentWhole() {
            String content = user(PromptBuilder.commitMessage("x".repeat(42)));

            assertEquals(42, content.chars().filter(c -> c == 'x').count());
        }
    }

    @Nested
    @DisplayName("inline chat")
    class InlineChat {

        @Test
        void optionalSectionsAreOmittedWhenEmpty() {
            String content = user(PromptBuilder.inlineChat("rename this", "", "  ", "java"));

            assertEquals("rename this", content);
        }

        @Test
        void selectionAndContextAreIncludedWhenPresent() {
            String content = user(
                PromptBuilder.inlineChat("rename this", "int a;", "class A {}", "java"));

            assertTrue(content.contains("Selected code:"), content);
            assertTrue(content.contains("Context:"), content);
            assertTrue(content.contains("int a;"), content);
        }

        @Test
        void nullSectionsAreTolerated() {
            String content = assertDoesNotThrow(
                () -> user(PromptBuilder.inlineChat("hi", null, null, "java")));

            assertEquals("hi", content);
        }
    }

    @Nested
    @DisplayName("completion prompt")
    class CompletionPrompt {

        private String build(String intent, int depth, String workspace, String keyword) {
            return PromptBuilder.completionPrompt(
                "int a = ", ";\n", "java", "Main.java", intent, depth, null, workspace, keyword);
        }

        @Test
        void theCursorIsMarkedBetweenPrefixAndSuffix() {
            String p = build("continue", 0, null, null);

            assertTrue(p.contains("int a = <CURSOR>;"), p);
        }

        @Test
        void theFileNameIsIncluded() {
            assertTrue(build("continue", 0, null, null).contains("Main.java"));
        }

        @Test
        void aKeywordHintOverridesTheIntentGuide() {
            String p = build("new-block", 0, null, "class");

            assertTrue(p.contains("the keyword \"class\""), p);
            assertTrue(p.contains("do NOT repeat it"), p);
            assertFalse(p.contains("NEXT logical declaration"), p);
        }

        @Test
        void newBlockIntentAsksForTheNextDeclaration() {
            assertTrue(build("new-block", 0, null, null).contains("NEXT logical declaration"));
        }

        @Test
        void newStatementIntentReportsTheNestingDepth() {
            assertTrue(build("new-statement", 3, null, null).contains("depth 3"));
        }

        @Test
        void anUnknownIntentFallsBackToLineCompletion() {
            assertTrue(build("whatever", 0, null, null)
                .contains("Complete what the user has started typing"));
        }

        @Test
        void theWorkspaceSectionAppearsOnlyWhenContextIsSupplied() {
            assertFalse(build("continue", 0, "   ", null).contains("Related declarations"));
            assertTrue(build("continue", 0, "class Helper {}", null)
                .contains("Related declarations resolved by the IDE"));
        }

        @Test
        void existingCodeIsProtected() {
            assertTrue(build("continue", 0, null, null)
                .contains("NEVER rewrite or alter existing code"));
        }
    }
}
