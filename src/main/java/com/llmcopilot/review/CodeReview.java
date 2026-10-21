package com.llmcopilot.review;

import com.llmcopilot.services.LLMClient.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Review of a selection or of the uncommitted changes, answered as findings
 * pinned to lines. The model is shown every line with the number it has in
 * the file as it now stands, so the numbers it quotes back can be trusted
 * without it counting through diff hunks. Mirrors codeReview.ts.
 */
public final class CodeReview {

    private CodeReview() {}

    public enum Severity { ERROR, WARNING, INFO }

    /** {@code file} is null when reviewing a single file; {@code line} is one-based. */
    public record Finding(String file, int line, Severity severity, String message) {}

    /** Prefix each line with its one-based number, starting from {@code firstLine}. */
    public static String numberLines(String code, int firstLine) {
        String[] lines = code.split("\n", -1);
        int width = String.valueOf(firstLine + lines.length - 1).length();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(pad(String.valueOf(firstLine + i), width)).append(" | ").append(lines[i]);
        }
        return out.toString();
    }

    private static String pad(String s, int width) {
        return " ".repeat(Math.max(0, width - s.length())) + s;
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /**
     * A unified diff rewritten so every line still in the file carries its new
     * line number and removed lines are marked but unnumbered.
     */
    public static String annotateDiff(String diff) {
        List<String> out = new ArrayList<>();
        int newLine = 0;
        boolean inHunk = false;

        for (String line : diff.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("diff --git")) { inHunk = false; continue; }
            if (line.startsWith("+++ ")) {
                String target = line.substring(4).replaceFirst("^b/", "");
                if (!target.equals("/dev/null")) out.add("=== " + target);
                continue;
            }
            if (line.startsWith("--- ") || line.startsWith("index ") || line.matches("^(new|deleted) file mode.*")) continue;

            Matcher hunk = HUNK.matcher(line);
            if (hunk.find()) {
                newLine = Integer.parseInt(hunk.group(1));
                inHunk = true;
                out.add("   …");
                continue;
            }
            if (!inHunk) continue;

            if (line.startsWith("+"))      out.add(pad(String.valueOf(newLine++), 5) + " + " + line.substring(1));
            else if (line.startsWith("-")) out.add("      - " + line.substring(1));
            else if (line.startsWith(" ")) out.add(pad(String.valueOf(newLine++), 5) + "   " + line.substring(1));
        }
        return String.join("\n", out);
    }

    private static final String FORMAT =
        "Report each problem on its own line, exactly as:\n" +
        "path/to/file.ext:LINE: severity: message\n\n" +
        "severity is one of error, warning or info. LINE is the number shown beside the code. " +
        "The message says what is wrong and what to do instead, in one or two sentences. " +
        "Report real defects first — bugs, unhandled errors, security problems, races, wrong edge cases — " +
        "then maintainability problems that matter. Do not comment on formatting or style a linter would catch, " +
        "and do not praise. If there is nothing worth raising, reply with exactly: NO ISSUES";

    public static List<ChatMessage> selectionPrompt(String code, String language, String file, int firstLine) {
        return List.of(
            new ChatMessage("system", "You are a meticulous senior " + language + " code reviewer.\n\n" + FORMAT),
            new ChatMessage("user", "Review this code from " + file + ":\n\n" + numberLines(code, firstLine)));
    }

    public static List<ChatMessage> changesPrompt(String annotatedDiff) {
        return List.of(
            new ChatMessage("system",
                "You are a meticulous senior code reviewer reviewing uncommitted changes. " +
                "Lines marked + were added, lines marked - were removed, the rest are unchanged context. " +
                "Review only what the change introduces or breaks.\n\n" + FORMAT),
            new ChatMessage("user", "Review these changes:\n\n" + annotatedDiff));
    }

    private static final Pattern FINDING = Pattern.compile(
        "^(?:\\[?([^\\s:\\]]+?)]?:)?\\s*(?:line\\s+)?(\\d+)]?\\s*[:\\-–]\\s*(error|warning|info|note|suggestion)\\s*[:\\-–]\\s*(.+)$",
        Pattern.CASE_INSENSITIVE);

    public static List<Finding> parseFindings(String reply) {
        List<Finding> findings = new ArrayList<>();
        for (String raw : reply.split("\n")) {
            String line = raw.trim().replaceFirst("^[-*•]\\s+", "").replace("`", "");
            Matcher m = FINDING.matcher(line);
            if (!m.matches()) continue;
            String severity = m.group(3).toLowerCase(Locale.ROOT);
            findings.add(new Finding(
                m.group(1),
                Integer.parseInt(m.group(2)),
                severity.equals("error") ? Severity.ERROR : severity.equals("warning") ? Severity.WARNING : Severity.INFO,
                m.group(4).trim()));
        }
        return findings;
    }
}
