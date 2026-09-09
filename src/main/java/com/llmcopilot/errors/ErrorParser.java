package com.llmcopilot.errors;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a block of run, debug or terminal output and works out what actually
 * went wrong: which runtime or tool printed it, the exception class and message,
 * and the frames that name a real file and line.
 *
 * <p>The point is not to pretty-print the trace — the user can already read it
 * in the console it came from. It is to find the one or two files the failure is
 * really about, so the source around those lines can be sent with the question.
 * A stack trace without its code is guesswork; with it, the model is answering
 * about the program that is actually running.
 *
 * <p>Traces are matched by shape rather than by asking the IDE what is open: a
 * terminal may be running anything, and a Java frame
 * {@code at com.acme.Order.total(Order.java:42)} cannot be confused with
 * {@code File "orders.py", line 42, in total}. Everything here is pure text
 * work, so it is fully covered by unit tests.
 *
 * <p>Mirrors {@code errorParser.ts} in the VS Code extension.
 */
public final class ErrorParser {

    private ErrorParser() { }

    // ── Types ────────────────────────────────────────────────────────────────

    /** The runtime or tool whose trace format the output matches. */
    public enum Origin {
        NODE, PYTHON, JAVA, DOTNET, GO, RUST, RUBY, PHP, COMPILER, PACKAGE_MANAGER, UNKNOWN;

        /** Lower-case name, as it is written into a prompt. */
        public String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** One line of a stack trace. A line or column of 0 means the format carried none. */
    public record Frame(String file, int line, int column, String symbol) {
        public Frame(String file, int line) { this(file, line, 0, null); }
    }

    public record ParsedError(
        /** Which panel the output was captured from: "terminal", "run" or "debug". */
        String source,
        Origin origin,
        /** Exception class or diagnostic code, or null when the output names none. */
        String type,
        String message,
        /** {@code type: message}, trimmed to something that fits in a list. */
        String headline,
        List<Frame> frames,
        /** The cleaned-up text the rest of this was read from. */
        String text
    ) { }

    // ── Cleaning ─────────────────────────────────────────────────────────────

    private static final String ESC = String.valueOf((char) 27);
    private static final String BEL = String.valueOf((char) 7);

    /** Colour codes, cursor moves, and the OSC sequences that set a window title. */
    private static final Pattern ANSI = Pattern.compile(
        ESC + "\\[[0-9;?]*[ -/]*[@-~]"
      + "|" + ESC + "\\][^" + BEL + ESC + "]*(?:" + BEL + "|" + ESC + "\\\\)"
      + "|" + ESC + "[@-Z\\\\-_]");

    public static String stripAnsi(String text) {
        return text == null ? "" : ANSI.matcher(text).replaceAll("");
    }

    private static List<String> normalise(String raw) {
        String cleaned = stripAnsi(raw).replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
        return new ArrayList<>(List.of(cleaned.split("\n", -1)));
    }

    // ── Recognising a failure ────────────────────────────────────────────────

    private static final Pattern ERROR_WORDS = Pattern.compile(
        "\\b(error|errors|exception|fatal|panic|traceback|failed|failure|refused|denied|"
      + "not found|cannot find|no such file|segmentation fault|assertion|unhandled|unresolved)\\b",
        Pattern.CASE_INSENSITIVE);

    /** {@code 0 errors}, {@code error-handling.ts} — words that only look bad. */
    private static final Pattern FALSE_POSITIVE = Pattern.compile(
        "\\b(0 errors?|no errors?|error[-_]|errors?:\\s*0)\\b", Pattern.CASE_INSENSITIVE);

    /** A thrown class name carries no word boundary of its own: {@code TypeError}. */
    private static final Pattern EXCEPTION_NAME =
        Pattern.compile("\\b[A-Z]\\w*(?:Error|Exception|Fault|Panic)\\b");

    private static final Pattern REALLY_BAD =
        Pattern.compile("exception|traceback|panic", Pattern.CASE_INSENSITIVE);

    /** True when a line could plausibly open a failure worth analysing. */
    public static boolean isSignalLine(String line) {
        String text = line.trim();
        if (text.isEmpty()) return false;
        if (FALSE_POSITIVE.matcher(text).find() && !REALLY_BAD.matcher(text).find()) return false;
        if (ERROR_WORDS.matcher(text).find() || EXCEPTION_NAME.matcher(text).find()) return true;
        for (FramePattern p : DIAGNOSTIC_PATTERNS) {
            if (p.re.matcher(text).find()) return true;
        }
        return false;
    }

