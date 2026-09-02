package com.llmcopilot.completion;

import com.llmcopilot.completion.IntentInference.ConstructKind;
import com.llmcopilot.completion.IntentInference.GoalKind;
import com.llmcopilot.completion.IntentInference.Named;
import com.llmcopilot.completion.IntentInference.Reading;
import com.llmcopilot.completion.IntentInference.Shape;
import com.llmcopilot.testsupport.FakeEditor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the reading of what the author is about to write.
 *
 * The {@code |} in each fixture marks the caret.
 */
class IntentInferenceTest {

    private static Reading read(String textWithCaret) {
        FakeEditor.Fixture f = FakeEditor.withCaret(textWithCaret);
        return IntentInference.read(f.editor(), f.offset());
    }

    @Nested
    @DisplayName("reading a name")
    class ReadingAName {

        @Test
        void splitsCamelCase() {
            assertEquals(List.of("fetch", "user", "orders"), IntentInference.splitIdentifier("fetchUserOrders"));
        }

        @Test
        void splitsSnakeCaseAndStripsLeadingUnderscores() {
            assertEquals(List.of("load", "config", "file"), IntentInference.splitIdentifier("_load_config_file"));
        }

        @Test
        void keepsRunsOfCapitalsWhole() {
            assertEquals(List.of("parse", "http", "response"), IntentInference.splitIdentifier("parseHTTPResponse"));
        }

        @Test
        void readsAVerbAppliedToASubject() {
            Named named = IntentInference.classifyName("fetchUserOrders");

            assertEquals(GoalKind.FETCH, named.kind());
            assertEquals("fetch user orders", named.goal());
            assertEquals("user orders", named.subject());
        }

        @Test
        void recognisesTheCommonVerbFamilies() {
            assertEquals(GoalKind.PREDICATE, IntentInference.classifyName("isEligible").kind());
            assertEquals(GoalKind.VALIDATE,  IntentInference.classifyName("validateEmail").kind());
            assertEquals(GoalKind.CREATE,    IntentInference.classifyName("buildRequest").kind());
            assertEquals(GoalKind.MUTATE,    IntentInference.classifyName("saveOrder").kind());
            assertEquals(GoalKind.TRANSFORM, IntentInference.classifyName("parseConfig").kind());
        }

        @Test
        void reportsUnknownRatherThanGuessing() {
            assertEquals(GoalKind.UNKNOWN, IntentInference.classifyName("quux").kind());
        }
    }

    @Nested
    @DisplayName("the block the caret is in")
    class TheBlockTheCaretIsIn {

        @Test
        void findsTheEnhancedForLoopWithItsVariableAndCollection() {
            Reading r = read("""
                class Orders {
                    List<String> collectNames(List<User> users) {
                        List<String> names = new ArrayList<>();
                        for (User user : users) {
                            |
                        }
                    }
                }""");

            assertNotNull(r.openConstruct());
            assertEquals(ConstructKind.LOOP, r.openConstruct().kind());
            assertEquals("user", r.openConstruct().binding());
            assertEquals("users", r.openConstruct().iterable());
        }

        @Test
        void findsACatchAndTheErrorItBinds() {
            Reading r = read("""
                class Loader {
                    Config load(String path) {
                        try {
                            return parse(path);
                        } catch (IOException err) {
                            |
                        }
                    }
                }""");

            assertEquals(ConstructKind.CATCH, r.openConstruct().kind());
            assertEquals("err", r.openConstruct().binding());
        }

        @Test
        void keepsTheConditionOfABranch() {
            Reading r = read("""
                class Maths {
                    int half(int n) {
                        if (n > 10) {
                            |
                        }
                    }
                }""");

            assertEquals(ConstructKind.BRANCH, r.openConstruct().kind());
            assertEquals("n > 10", r.openConstruct().condition());
        }

