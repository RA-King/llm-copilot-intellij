package com.llmcopilot.services;

import com.llmcopilot.review.CodeReview;
import com.llmcopilot.services.LLMClient.ChatMessage;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prompts for fixing a reported problem, shell commands and pull requests. Mirrors assistPrompts.ts. */
public final class AssistPrompts {

    private AssistPrompts() {}

    // ── Fixing a reported problem ────────────────────────────────────────────

    /**
     * Asks for the window around a problem back in full, corrected. Returning
     * the whole window rather than a patch is what lets the edit be applied
     * without the model having to get offsets right.
     */
    public static List<ChatMessage> problemFix(String language, String file, List<String> problems,
                                               int firstLine, String code, String declarations) {
        int lastLine = firstLine + code.split("\n", -1).length - 1;
        StringBuilder user = new StringBuilder(file).append("\n\nProblems:\n");
        for (String p : problems) user.append("- ").append(p).append('\n');
        user.append('\n').append(CodeReview.numberLines(code, firstLine));
        if (declarations != null && !declarations.isEmpty()) user.append("\n\n").append(declarations);
        return List.of(
            new ChatMessage("system",
                "You are an expert " + language + " programmer fixing a reported problem. " +
                "Reply with lines " + firstLine + "–" + lastLine + " rewritten so the problem is gone, as a single " +
                "fenced " + language + " code block, without the line numbers. Change only what the fix needs, " +
                "keep every other line exactly as it is, and keep the indentation. No explanation."),
            new ChatMessage("user", user.toString()));
    }

    public static List<ChatMessage> problemExplain(String language, String file, List<String> problems, String code) {
        return List.of(
            new ChatMessage("system",
                "You are an expert programmer. Explain what the reported problem means, why this code " +
                "triggers it, and how to fix it, with the corrected code in a fenced block. Be concise."),
            new ChatMessage("user", file + "\n\n" + String.join("\n", problems) +
                "\n\n```" + language + "\n" + code + "\n```"));
    }

    private static final Pattern FENCE = Pattern.compile("```[^\\n]*\\n([\\s\\S]*?)```");

    /** The first fenced block in a reply, or the whole reply when it has none. */
    public static String extractCodeBlock(String reply) {
        Matcher m = FENCE.matcher(reply);
        return (m.find() ? m.group(1) : reply).stripTrailing();
    }

    // ── Terminal commands ────────────────────────────────────────────────────

    public record TerminalSuggestion(String command, String explanation) {}

    public static List<ChatMessage> terminalCommand(String request, String shell, String platform, String cwd) {
        String where = cwd != null && !cwd.isEmpty() ? " The working directory is " + cwd + "." : "";
        return List.of(
            new ChatMessage("system",
                "You turn requests into a single " + shell + " command for " + platform + "." + where + " " +
                "Reply with the command in one fenced code block, then one line explaining what it does. " +
                "Prefer standard tools that are present by default. Chain steps with && rather than giving " +
                "several commands. Never suggest anything destructive unless it was explicitly asked for."),
            new ChatMessage("user", request));
    }

    public static TerminalSuggestion parseTerminalSuggestion(String reply) {
        Matcher m = FENCE.matcher(reply);
        String command;
        String explanation;
        if (m.find()) {
            command = m.group(1);
            explanation = reply.substring(m.end());
        } else {
            String[] lines = reply.trim().split("\n", 2);
            command = lines[0];
            explanation = lines.length > 1 ? lines[1] : "";
        }
        StringBuilder cleaned = new StringBuilder();
        for (String line : command.trim().split("\n")) {
            if (cleaned.length() > 0) cleaned.append('\n');
            cleaned.append(line.replaceFirst("^\\s*[$>%#]\\s+", ""));
        }
        return new TerminalSuggestion(cleaned.toString().trim(), explanation.trim().replaceAll("\\s+", " "));
    }

    // ── Pull request descriptions ────────────────────────────────────────────

    public static List<ChatMessage> pullRequest(String branch, String base, String log, String stat,
                                                String diff, int maxDiffChars) {
        String body = diff.length() > maxDiffChars ? diff.substring(0, maxDiffChars) + "\n… (diff truncated)" : diff;
        return List.of(
            new ChatMessage("system",
                "You write pull request descriptions. Reply in markdown: the first line is the title on its own " +
                "(no heading marker, under 70 characters, imperative), then a blank line, then a \"## Summary\" " +
                "section of a few bullets on what changed and why, and a \"## Testing\" section on how it was or " +
                "should be verified. Describe what the commits and diff actually show; do not invent motivation."),
            new ChatMessage("user",
                "Branch " + branch + " into " + base + ".\n\nCommits:\n" + (log.isBlank() ? "(none)" : log) +
                "\n\nFiles changed:\n" + (stat.isBlank() ? "(none)" : stat) + "\n\nDiff:\n" + body));
    }
}