    /** True when the output looks like something failed rather than merely ran. */
    public static boolean looksLikeError(String text) {
        for (String line : normalise(text)) {
            if (isSignalLine(line)) return true;
        }
        return false;
    }

    /**
     * Cuts a capture down to the part worth sending. A build can run to thousands
     * of lines and the failure is almost always at the end — so keep the tail,
     * then trim the front back to just before the first line that reads like an
     * error, leaving a little of the output that led into it.
     */
    public static String extractErrorBlock(String raw, int maxLines) {
        List<String> lines = normalise(raw);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        if (lines.isEmpty()) return "";

        List<String> tail = lines.size() > maxLines
            ? lines.subList(lines.size() - maxLines, lines.size())
            : lines;

        int first = -1;
        for (int i = 0; i < tail.size(); i++) {
            if (isSignalLine(tail.get(i))) { first = i; break; }
        }
        int start = first > 0 ? Math.max(0, first - 3) : 0;
        return String.join("\n", tail.subList(start, tail.size())).trim();
    }

    // ── Frame formats ────────────────────────────────────────────────────────

    /** Group indexes of 0 mean the format does not carry that part. */
    private record FramePattern(Origin origin, Pattern re, int file, int line, int column, int symbol) { }

    private static FramePattern frame(Origin origin, String re, int file, int line, int column, int symbol) {
        return new FramePattern(origin, Pattern.compile(re), file, line, column, symbol);
    }

    /**
     * Ordered within each origin from most specific to least. A line is only
     * tried against its own runtime's patterns and the compiler diagnostics,
     * which turn up interleaved in build output whatever is being built.
     */
    private static final List<FramePattern> FRAME_PATTERNS = List.of(
        // Node:  at total (/app/src/order.ts:42:15)      at /app/src/order.ts:42:15
        frame(Origin.NODE, "^\\s*at\\s+(?:async\\s+)?([\\w$.<>\\[\\]\\s]+?)\\s+\\((.+?):(\\d+):(\\d+)\\)\\s*$", 2, 3, 4, 1),
        frame(Origin.NODE, "^\\s*at\\s+(.+?):(\\d+):(\\d+)\\s*$", 1, 2, 3, 0),

        // Python:  File "/app/orders.py", line 42, in total
        frame(Origin.PYTHON, "^\\s*File\\s+\"(.+?)\",\\s+line\\s+(\\d+)(?:,\\s+in\\s+(.+))?\\s*$", 1, 2, 0, 3),

        // Java / Kotlin / Scala:  at com.acme.Order.total(Order.java:42)
        frame(Origin.JAVA, "^\\s*at\\s+([\\w$.<>]+)\\(([\\w$.-]+\\.(?:java|kt|kts|scala|groovy)):(\\d+)\\)", 2, 3, 0, 1),

        // .NET:  at Acme.Order.Total() in /app/Order.cs:line 42
        frame(Origin.DOTNET, "^\\s*at\\s+(.+?)\\s+in\\s+(.+?):line\\s+(\\d+)\\s*$", 2, 3, 0, 1),

        // Go:  the file line printed under the function line, with the PC offset
        frame(Origin.GO, "^\\s*((?:[A-Za-z]:)?[^\\s:]+\\.go):(\\d+)(?:\\s+\\+0x[0-9a-f]+)?\\s*$", 1, 2, 0, 0),

        // Rust:  thread 'main' panicked at src/main.rs:42:9   /   at src/main.rs:42
        frame(Origin.RUST, "panicked at\\s+(.+?\\.rs):(\\d+):(\\d+)", 1, 2, 3, 0),
        frame(Origin.RUST, "^\\s*(?:\\d+:\\s+)?\\s*at\\s+(.+?\\.rs):(\\d+)(?::(\\d+))?\\s*$", 1, 2, 3, 0),

        // Ruby:  from /app/order.rb:42:in `total'
        frame(Origin.RUBY, "^\\s*(?:from\\s+)?(.+?\\.rb):(\\d+):in\\s+[`'](.+?)'", 1, 2, 0, 3),

        // PHP:  #0 /app/Order.php(42): total()      ... in /app/Order.php on line 42
        frame(Origin.PHP, "^#\\d+\\s+(.+?\\.php)\\((\\d+)\\)(?::\\s*(.+))?", 1, 2, 0, 3),
        frame(Origin.PHP, "\\sin\\s+(.+?\\.php)\\s+on\\s+line\\s+(\\d+)", 1, 2, 0, 0),
        frame(Origin.PHP, "\\s(\\S+?\\.php):(\\d+)", 1, 2, 0, 0)
    );

