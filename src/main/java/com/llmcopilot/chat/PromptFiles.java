package com.llmcopilot.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reusable prompts kept in the repository as {@code .github/prompts/<name>.prompt.md},
 * the layout GitHub Copilot uses. Each one becomes a slash command in the chat
 * panel: {@code /<name> anything else} sends the file's body with its variables
 * filled in. Mirrors promptFiles.ts.
 *
 * <p>Variables follow Copilot's spelling: {@code ${selection}}, {@code ${selectedText}},
 * {@code ${file}}, {@code ${fileBasename}}, {@code ${fileDirname}}, {@code ${workspaceFolder}},
 * and {@code ${input:name}} / {@code ${input:name:hint}} for whatever was typed after the command.
 */
public final class PromptFiles {

    private PromptFiles() {}

    public record PromptFile(String name, String description, String body, String source) {}

    public record Variables(String selection, String file, String workspaceFolder, String input) {}

    public static PromptFile parse(String fileName, String text, String source) {
        CustomInstructions.FrontMatter fm = CustomInstructions.parseFrontMatter(text);
        String name = fileName.replaceFirst("^.*[\\\\/]", "")
            .replaceFirst("(?i)\\.prompt\\.md$", "")
            .replaceFirst("(?i)\\.md$", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", "-");
        String description = fm.fields().getOrDefault("description", firstLine(fm.body()));
        return new PromptFile(name, description, fm.body(), source);
    }

    private static String firstLine(String body) {
        for (String line : body.split("\n")) {
            if (!line.isBlank()) {
                String text = line.trim().replaceFirst("^#+\\s*", "");
                return text.length() > 80 ? text.substring(0, 80) : text;
            }
        }
        return "";
    }

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(input:[^}]*|\\w+)}");

    public static String expand(String body, Variables vars) {
        String file = vars.file().replace('\\', '/');
        int slash = file.lastIndexOf('/');
        Map<String, String> values = new HashMap<>();
        values.put("selection", vars.selection());
        values.put("selectedText", vars.selection());
        values.put("file", file);
        values.put("fileBasename", slash >= 0 ? file.substring(slash + 1) : file);
        values.put("fileDirname", slash >= 0 ? file.substring(0, slash) : ".");
        values.put("workspaceFolder", vars.workspaceFolder());

        boolean usedInput = false;
        Matcher m = VARIABLE.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value;
            if (key.startsWith("input:")) {
                usedInput = true;
                String[] parts = key.split(":", 3);
                value = !vars.input().isEmpty() ? vars.input() : parts.length > 2 ? parts[2] : "";
            } else {
                value = values.getOrDefault(key, m.group());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);

        // Text typed after the command still matters when the prompt has nowhere
        // to put it; it reads as a further instruction.
        String typed = vars.input().trim();
        return !usedInput && !typed.isEmpty() ? out + "\n\n" + typed : out.toString();
    }

    public static List<PromptFile> load(String basePath) {
        if (basePath == null) return List.of();
        Path root = Path.of(basePath);
        Path dir = root.resolve(".github/prompts");
        if (!Files.isDirectory(dir)) return List.of();

        Map<String, PromptFile> found = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".prompt.md"))
                .sorted()
                .limit(100)
                .forEach(p -> {
                    try {
                        String text = Files.readString(p, StandardCharsets.UTF_8);
                        PromptFile prompt = parse(p.getFileName().toString(), text,
                            root.relativize(p).toString().replace('\\', '/'));
                        if (!prompt.body().isEmpty()) found.putIfAbsent(prompt.name(), prompt);
                    } catch (IOException ignored) { }
                });
        } catch (IOException ignored) { }
        return new ArrayList<>(found.values());
    }
}
