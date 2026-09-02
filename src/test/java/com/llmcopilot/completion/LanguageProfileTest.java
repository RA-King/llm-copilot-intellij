package com.llmcopilot.completion;

import com.llmcopilot.completion.LanguageProfile.BlockStyle;
import com.llmcopilot.completion.LanguageProfile.LocalMatch;
import com.llmcopilot.completion.LanguageProfile.LoopMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the shared table of language-specific shapes. */
class LanguageProfileTest {

    @Nested
    @DisplayName("loop headers")
    class Loops {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "go two-value range   | for i, user := range users {          | user  | users",
            "go one-value range   | for user := range users {             | user  | users",
            "csharp foreach       | foreach (var user in users) {         | user  | users",
            "php foreach          | foreach ($users as $user) {           | $user | $users",
            "php foreach kv       | foreach ($users as $id => $user) {    | $user | $users",
            "js for-of            | for (const user of users) {           | user  | users",
            "java enhanced for    | for (User user : users) {             | user  | users",
            "cpp range for        | for (const auto& user : users) {      | user  | users",
            "scala for            | for (user <- users) {                 | user  | users",
            "python for           | for user in users:                    | user  | users",
            "rust for             | for user in users {                   | user  | users",
            "kotlin for           | for (user in users) {                 | user  | users",
        })
        void readsTheBindingAndTheCollection(String label, String header, String binding, String iterable) {
            LoopMatch m = LanguageProfile.matchLoop(header.trim());

            assertNotNull(m, label);
            assertEquals(binding.trim(), m.binding(), label);
            assertEquals(iterable.trim(), m.iterable(), label);
        }

        @Test
        void readsARubyBlockAsALoop() {
            // Kept out of the table above: the block's own pipes collide with the
            // delimiter the parameterised cases use.
            LoopMatch m = LanguageProfile.matchLoop("users.each do |user|");

            assertNotNull(m);
            assertEquals("user", m.binding());
            assertEquals("users", m.iterable());
        }

        @Test
        void readsACounterLoopAsABindingWithNoCollection() {
            LoopMatch m = LanguageProfile.matchLoop("for (int i = 0; i < n; i++) {");

            assertEquals("i", m.binding());
            assertEquals("", m.iterable());
        }

        @Test
        void doesNotReadAnOrdinaryCallAsALoop() {
            assertNull(LanguageProfile.matchLoop("int total = sum(values);"));
        }
    }

    @Nested
    @DisplayName("local declarations")
    class Locals {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "rust let mut  | let mut names = Vec::new();               | names  |              | Vec::new()",
            "go short decl | names := []string{}                       | names  |              | []string{}",
            "go var        | var names []string                        | names  | []string     | ",
            "php           | $names = [];                              | $names |              | []",
            "kotlin val    | val names = mutableListOf<String>()       | names  |              | mutableListOf<String>()",
            "python        | names = []                                | names  |              | []",
        })
        void readsADeclaration(String label, String line, String name, String type, String init) {
            LocalMatch m = LanguageProfile.matchLocal(line.trim());

            assertNotNull(m, label);
            assertEquals(name.trim(), m.name(), label);
            assertEquals(type == null ? "" : type.trim(), m.type(), label);
            assertEquals(init == null ? "" : init.trim(), m.init(), label);
        }

        @Test
        void readsAJavaDeclarationWithItsType() {
            LocalMatch m = LanguageProfile.matchLocal("List<String> names = new ArrayList<>();");

            assertEquals("names", m.name());
            assertEquals("List<String>", m.type());
            assertEquals("new ArrayList<>()", m.init());
        }

        @Test
        void readsACppDeclarationWithNoInitialiser() {
            LocalMatch m = LanguageProfile.matchLocal("std::vector<std::string> names;");

            assertEquals("names", m.name());
            assertEquals("std::vector<std::string>", m.type());
            assertEquals("", m.init());
        }

        @Test
        void refusesAControlFlowHeader() {
            assertNull(LanguageProfile.matchLocal("foreach ($users as $user) {"));
            assertNull(LanguageProfile.matchLocal("if (total = 0) {"));
        }
    }

    @Nested
    @DisplayName("empty initialisers")
    class EmptyInitialisers {

        @ParameterizedTest
        @ValueSource(strings = {
            "[]", "[];", "{}", "0", "0.0", "''", "\"\"", "new ArrayList<>()", "Vec::new()",
            "vec![]", "make(map[string]int)", "[]string{}", "mutableListOf<String>()",
            "ListBuffer[String]()", "scala.collection.mutable.ListBuffer[String]()", "array()",
        })
        void treatsTheseAsEmpty(String init) {
            assertTrue(LanguageProfile.isEmptyInitialiser(init), init);
        }

        @ParameterizedTest
        @ValueSource(strings = { "new ArrayList<>(other)", "fetchUsers()", "user.getName()", "42" })
        void leavesTheseAlone(String init) {
            assertFalse(LanguageProfile.isEmptyInitialiser(init), init);
        }

        @Test
        void treatsADeclarationWithNoInitialiserAsEmptyWhenATypeWasStated() {
            assertTrue(LanguageProfile.isEmptyInitialiser("", "std::vector<std::string>"));
            assertFalse(LanguageProfile.isEmptyInitialiser("", ""));
        }
    }

    @Nested
    @DisplayName("void types")
    class VoidTypes {

        @ParameterizedTest
        @ValueSource(strings = { "void", "Void", "None", "Unit", "()", "undefined", "never", "Task<Unit>" })
        void treatsTheseAsReturningNothing(String type) {
            assertTrue(LanguageProfile.isVoidType(type), type);
        }

        @ParameterizedTest
        @ValueSource(strings = { "String", "List<User>", "Result<T, E>" })
        void treatsTheseAsARealResult(String type) {
            assertFalse(LanguageProfile.isVoidType(type), type);
        }
    }

    @Nested
    @DisplayName("language identity")
    class Identity {

        @Test
        void recognisesControlHeadersThatDeclareNothing() {
            assertTrue(LanguageProfile.isControlLine("foreach ($users as $user) {"));
            assertTrue(LanguageProfile.isControlLine("} catch (IOException e) {"));
            assertTrue(LanguageProfile.isControlLine("using (var stream = File.Open(path)) {"));
            assertFalse(LanguageProfile.isControlLine("public List<String> collectNames(List<User> users) {"));
        }

        @Test
        void knowsHowEachFamilyDelimitsABlock() {
            assertEquals(BlockStyle.INDENT, LanguageProfile.blockStyleFor("python"));
            assertEquals(BlockStyle.END,    LanguageProfile.blockStyleFor("ruby"));
            assertEquals(BlockStyle.BRACE,  LanguageProfile.blockStyleFor("java"));
        }

        @Test
        void knowsTheLineCommentMarkers() {
            assertEquals(List.of("#"),  LanguageProfile.lineCommentsFor("python"));
            assertEquals(List.of("--"), LanguageProfile.lineCommentsFor("lua"));
            assertEquals(List.of("//"), LanguageProfile.lineCommentsFor("java"));
            assertTrue(LanguageProfile.lineCommentsFor("rust").contains("///"));
        }
    }
}