        @Test
        void doesNotMistakeTheEnclosingSignatureForABlockTheCaretIsNestedIn() {
            Reading r = read("""
                class Maths {
                    int half(int n) {
                        |
                    }
                }""");

            assertNull(r.openConstruct());
        }
    }

    @Nested
    @DisplayName("progress through the body")
    class ProgressThroughTheBody {

        @Test
        void reportsParametersNothingHasReferenced() {
            Reading r = read("""
                class Pricing {
                    Order applyDiscount(Order order, double rate) {
                        double subtotal = order.total();
                        |
                    }
                }""");

            assertEquals(List.of("rate"), r.unusedParams());
        }

        @Test
        void flagsALocalThatNothingReads() {
            Reading r = read("""
                class Report {
                    String summarise(List<Row> rows) {
                        StringBuilder parts = new StringBuilder();
                        |
                    }
                }""");

            assertTrue(r.unusedLocals().stream().anyMatch(b -> b.name().equals("parts")));
        }

        @Test
        void countsTheGuardClausesAlreadyWritten() {
            Reading r = read("""
                class Maths {
                    int divide(int a, int b) {
                        if (b == 0) { throw new ArithmeticException("divide by zero"); }
                        |
                    }
                }""");

            assertEquals(1, r.guardCount());
        }

        @Test
        void spotsTheCollectionTheLoopIsFilling() {
            Reading r = read("""
                class Orders {
                    List<String> collectNames(List<User> users) {
                        List<String> names = new ArrayList<>();
                        for (User user : users) {
                            |
                        }
                    }
                }""");

            assertNotNull(r.accumulator());
            assertEquals("names", r.accumulator().name());
            assertTrue(r.nextSteps().get(0).contains("names"));
        }
    }

    @Nested
    @DisplayName("how much to write")
    class HowMuchToWrite {

        @Test
        void asksForAnExpressionAfterAnAssignmentOrOperator() {
            assertEquals(Shape.EXPRESSION, IntentInference.decideShape("    double tax = ", null, 3));
            assertEquals(Shape.EXPRESSION, IntentInference.decideShape("    return ", null, 3));
            assertEquals(Shape.EXPRESSION, IntentInference.decideShape("    call(a, ", null, 3));
        }

        @Test
        void asksForABlockOnTheLineAfterOneWasOpened() {
            IntentInference.OpenConstruct oc = new IntentInference.OpenConstruct(
                ConstructKind.LOOP, "for (User user : users) {", 2, "user", "users", "");

            assertEquals(Shape.BLOCK, IntentInference.decideShape("        ", oc, 3));
        }

        @Test
        void asksForAStatementFurtherInsideTheBlock() {
            IntentInference.OpenConstruct oc = new IntentInference.OpenConstruct(
                ConstructKind.LOOP, "for (User user : users) {", 1, "user", "users", "");

            assertEquals(Shape.STATEMENT, IntentInference.decideShape("        ", oc, 5));
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @Test
        void namesTheGoalTheLoopTheAccumulatorAndANextStep() {
            String out = IntentInference.render(read("""
                class Orders {
                    List<String> collectNames(List<User> users) {
                        List<String> names = new ArrayList<>();
                        for (User user : users) {
                            |
                        }
                    }
                }"""));

            assertNotNull(out);
            assertTrue(out.contains("collect names"), out);
            assertTrue(out.contains("inside a loop"), out);
            assertTrue(out.contains("`names`"), out);
            assertTrue(out.contains("Most likely next:"), out);
        }

        @Test
        void suggestsHandlingTheBoundError() {
            String out = IntentInference.render(read("""
                class Loader {
                    Config loadConfig(String path) {
                        try {
                            return parse(path);
                        } catch (IOException err) {
                            |
                        }
                    }
                }"""));

            assertTrue(out.contains("err"), out);
        }

        @Test
        void staysSilentWhereThereIsNothingToSay() {
            assertNull(IntentInference.render(Reading.EMPTY));
            assertNull(IntentInference.render(read("|")));
        }
    }
}
