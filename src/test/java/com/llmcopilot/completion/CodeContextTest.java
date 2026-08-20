package com.llmcopilot.completion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.llmcopilot.completion.CodeContext.Declaration;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for shaping IDE-resolved context into the parts of a completion prompt. */
class CodeContextTest {

    private static CodeContext withChain(String... chain) {
        return new CodeContext(List.of(chain), List.of());
    }

    private static CodeContext withRelated(Declaration... decls) {
        return new CodeContext(List.of(), List.of(decls));
    }

    @Nested
    @DisplayName("the enclosing declaration")
    class Enclosing {

        @Test
        void theInnermostContainerIsTheEnclosingOne() {
            assertEquals("compute(String, int): int",
                withChain("class Foo", "compute(String, int): int").enclosingSignature());
        }

        @Test
        void fileScopeHasNoEnclosingDeclaration() {
            assertNull(CodeContext.EMPTY.enclosingSignature());
        }

        @Test
        void anEmptyContextIsReportedEmpty() {
            assertTrue(CodeContext.EMPTY.isEmpty());
            assertFalse(withChain("class Foo").isEmpty());
            assertFalse(withRelated(new Declaration("Util.java", "slug(String): String")).isEmpty());
        }
    }

    @Nested
    @DisplayName("the structural guide")
    class Guide {

        /** Null is the signal that the caller should fall back to StructureAnalyzer. */
        @Test
        void noChainYieldsNoGuide() {
            assertNull(CodeContext.EMPTY.structuralGuide("Suggest the next statement."));
        }

        @Test
        void theChainIsRenderedOutermostFirst() {
            String guide = withChain("class Foo", "compute(String, int): int").structuralGuide(null);

            assertTrue(guide.contains("class Foo > compute(String, int): int"), guide);
        }

        @Test
        void theEnclosingSignatureIsCalledOutSeparately() {
            String guide = withChain("class Foo", "compute(String, int): int").structuralGuide(null);

            assertTrue(guide.contains("Enclosing declaration: compute(String, int): int"), guide);
            assertTrue(guide.contains("Honour that signature"), guide);
        }

        @Test
        void theSuggestionHintIsAppendedWhenGiven() {
            String guide = withChain("class Foo").structuralGuide("Suggest the next logical method.");

            assertTrue(guide.contains("Suggest the next logical method."), guide);
        }

        @Test
        void aMissingHintStillProducesAGuide() {
            assertNotNull(withChain("class Foo").structuralGuide(null));
            assertNotNull(withChain("class Foo").structuralGuide("   "));
        }
    }

    @Nested
    @DisplayName("the related-declaration block")
    class Related {

        @Test
        void nothingResolvedMeansNoSection() {
            assertNull(CodeContext.EMPTY.relatedBlock(2000));
        }

        @Test
        void aDeclarationIsLabelledWithItsOrigin() {
            String block = withRelated(new Declaration("Util.java", "slug(String): String"))
                .relatedBlock(2000);

            assertEquals("  Util.java: slug(String): String", block);
        }

        @Test
        void anUnknownOriginLeavesJustTheSignature() {
            assertEquals("  slug(String): String",
                withRelated(new Declaration("", "slug(String): String")).relatedBlock(2000));
        }

        @Test
        void repeatedResolutionsOfTheSameSymbolCollapse() {
            String block = withRelated(
                new Declaration("Util.java", "slug(String): String"),
                new Declaration("Util.java", "slug(String): String")
            ).relatedBlock(2000);

            assertEquals(1, block.lines().count(), block);
        }

        @Test
        void blankSignaturesAreDropped() {
            assertNull(withRelated(
                new Declaration("Util.java", "   "),
                new Declaration("Util.java", null)
            ).relatedBlock(2000));
        }

        @Test
        void theBudgetCapsHowManyDeclarationsAreSent() {
            String block = withRelated(
                new Declaration("A.java", "aaaaaaaaaa(): void"),
                new Declaration("B.java", "bbbbbbbbbb(): void"),
                new Declaration("C.java", "cccccccccc(): void")
            ).relatedBlock(40);

            assertTrue(block.length() <= 40, block);
            assertTrue(block.contains("aaaaaaaaaa"), block);
            assertFalse(block.contains("cccccccccc"), block);
        }

        @Test
        void aBudgetTooSmallForEvenOneRowYieldsNoSection() {
            assertNull(withRelated(new Declaration("A.java", "aaaaaaaaaa(): void")).relatedBlock(5));
        }
    }
}
