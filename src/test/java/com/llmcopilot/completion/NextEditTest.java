package com.llmcopilot.completion;

import com.llmcopilot.completion.NextEdit.Located;
import com.llmcopilot.completion.NextEdit.SearchReplace;
import com.llmcopilot.services.LLMClient.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for predicting, reading and placing a next edit. Mirrors nextEdit.test.ts. */
class NextEditTest {

    @Test void promptCarriesTheRecentEditsAndTheNumberedWindow() {
        List<ChatMessage> msgs = NextEdit.prompt("java", "src/A.java", "@@ line 1\n- a\n+ b", "int b = 1;\nuse(a);", 10, 10);
        assertTrue(msgs.get(0).content().contains("<<<<<<< SEARCH"));
        assertTrue(msgs.get(1).content().contains("@@ line 1\n- a\n+ b"));
        assertTrue(msgs.get(1).content().contains("11 | use(a);"));
    }

    @Test void readsASearchReplaceBlock() {
        assertEquals(new SearchReplace("use(a);", "use(b);"),
            NextEdit.parse("Sure.\n<<<<<<< SEARCH\nuse(a);\n=======\nuse(b);\n>>>>>>> REPLACE\n"));
    }

    @Test void allowsAnEmptyReplacementWhichDeletes() {
        assertEquals(new SearchReplace("import x;", ""), NextEdit.parse("<<<<<<< SEARCH\nimport x;\n=======\n>>>>>>> REPLACE"));
    }

    @Test void stripsLineNumbersQuotedBack() {
        assertEquals("use(a);", NextEdit.parse("<<<<<<< SEARCH\n11 | use(a);\n=======\n11 | use(b);\n>>>>>>> REPLACE").search());
    }

    @Test void readsNoneNoOpsAndNoiseAsNothing() {
        assertNull(NextEdit.parse("NONE"));
        assertNull(NextEdit.parse("<<<<<<< SEARCH\nx\n=======\nx\n>>>>>>> REPLACE"));
        assertNull(NextEdit.parse("use(b);"));
    }

    private static final String TEXT = "use(a);\nfoo();\nuse(a);\n";

    @Test void picksTheOccurrenceNearestTheCaret() {
        SearchReplace edit = new SearchReplace("use(a);", "use(b);");
        assertEquals(15, NextEdit.locate(TEXT, edit, TEXT.length()).start());
        assertEquals(0, NextEdit.locate(TEXT, edit, 0).start());
    }

    @Test void toleratesTrailingWhitespaceTheModelDropped() {
        assertEquals(new Located(0, 16, "x"), NextEdit.locate("a = 1;   \nb = 2;\n", new SearchReplace("a = 1;\nb = 2;", "x"), 0));
    }

    @Test void findsNothingForTextThatIsNotThere() {
        assertNull(NextEdit.locate(TEXT, new SearchReplace("nope", "x"), 0));
    }

    @Test void catchesASuggestionThatUndoesTheLastEdit() {
        EditHistory h = new EditHistory();
        h.reset("load(user)\n");
        h.apply(List.of(new EditHistory.TextChange(5, 4, "account")));
        assertTrue(NextEdit.revertsLastEdit(new SearchReplace("load(account)", "load(user)"), h.last()));
        assertFalse(NextEdit.revertsLastEdit(new SearchReplace("save(user)", "save(account)"), h.last()));
        assertFalse(NextEdit.revertsLastEdit(new SearchReplace("x", "y"), null));
    }

    @Test void narrowsTheEditToTheCharactersThatChange() {
        String text = "save(user);";
        assertEquals(new Located(5, 9, "account"), NextEdit.trim(text, new Located(0, text.length(), "save(account);")));
    }
}
