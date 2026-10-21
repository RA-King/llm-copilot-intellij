package com.llmcopilot.chat;

import com.llmcopilot.services.LLMClient;
import com.llmcopilot.settings.LLMCopilotSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Repository instructions, read from the same places GitHub Copilot reads them,
 * so a project that already has them set up gets the same behaviour here
 * without writing anything twice:
 *
 * <ul>
 *   <li>{@code .github/copilot-instructions.md} — always applies</li>
 *   <li>{@code .github/instructions/*.instructions.md} — applies to files matching {@code applyTo}</li>
 *   <li>{@code AGENTS.md} — always applies</li>
 * </ul>
 *
 * The text goes at the front of every chat request. Mirrors customInstructions.ts.
 */
public final class CustomInstructions {

    private CustomInstructions() {}

    /** One file's worth: where it came from, its {@code applyTo} globs (empty = everything), and its text. */
    public record InstructionFile(String source, List<String> applyTo, String body) {}

    public record FrontMatter(Map<String, String> fields, String body) {}

    private static final Pattern HEADER = Pattern.compile("^---\n([\\s\\S]*?)\n---\n?");
    private static final Pattern FIELD  = Pattern.compile("^\\s*([\\w-]+)\\s*:\\s*(.*)$");

    /** Splits a leading {@code ---} block off a markdown file. Only flat {@code key: value} pairs are read. */
    public static FrontMatter parseFrontMatter(String text) {
        String normalised = text.replaceFirst("^﻿", "").replace("\r\n", "\n");
        Matcher m = HEADER.matcher(normalised);
        if (!m.find()) return new FrontMatter(Map.of(), normalised.trim());

        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : m.group(1).split("\n")) {
            Matcher kv = FIELD.matcher(line);
            if (kv.matches()) fields.put(kv.group(1), unquote(kv.group(2).trim()));
        }
        return new FrontMatter(fields, normalised.substring(m.end()).trim());
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** {@code applyTo: "**&#47;*.ts, src/**"} to the individual globs. */
    public static List<String> splitGlobs(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.replaceAll("^\\[|]$", "").split(",")) {
            String glob = unquote(part.trim());
            if (!glob.isEmpty()) out.add(glob);
        }
        return out;
    }

    /** A minimal glob: {@code **} crosses directories, {@code *} and {@code ?} do not, {@code {a,b}} alternates. */
    public static Pattern globToPattern(String glob) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    boolean slash = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                    out.append(slash ? "(?:.*/)?" : ".*");
                    i += slash ? 2 : 1;
                } else {
                    out.append("[^/]*");
                }
            } else if (c == '?') {
                out.append("[^/]");
            } else if (c == '{') {
                int end = glob.indexOf('}', i);
                if (end < 0) { out.append("\\{"); continue; }
                StringJoiner alternatives = new StringJoiner("|", "(?:", ")");
                for (String alt : glob.substring(i + 1, end).split(",")) alternatives.add(Pattern.quote(alt));
                out.append(alternatives);
                i = end;
            } else {
                out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(out.toString());
    }

    public static boolean appliesTo(InstructionFile file, String relPath) {
        if (file.applyTo().isEmpty()) return true;
        if (relPath == null) return false;
        String target = relPath.replace('\\', '/');
        for (String glob : file.applyTo()) {
            if (glob.equals("**") || globToPattern(glob).matcher(target).matches()) return true;
        }
        return false;
    }

    /**
     * The instructions that apply to one file, as a single block for the system
     * prompt. Unconditional files come first, scoped ones after, each headed by
     * where it came from.
     */
    public static String compose(List<InstructionFile> files, String relPath) {
        List<InstructionFile> applicable = files.stream()
            .filter(f -> !f.body().isEmpty() && appliesTo(f, relPath))
            .sorted(Comparator.comparingInt(f -> f.applyTo().size()))
            .toList();
        if (applicable.isEmpty()) return "";
        StringJoiner out = new StringJoiner("\n\n", "Follow these instructions from the repository:\n\n", "");
        for (InstructionFile f : applicable) out.add("<!-- " + f.source() + " -->\n" + f.body());
        return out.toString();
    }

    /** Folds instructions into the system message, adding one when there is none. */
    public static List<LLMClient.ChatMessage> withInstructions(List<LLMClient.ChatMessage> messages, String instructions) {
        if (instructions == null || instructions.isEmpty()) return messages;
        List<LLMClient.ChatMessage> out = new ArrayList<>(messages);
        for (int i = 0; i < out.size(); i++) {
            LLMClient.ChatMessage m = out.get(i);
            if ("system".equals(m.role())) {
                out.set(i, new LLMClient.ChatMessage("system", m.content() + "\n\n" + instructions));
                return out;
            }
        }
        out.add(0, new LLMClient.ChatMessage("system", instructions));
        return out;
    }

    // ── Loading from disk ────────────────────────────────────────────────────

    private static final List<String> ALWAYS = List.of(".github/copilot-instructions.md", "AGENTS.md");

    public static List<InstructionFile> load(Path root) {
        List<InstructionFile> files = new ArrayList<>();
        if (root == null) return files;

        for (String name : ALWAYS) {
            String text = read(root.resolve(name));
            if (text != null) files.add(new InstructionFile(name, List.of(), parseFrontMatter(text).body()));
        }

        Path scoped = root.resolve(".github/instructions");
        if (Files.isDirectory(scoped)) {
            try (Stream<Path> walk = Files.walk(scoped, 4)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".instructions.md"))
                    .sorted()
                    .limit(50)
                    .forEach(p -> {
                        String text = read(p);
                        if (text == null) return;
                        FrontMatter fm = parseFrontMatter(text);
                        files.add(new InstructionFile(
                            root.relativize(p).toString().replace('\\', '/'),
                            splitGlobs(fm.fields().get("applyTo")),
                            fm.body()));
                    });
            } catch (IOException ignored) { }
        }
        return files;
    }

    /** The instructions for one file of the project at {@code basePath}, or "" when switched off. */
    public static String forFile(String basePath, String relPath) {
        if (basePath == null || !LLMCopilotSettings.getInstance().isCustomInstructions()) return "";
        return compose(load(Path.of(basePath)), relPath);
    }

    private static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
