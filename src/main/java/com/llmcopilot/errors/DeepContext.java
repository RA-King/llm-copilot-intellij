package com.llmcopilot.errors;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.llmcopilot.index.ProjectIndexService;
import com.llmcopilot.settings.LLMCopilotSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an error needs around it before an answer is worth reading.
 *
 * <p>The source at the failing line is the obvious context, and on its own it
 * is rarely enough. {@code NullPointerException} at
 * {@code repo.findByCustomer(id)} cannot be answered from that line: the
 * answer is in whatever {@code repo} is, what that type declares, and who
 * constructed it. A model given only the failing line has to invent those, and
 * it does — confidently, and wrongly.
 *
 * <p>The project index knows all three, so the error pane asks it:
 *
 * <ul>
 *   <li>every identifier named in the message or on the failing lines is
 *       looked up, and its real declaration goes into the prompt;</li>
 *   <li>the files importing the failing one are listed, because a fix that
 *       changes a signature has to be a fix for them too;</li>
 *   <li>the project's manifest goes in, trimmed, because half of all runtime
 *       failures are a dependency, a version or a task;</li>
 *   <li>a short digest of the project's shape goes in first, so the answer is
 *       written for this codebase rather than for the language.</li>
 * </ul>
 *
 * <p>Mirrors {@code deepContext.ts}.
 */
public final class DeepContext {

    private DeepContext() { }

    /** The rendered block, plus what went into it, for the pane's own labelling. */
    public record Gathered(String text, List<String> resolvedNames, List<String> dependents) {
        public static final Gathered EMPTY = new Gathered("", List.of(), List.of());
        public boolean isEmpty() { return text.isEmpty(); }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

    /** Words that appear in every trace and resolve to nothing useful. */
    private static final Set<String> NOISE = Set.of(
        "error", "Error", "TypeError", "ValueError", "Exception", "RuntimeException",
        "NullPointerException", "IllegalArgumentException", "IllegalStateException",
        "null", "undefined", "None", "true", "false", "this", "self", "super",
        "function", "object", "Object", "string", "String", "number", "Number",
        "boolean", "Boolean", "Array", "List", "Map", "return", "const", "let",
        "var", "await", "async", "import", "from", "class", "interface", "public",
        "private", "static", "void", "int", "new", "throw", "catch", "try", "not",
        "the", "and", "for", "with", "has", "was", "main", "module", "line",
        "file", "at", "in", "is", "of", "to", "call", "stack", "trace",
        "Traceback", "Caused", "java", "lang", "util");

    private static final int MAX_NAMES = 24;

    // ── Gathering ────────────────────────────────────────────────────────────

    public static Gathered gather(
        Project project, ErrorParser.ParsedError parsed, List<SourceLookup.FrameContext> frames
    ) {
        LLMCopilotSettings settings = LLMCopilotSettings.getInstance();
        if (!settings.isErrorDeepContext()) return Gathered.EMPTY;

        ProjectIndexService index = ProjectIndexService.getInstance(project);
        if (index == null || !index.isReady()) return Gathered.EMPTY;

        int budget = settings.getErrorProjectChars();
        if (budget <= 0) return Gathered.EMPTY;

        List<String> sections = new ArrayList<>();
        int[] spent = { 0 };

        java.util.function.Predicate<String> take = block -> {
            if (block == null || block.isBlank()) return true;
            if (spent[0] + block.length() > budget) return false;
            sections.add(block);
            spent[0] += block.length();
            return true;
        };

        String digest = index.renderDigest();
        if (!digest.isEmpty()) take.test("── This project ──\n" + digest);

        // ── Identifiers worth resolving ──────────────────────────────────────
        List<String> names = identifiersToResolve(parsed, frames);
        String declarations = index.declarationsFor(names, (int) (budget * 0.4));

        List<String> resolvedNames = new ArrayList<>();
        for (String name : names) {
            if (!index.lookup(name, 1).isEmpty()) resolvedNames.add(name);
        }

        if (!declarations.isEmpty()) {
            take.test("── Where the names in this failure are declared ──\n"
                + "These are read from the project, not inferred. Use the real signatures.\n"
                + declarations);
        }

        // ── Who depends on the failing file ──────────────────────────────────
        List<String> dependents = List.of();
        SourceLookup.FrameContext failing = frames.isEmpty() ? null : frames.get(0);

        if (failing != null) {
            String relative = relative(project, failing.file());
            dependents = index.dependents(relative, 10);

            if (!dependents.isEmpty()) {
                take.test("── Callers of " + relative + " ──\n"
                    + "A change to what this file exports has to hold for these too:\n"
                    + indent(dependents));
            }

            List<String> imported = index.dependencies(relative);
            if (!imported.isEmpty()) {
                take.test("── " + relative + " depends on ──\n"
                    + indent(imported.subList(0, Math.min(10, imported.size()))));
            }
        }

        // ── The project manifest ─────────────────────────────────────────────
        String manifest = readManifest(project, index, budget - spent[0]);
        if (!manifest.isEmpty()) take.test(manifest);

        return new Gathered(String.join("\n\n", sections), resolvedNames, dependents);
    }