    /**
     * Compiler and linter diagnostics. These carry the location <em>and</em> the
     * message, so they are read both as frames and as the failure itself.
     */
    private static final List<FramePattern> DIAGNOSTIC_PATTERNS = List.of(
        // Java / TypeScript / MSBuild:  src/order.ts(42,15): error TS2345: message
        frame(Origin.COMPILER, "^(.+?)\\((\\d+),(\\d+)\\):\\s*(?:fatal\\s+)?error\\b", 1, 2, 3, 0),
        // gcc / clang / tsc --pretty:  src/order.c:42:15: error: message
        frame(Origin.COMPILER, "^(.+?):(\\d+):(\\d+):\\s*(?:fatal\\s+)?(?:error|Error)\\b", 1, 2, 3, 0),
        // javac:  /app/Order.java:42: error: message
        frame(Origin.COMPILER, "^(.+?):(\\d+):\\s*(?:fatal\\s+)?error\\b", 1, 2, 0, 0),
        // rustc's location arrow:  --> src/main.rs:42:9
        frame(Origin.COMPILER, "^\\s*-->\\s*(.+?):(\\d+)(?::(\\d+))?\\s*$", 1, 2, 3, 0)
    );

    /** Last resort: any {@code path.ext:line} or {@code path.ext(line)} in the text. */
    private static final Pattern LOOSE_FRAME = Pattern.compile(
        "(?:^|[\\s(\\['\"])((?:[A-Za-z]:)?[\\w./\\\\@+-]+\\.[A-Za-z]\\w{0,4})[:(](\\d+)(?:[:,](\\d+))?\\)?");

    // ── Origin ───────────────────────────────────────────────────────────────

    private record OriginSignature(Origin origin, Pattern re) { }

    private static OriginSignature signature(Origin origin, String re) {
        return new OriginSignature(origin, Pattern.compile(re, Pattern.MULTILINE));
    }

    /** Checked in order; the first runtime whose fingerprint appears wins. */
    private static final List<OriginSignature> ORIGIN_SIGNATURES = List.of(
        signature(Origin.PYTHON, "^Traceback \\(most recent call last\\)|^\\s*File\\s+\".+?\",\\s+line\\s+\\d+"),
        signature(Origin.JAVA,   "^(?:Exception in thread|Caused by:)|^\\s*at\\s+[\\w$.]+\\([\\w$.-]+\\.(?:java|kt|kts|scala|groovy):\\d+\\)"),
        signature(Origin.DOTNET, "^Unhandled exception\\.|^\\s*at\\s+.+\\s+in\\s+.+:line\\s+\\d+"),
        signature(Origin.GO,     "^goroutine \\d+ \\[|^panic:\\s"),
        signature(Origin.RUST,   "panicked at|^error\\[E\\d+\\]"),
        signature(Origin.RUBY,   "\\.rb:\\d+:in\\s+[`']"),
        signature(Origin.PHP,    "^PHP (?:Fatal error|Warning|Parse error|Notice)|^#\\d+\\s+.+\\.php\\(\\d+\\)"),
        signature(Origin.NODE,   "^\\s*at\\s+.+:\\d+:\\d+\\)?\\s*$|node:internal|^\\s*at\\s+[\\w$.]+\\s+\\(")
    );

    private static final Pattern PACKAGE_MANAGER = Pattern.compile(
        "^(?:npm ERR!|yarn error|pnpm ERR!|FAILURE: Build failed|\\[ERROR\\]\\s|BUILD FAILED|"
      + "error Command failed|Execution failed for task)", Pattern.MULTILINE);

    private static Origin detectOrigin(String text) {
        for (OriginSignature sig : ORIGIN_SIGNATURES) {
            if (sig.re.matcher(text).find()) return sig.origin;
        }
        for (FramePattern p : DIAGNOSTIC_PATTERNS) {
            if (Pattern.compile(p.re.pattern(), Pattern.MULTILINE).matcher(text).find()) return Origin.COMPILER;
        }
        if (PACKAGE_MANAGER.matcher(text).find()) return Origin.PACKAGE_MANAGER;
        return Origin.UNKNOWN;
    }

    // ── Headline ─────────────────────────────────────────────────────────────

    /** A group index of 0 for the message means "the whole line". */
    private record HeadlinePattern(Origin origin, Pattern re, int type, int message, boolean fromBottom) { }

    private static HeadlinePattern headline(Origin origin, String re, int type, int message) {
        return new HeadlinePattern(origin, Pattern.compile(re), type, message, false);
    }

