package com.llmcopilot.errors;

import com.llmcopilot.errors.ErrorParser.Frame;
import com.llmcopilot.errors.ErrorParser.Origin;
import com.llmcopilot.errors.ErrorParser.ParsedError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for reading a failure out of run, debug and terminal output. */
class ErrorParserTest {

    private static final String ESC = String.valueOf((char) 27);
    private static final String BEL = String.valueOf((char) 7);

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }

    @Nested
    @DisplayName("cleaning")
    class Cleaning {

        @Test void dropsColourCodes() {
            assertEquals("Error: boom", ErrorParser.stripAnsi(ESC + "[31mError" + ESC + "[0m: boom"));
        }

        @Test void dropsTheSequenceAShellUsesToSetItsWindowTitle() {
            assertEquals("npm test", ErrorParser.stripAnsi(ESC + "]0;~/app" + BEL + "npm test"));
        }

        @Test void leavesPlainOutputAlone() {
            String text = "TypeError: x is not a function";
            assertEquals(text, ErrorParser.stripAnsi(text));
        }
    }

    @Nested
    @DisplayName("recognising a failure")
    class Recognising {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "a thrown exception    | TypeError: undefined is not a function",
            "a compiler diagnostic | src/order.ts(42,15): error TS2345: Argument of type",
            "a missing file        | cat: config.yml: No such file or directory",
            "a go panic            | panic: runtime error: index out of range",
        })
        void recognises(String label, String text) {
            assertTrue(ErrorParser.looksLikeError(text.trim()), label);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "a clean build              | Compiled successfully in 2.1s",
            "a zero count               | Found 0 errors. Watching for file changes.",
            "a file named after the word| created src/error-handling.ts",
        })
        void doesNotFireOn(String label, String text) {
            assertFalse(ErrorParser.looksLikeError(text.trim()), label);
        }
    }

    @Nested
    @DisplayName("trimming the capture")
    class Trimming {

        @Test void keepsTheFailureAndDropsWhatScrolledBeforeIt() {
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 200; i++) output.append("  ok test ").append(i).append("\n");
            output.append("FAIL src/order.test.ts\n")
                  .append("  ● totals an empty basket\n")
                  .append("    expect(received).toBe(expected)");

            String block = ErrorParser.extractErrorBlock(output.toString(), 20);
            assertTrue(block.contains("FAIL src/order.test.ts"));
            assertTrue(block.contains("expect(received).toBe(expected)"));
            assertTrue(block.split("\n").length <= 20);
        }

        @Test void trimsTrailingBlankLines() {
            assertEquals("Error: boom", ErrorParser.extractErrorBlock("Error: boom\n\n\n", 80));
        }

        @Test void returnsNothingForEmptyOutput() {
            assertEquals("", ErrorParser.extractErrorBlock("   \n\n", 80));
        }
    }

    @Nested
    @DisplayName("stack traces")
    class Traces {

        @Test void readsANodeTrace() {
            ParsedError parsed = ErrorParser.parse(lines(
                "TypeError: Cannot read properties of undefined (reading 'map')",
                "    at total (/app/src/order.ts:42:25)",
                "    at Object.<anonymous> (/app/src/index.ts:8:1)",
                "    at Module._compile (node:internal/modules/cjs/loader:1105:14)"
            ), CapturedError.TERMINAL);

            assertNotNull(parsed);
            assertEquals(Origin.NODE, parsed.origin());
            assertEquals("TypeError", parsed.type());
            assertTrue(parsed.message().contains("Cannot read properties of undefined"));
            assertEquals(new Frame("/app/src/order.ts", 42, 25, "total"), parsed.frames().get(0));
        }

        @Test void readsAPythonTracebackWhoseExceptionComesLast() {
            ParsedError parsed = ErrorParser.parse(lines(
                "Traceback (most recent call last):",
                "  File \"/app/orders.py\", line 12, in <module>",
                "    print(total(basket))",
                "  File \"/app/orders.py\", line 8, in total",
                "    return sum(i.price for i in basket.items)",
                "AttributeError: 'NoneType' object has no attribute 'items'"
            ), CapturedError.DEBUG);

            assertNotNull(parsed);
            assertEquals(Origin.PYTHON, parsed.origin());
            assertEquals("AttributeError", parsed.type());
            assertEquals("'NoneType' object has no attribute 'items'", parsed.message());
            assertEquals(2, parsed.frames().size());
            assertEquals(new Frame("/app/orders.py", 8, 0, "total"), parsed.frames().get(1));
        }

        @Test void readsAJavaException() {
            ParsedError parsed = ErrorParser.parse(lines(
                "Exception in thread \"main\" java.lang.NullPointerException: Cannot invoke \"Item.price()\"",
                "\tat com.acme.Order.total(Order.java:42)",
                "\tat com.acme.Main.main(Main.java:11)"
            ), CapturedError.RUN);

            assertNotNull(parsed);
            assertEquals(Origin.JAVA, parsed.origin());
            assertEquals("java.lang.NullPointerException", parsed.type());
            assertEquals(new Frame("Order.java", 42, 0, "com.acme.Order.total"), parsed.frames().get(0));
        }

        @Test void readsAJavacDiagnostic() {
            ParsedError parsed = ErrorParser.parse(
                "/app/src/Order.java:42: error: cannot find symbol", CapturedError.RUN);

            assertNotNull(parsed);
            assertEquals(Origin.COMPILER, parsed.origin());
            assertEquals(new Frame("/app/src/Order.java", 42, 0, null), parsed.frames().get(0));
            assertTrue(parsed.message().contains("cannot find symbol"));
        }

        @Test void readsATypeScriptDiagnosticAsBothMessageAndLocation() {
            ParsedError parsed = ErrorParser.parse(
                "src/order.ts(42,15): error TS2345: Argument of type 'string' is not assignable.",
                CapturedError.TERMINAL);

            assertNotNull(parsed);
            assertEquals("TS2345", parsed.type());
            assertEquals(new Frame("src/order.ts", 42, 15, null), parsed.frames().get(0));
        }

        @Test void readsAGoPanic() {
            ParsedError parsed = ErrorParser.parse(lines(
                "panic: runtime error: index out of range [3] with length 2",
                "",
                "goroutine 1 [running]:",
                "main.total(...)",
                "\t/app/order.go:42 +0x1d"
            ), CapturedError.TERMINAL);

            assertNotNull(parsed);
            assertEquals(Origin.GO, parsed.origin());
            assertEquals("runtime error: index out of range [3] with length 2", parsed.message());
            assertEquals(new Frame("/app/order.go", 42, 0, null), parsed.frames().get(0));
        }

        @Test void readsARustPanic() {
            ParsedError parsed = ErrorParser.parse(lines(
                "thread 'main' panicked at src/main.rs:42:9:",
                "index out of bounds: the len is 2 but the index is 3"
            ), CapturedError.TERMINAL);

            assertNotNull(parsed);
            assertEquals(Origin.RUST, parsed.origin());
            assertEquals(new Frame("src/main.rs", 42, 9, null), parsed.frames().get(0));
        }

        @Test void readsADotNetException() {
            ParsedError parsed = ErrorParser.parse(lines(
                "Unhandled exception. System.NullReferenceException: Object reference not set.",
                "   at Acme.Order.Total() in /app/Order.cs:line 42"
            ), CapturedError.DEBUG);

            assertNotNull(parsed);
            assertEquals(Origin.DOTNET, parsed.origin());
            assertEquals("System.NullReferenceException", parsed.type());
            assertEquals(new Frame("/app/Order.cs", 42, 0, "Acme.Order.Total()"), parsed.frames().get(0));
        }

        @Test void readsARubyError() {
            ParsedError parsed = ErrorParser.parse(lines(
                "/app/order.rb:42:in `total': undefined method `price' for nil (NoMethodError)",
                "\tfrom /app/main.rb:11:in `<main>'"
            ), CapturedError.TERMINAL);

            assertNotNull(parsed);
            assertEquals(Origin.RUBY, parsed.origin());
            assertEquals("NoMethodError", parsed.type());
            assertEquals("/app/order.rb", parsed.frames().get(0).file());
            assertEquals(42, parsed.frames().get(0).line());
        }

        @Test void readsAGradleFailureWithNoTraceAtAll() {
            ParsedError parsed = ErrorParser.parse(lines(
                "FAILURE: Build failed with an exception.",
                "* What went wrong:",
                "Execution failed for task ':compileJava'."
            ), CapturedError.RUN);

            assertNotNull(parsed);
            assertEquals(Origin.PACKAGE_MANAGER, parsed.origin());
            assertTrue(parsed.headline().contains("Build failed"));
        }

        @Test void stripsColourCodesBeforeReading() {
            ParsedError parsed = ErrorParser.parse(ESC + "[31mTypeError" + ESC + "[0m: boom", CapturedError.RUN);
            assertNotNull(parsed);
            assertEquals("TypeError: boom", parsed.headline());
        }

        @Test void returnsNullWhenNothingFailed() {
            assertNull(ErrorParser.parse("Compiled successfully in 2.1s", CapturedError.RUN));
            assertNull(ErrorParser.parse("   ", CapturedError.RUN));
        }
    }

    @Nested
    @DisplayName("ranking frames")
    class Ranking {

        @Test void putsTheProjectsOwnFilesAheadOfDependenciesAndRuntimes() {
            List<Frame> ranked = ErrorParser.rankFrames(List.of(
                new Frame("node:internal/modules/cjs/loader", 1105),
                new Frame("/app/node_modules/express/lib/router.js", 47),
                new Frame("/app/src/order.ts", 42)
            ));
            assertEquals("/app/src/order.ts", ranked.get(0).file());
        }

        @Test void keepsThePrintedOrderWithinEachGroup() {
            List<Frame> ranked = ErrorParser.rankFrames(List.of(
                new Frame("/app/src/a.ts", 1),
                new Frame("/app/src/b.ts", 2)
            ));
            assertEquals(List.of("/app/src/a.ts", "/app/src/b.ts"),
                ranked.stream().map(Frame::file).toList());
        }
    }

    @Nested
    @DisplayName("labelling")
    class Labelling {

        @Test void namesTheFileTheFailureCameFrom() {
            ParsedError parsed = ErrorParser.parse(lines(
                "TypeError: boom",
                "    at total (/app/src/order.ts:42:25)"
            ), CapturedError.TERMINAL);

            assertNotNull(parsed);
            assertEquals("TypeError: boom — order.ts:42", ErrorParser.describe(parsed));
        }

        @Test void fallsBackToTheHeadlineWhenNoFrameNamedAFile() {
            ParsedError parsed = ErrorParser.parse("BUILD FAILED in 3s", CapturedError.RUN);
            assertNotNull(parsed);
            assertEquals(parsed.headline(), ErrorParser.describe(parsed));
        }
    }
}