    // ── Identifier extraction ────────────────────────────────────────────────

    /**
     * The names worth looking up: the ones the error itself printed, then the
     * ones on the lines the trace pointed at. Ordered, because the budget runs
     * out and the message's own nouns are what the answer turns on.
     */
    public static List<String> identifiersToResolve(
        ErrorParser.ParsedError parsed, List<SourceLookup.FrameContext> frames
    ) {
        Set<String> ordered = new LinkedHashSet<>();

        addIdentifiers(ordered, parsed.headline());
        addIdentifiers(ordered, firstLines(parsed.text(), 4));

        for (ErrorParser.Frame frame : parsed.frames().subList(0, Math.min(6, parsed.frames().size()))) {
            if (frame.symbol() != null) addIdentifiers(ordered, frame.symbol());
        }

        // Then the code, restricted to the line the trace pointed at and its
        // immediate neighbours — the rest of the snippet is context, not cause.
        for (SourceLookup.FrameContext frame : frames.subList(0, Math.min(2, frames.size()))) {
            String[] lines = frame.snippet().split("\n", -1);
            int centre = frame.frame().line() > 0
                ? frame.frame().line() - 1 - frame.startLine()
                : lines.length / 2;

            for (int i = Math.max(0, centre - 1); i <= Math.min(lines.length - 1, centre + 1); i++) {
                if (i >= 0) addIdentifiers(ordered, lines[i]);
            }
        }

        return ordered.stream().limit(MAX_NAMES).toList();
    }

    private static void addIdentifiers(Set<String> into, String text) {
        if (text == null) return;
        Matcher m = IDENTIFIER.matcher(text);
        while (m.find() && into.size() < MAX_NAMES * 2) {
            String name = m.group();
            if (name.length() < 3 || NOISE.contains(name)) continue;
            into.add(name);
        }
    }

    private static String firstLines(String text, int count) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(count, lines.length); i++) {
            out.append(lines[i]).append(' ');
        }
        return out.toString();
    }

    // ── The manifest ─────────────────────────────────────────────────────────

    /**
     * The project's own declaration of itself: dependencies, tasks, versions.
     * The shallowest manifest is the project's own; deeper ones belong to
     * modules inside it and say less about the failure.
     */
    private static String readManifest(Project project, ProjectIndexService index, int budget) {
        if (budget < 300) return "";

        List<String> manifests = index.manifests();
        if (manifests.isEmpty()) return "";

        String chosen = manifests.stream()
            .min((a, b) -> {
                int depth = Integer.compare(countSlashes(a), countSlashes(b));
                return depth != 0 ? depth : Integer.compare(a.length(), b.length());
            })
            .orElse(null);
        if (chosen == null) return "";

        VirtualFile base = com.intellij.openapi.project.ProjectUtil.guessProjectDir(project);
        if (base == null) return "";
        VirtualFile file = base.findFileByRelativePath(chosen);
        if (file == null || file.getLength() > 200_000) return "";

        try {
            String text = VfsUtilCore.loadText(file);
            int room = budget - 120;
            if (text.length() > room) text = text.substring(0, room);
            if (text.isBlank()) return "";
            return "── " + chosen + " ──\n```\n" + text + "\n```";
        } catch (Exception ex) {
            return "";
        }
    }

    private static int countSlashes(String path) {
        int count = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') count++;
        }
        return count;
    }

    // ── Paths ────────────────────────────────────────────────────────────────

    private static String relative(Project project, VirtualFile file) {
        String base = project.getBasePath();
        String path = file.getPath();
        return base != null && path.startsWith(base + "/") ? path.substring(base.length() + 1) : path;
    }

    private static String indent(List<String> paths) {
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            if (out.length() > 0) out.append('\n');
            out.append("  ").append(path);
        }
        return out.toString();
    }
}