    private static final List<HeadlinePattern> HEADLINE_PATTERNS = List.of(
        // Python's exception line closes the traceback.
        new HeadlinePattern(Origin.PYTHON,
            Pattern.compile("^([\\w.]*(?:Error|Exception|Interrupt|Exit|Warning))(?::\\s*(.*))?$"), 1, 2, true),

        headline(Origin.JAVA,   "^(?:Exception in thread\\s+\".*?\"\\s+)?(?:Caused by:\\s+)?([\\w$.]+(?:Exception|Error|Throwable))(?::\\s*(.*))?$", 1, 2),
        headline(Origin.DOTNET, "^(?:Unhandled exception\\.\\s*)?([\\w.]+Exception):\\s*(.*)$", 1, 2),
        headline(Origin.GO,     "^panic:\\s*(.*)$", 0, 1),
        headline(Origin.RUST,   "^thread\\s+'.*?'\\s+panicked at\\s+(.*)$", 0, 1),
        headline(Origin.RUST,   "^(error\\[E\\d+\\]|error):\\s*(.*)$", 1, 2),
        headline(Origin.RUBY,   "^.+?:\\d+:in\\s+[`'].+?':\\s*(.*?)\\s*\\((\\w+(?:Error|Exception))\\)\\s*$", 2, 1),
        headline(Origin.PHP,    "^PHP\\s+(?:Fatal error|Parse error|Warning):\\s+(?:Uncaught\\s+)?(?:([\\w\\\\]+):\\s*)?(.*)$", 1, 2),

        // Compiler diagnostics carry a code worth keeping — TS2345, E0308, CS1002.
        headline(Origin.COMPILER, "^.+?\\(\\d+,\\d+\\):\\s*(?:fatal\\s+)?error\\s+(\\w+)?:?\\s*(.*)$", 1, 2),
        headline(Origin.COMPILER, "^.+?:\\d+(?::\\d+)?:\\s*(?:fatal\\s+)?error(?:\\[(\\w+)\\])?:\\s*(.*)$", 1, 2),
        headline(Origin.COMPILER, "^error\\[(E\\d+)\\]:\\s*(.*)$", 1, 2),

        headline(Origin.PACKAGE_MANAGER, "^npm ERR!\\s+(?!code\\b|errno\\b|syscall\\b|path\\b|A complete log)(.*)$", 0, 1),
        headline(Origin.PACKAGE_MANAGER, "^\\[ERROR\\]\\s+(.*)$", 0, 1),
        headline(Origin.PACKAGE_MANAGER, "^(?:FAILURE: Build failed.*|BUILD FAILED.*|error Command failed.*|Execution failed for task.*)$", 0, 0),

        // Anything at all: a thrown class name, then any line that reads like a failure.
        headline(null, "^(?:Uncaught\\s+)?([A-Z][\\w$.]*(?:Error|Exception|Fault)):\\s*(.*)$", 1, 2),
        new HeadlinePattern(null,
            Pattern.compile("^.*?\\b(?:error|fatal|failed|failure|panic)\\b.*$", Pattern.CASE_INSENSITIVE), 0, 0, false)
    );

    private record Headline(String type, String message) { }

    private static Headline findHeadline(List<String> lines, Origin origin) {
        for (HeadlinePattern pattern : HEADLINE_PATTERNS) {
            if (pattern.origin != null && pattern.origin != origin) continue;

            List<String> order = new ArrayList<>(lines);
            if (pattern.fromBottom) java.util.Collections.reverse(order);

            for (String raw : order) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                Matcher m = pattern.re.matcher(line);
                if (!m.find()) continue;

                String type = pattern.type > 0 ? m.group(pattern.type) : null;
                String message = pattern.message == 0 ? m.group() : m.group(pattern.message);
                if (message == null) message = "";
                if ((type == null || type.isBlank()) && message.isBlank()) continue;
                return new Headline(type == null || type.isBlank() ? null : type.trim(), message.trim());
            }
        }
        for (String line : lines) {
            if (!line.isBlank()) return new Headline(null, line.trim());
        }
        return new Headline(null, "");
    }

    // ── Frames ───────────────────────────────────────────────────────────────

    private static Frame toFrame(Matcher m, FramePattern p) {
        String file = m.group(p.file);
        if (file == null) return null;
        file = file.trim();
        if (file.isEmpty() || file.startsWith("<")) return null;

        int line = number(m, p.line);
        int column = number(m, p.column);
        String symbol = p.symbol > 0 ? m.group(p.symbol) : null;
        if (symbol != null) {
            symbol = symbol.trim();
            if (symbol.isEmpty()) symbol = null;
        }
        return new Frame(file, line, column, symbol);
    }

    private static int number(Matcher m, int group) {
        if (group == 0) return 0;
        String text = m.group(group);
        if (text == null) return 0;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<Frame> collectFrames(List<String> lines, Origin origin) {
        List<FramePattern> patterns = new ArrayList<>();
        for (FramePattern p : FRAME_PATTERNS) {
            if (p.origin == origin) patterns.add(p);
        }
        patterns.addAll(DIAGNOSTIC_PATTERNS);

        List<Frame> frames = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String line : lines) {
            for (FramePattern p : patterns) {
                Matcher m = p.re.matcher(line);
                if (!m.find()) continue;
                add(frames, seen, toFrame(m, p));
                break;
            }
        }

        if (frames.isEmpty()) {
            for (String line : lines) {
                Matcher m = LOOSE_FRAME.matcher(line);
                if (m.find()) {
                    add(frames, seen, new Frame(m.group(1), number(m, 2), number(m, 3), null));
                }
            }
        }

        return frames.size() > 12 ? new ArrayList<>(frames.subList(0, 12)) : frames;
    }

    private static void add(List<Frame> frames, Set<String> seen, Frame frame) {
        if (frame == null) return;
        if (seen.add(frame.file() + ":" + frame.line())) frames.add(frame);
    }

    /** Paths that belong to a dependency, a runtime, or the language itself. */
    private static final List<Pattern> NOT_MINE = List.of(
        Pattern.compile("node_modules"), Pattern.compile("(?:^|/)internal/"), Pattern.compile("^node:"),
        Pattern.compile("site-packages"), Pattern.compile("dist-packages"),
        Pattern.compile("/lib/python[\\d.]+/"), Pattern.compile("(?:^|/)vendor/"),
        Pattern.compile("\\.cargo[/\\\\]registry"), Pattern.compile("/usr/(?:lib|local)/"),
        Pattern.compile("(?:^|/)runtime[/\\\\][\\w.]+\\.go$"),
        Pattern.compile("^java\\.|^javax\\.|^jdk\\.|^sun\\."),
        Pattern.compile("/gems?/"), Pattern.compile("\\.gradle[/\\\\]caches")
    );

    /**
     * Reorders frames so the ones in the user's own code come first. A trace
     * often starts several layers deep inside a framework; the frame worth
     * reading source for is the first one that is not.
     */
    public static List<Frame> rankFrames(List<Frame> frames) {
        List<Frame> mine = new ArrayList<>();
        List<Frame> theirs = new ArrayList<>();
        for (Frame frame : frames) {
            boolean external = false;
            for (Pattern re : NOT_MINE) {
                if (re.matcher(frame.file()).find()) { external = true; break; }
            }
            (external ? theirs : mine).add(frame);
        }
        mine.addAll(theirs);
        return mine;
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    private static String buildHeadline(String type, String message) {
        String joined;
        if (type != null && !message.isEmpty()) joined = type + ": " + message;
        else if (type != null) joined = type;
        else joined = message;

        String oneLine = joined.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 120 ? oneLine.substring(0, 117) + "…" : oneLine;
    }

    /**
     * Reads captured output into a {@link ParsedError}, or returns null when the
     * text carries no sign of a failure at all — a clean build, a passing run.
     */
    public static ParsedError parse(String raw, String source) {
        String text = extractErrorBlock(raw == null ? "" : raw, 200);
        if (text.isEmpty()) return null;

        List<String> lines = List.of(text.split("\n", -1));
        Origin origin = detectOrigin(text);
        List<Frame> frames = collectFrames(lines, origin);
        Headline headline = findHeadline(lines, origin);

        if (headline.type == null && frames.isEmpty()) {
            boolean signal = false;
            for (String line : lines) {
                if (isSignalLine(line)) { signal = true; break; }
            }
            if (!signal) return null;
        }

        return new ParsedError(source, origin, headline.type, headline.message,
            buildHeadline(headline.type, headline.message), frames, text);
    }

    /** How a parsed failure is labelled in a picker or a chat bubble. */
    public static String describe(ParsedError error) {
        List<Frame> ranked = rankFrames(error.frames());
        if (ranked.isEmpty()) return error.headline();
        Frame where = ranked.get(0);
        String name = where.file().replaceAll(".*[/\\\\]", "");
        return error.headline() + " — " + name + (where.line() > 0 ? ":" + where.line() : "");
    }
}
