package com.llmcopilot.agent;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.llmcopilot.actions.ProblemsAtCaret;
import com.llmcopilot.chat.CustomInstructions;
import com.llmcopilot.index.ProjectIndexService;
import com.llmcopilot.review.CodeReview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The agent's hands in an IntelliJ project. Edits go through the document and
 * are saved straight away, so a command run afterwards sees them; every file
 * touched is remembered as it was first found, which is what the change list's
 * diffs and "Undo all" work from. Commands never run without the user's say-so.
 * Mirrors agentTools.ts.
 */
public final class WorkspaceTools implements AgentSession.Tools {

    public enum Decision { RUN, SKIP }

    private static final Set<String> SKIP_DIRECTORIES = Set.of(
        "node_modules", ".git", "out", "dist", "build", "target", ".venv", "venv", "__pycache__",
        ".next", "coverage", ".gradle", ".idea");
    private static final int READ_CHUNK = 400;

    private final Project project;
    private final Path root;
    private final Function<String, Decision> confirmCommand;
    private final int commandTimeoutSeconds;

    /** Relative path → contents before the first change, or null for a file the agent created. */
    private final Map<String, String> originals = new LinkedHashMap<>();

    public WorkspaceTools(Project project, Function<String, Decision> confirmCommand, int commandTimeoutSeconds) {
        this.project = project;
        this.root = Path.of(Objects.requireNonNull(project.getBasePath()));
        this.confirmCommand = confirmCommand;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    public Map<String, String> originals() { return Collections.unmodifiableMap(originals); }

    public Path root() { return root; }

    @Override
    public AgentSession.Outcome run(AgentProtocol.ToolCall call, BooleanSupplier cancelled) throws Exception {
        return switch (call.name()) {
            case "list_files"   -> listFiles(call);
            case "read_file"    -> readFile(call);
            case "search_text"  -> searchText(call);
            case "find_symbol"  -> findSymbol(call);
            case "edit_file"    -> editFile(call);
            case "create_file"  -> createFile(call);
            case "get_problems" -> getProblems(call);
            case "run_command"  -> runCommand(call, cancelled);
            default -> new AgentSession.Outcome(false, "There is no tool called " + call.name() + ".", "Unknown tool " + call.name());
        };
    }

    // ── Paths ────────────────────────────────────────────────────────────────

    private String rel(AgentProtocol.ToolCall call) {
        return AgentProtocol.safeRelativePath(AgentProtocol.stringArg(call, "path", true), root.toString());
    }

    private VirtualFile find(String rel) {
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(rel));
    }

    private String textOf(VirtualFile vf) {
        return ReadAction.compute(() -> {
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc != null) return doc.getText();
            try { return new String(vf.contentsToByteArray(), vf.getCharset()); }
            catch (IOException e) { throw new RuntimeException(e.getMessage()); }
        });
    }

    /** Every source file under the root, skipping build output and dependencies. */
    private List<Path> walk(String glob, int limit) throws IOException {
        Pattern matcher = CustomInstructions.globToPattern(glob == null || glob.isBlank() ? "**/*" : glob);
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && SKIP_DIRECTORIES.contains(dir.getFileName().toString())
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (matcher.matcher(rel).matches()) out.add(file);
                return out.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    private AgentSession.Outcome listFiles(AgentProtocol.ToolCall call) throws IOException {
        String pattern = AgentProtocol.stringArg(call, "pattern", false);
        if (pattern == null || pattern.isBlank()) pattern = "**/*";
        List<Path> files = walk(pattern, 300);
        List<String> paths = files.stream().map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        String more = files.size() >= 300 ? "\n… (more files; narrow the pattern)" : "";
        return new AgentSession.Outcome(true, (paths.isEmpty() ? "No files match." : String.join("\n", paths)) + more,
            "Listed " + paths.size() + " files matching " + pattern);
    }

    private AgentSession.Outcome readFile(AgentProtocol.ToolCall call) {
        String rel = rel(call);
        VirtualFile vf = find(rel);
        if (vf == null || vf.isDirectory()) return new AgentSession.Outcome(false, rel + " does not exist.", "Read " + rel + ": no such file");
        String[] lines = textOf(vf).split("\n", -1);
        int total = lines.length;
        Integer from = AgentProtocol.intArg(call, "start_line");
        Integer to = AgentProtocol.intArg(call, "end_line");
        int start = Math.max(1, from != null ? from : 1);
        int end = Math.min(total, Math.min(to != null ? to : start + READ_CHUNK - 1, start + READ_CHUNK - 1));
        if (start > total) return new AgentSession.Outcome(false, rel + " has only " + total + " lines.", "Read " + rel + ": past the end");
        String text = String.join("\n", Arrays.copyOfRange(lines, start - 1, end));
        String rest = end < total ? "\n… (" + (total - end) + " more lines; read with start_line " + (end + 1) + ")" : "";
        return new AgentSession.Outcome(true,
            rel + " (lines " + start + "-" + end + " of " + total + ")\n" + CodeReview.numberLines(text, start) + rest,
            "Read " + rel + (start > 1 || end < total ? " lines " + start + "–" + end : ""));
    }

    private AgentSession.Outcome searchText(AgentProtocol.ToolCall call) throws IOException {
        String query = AgentProtocol.stringArg(call, "query", true);
        String regex = AgentProtocol.stringArg(call, "regex", false);
        boolean isRegex = "true".equalsIgnoreCase(regex);
        Pattern pattern;
        try {
            pattern = Pattern.compile(isRegex ? query : Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        } catch (RuntimeException e) {
            return new AgentSession.Outcome(false, "Bad regular expression: " + e.getMessage(), "Search failed");
        }

        List<String> hits = new ArrayList<>();
        for (Path file : walk(AgentProtocol.stringArg(call, "include", false), 3000)) {
            if (hits.size() >= 60) break;
            byte[] bytes;
            try {
                if (Files.size(file) > 512 * 1024) continue;
                bytes = Files.readAllBytes(file);
            } catch (IOException e) { continue; }
            boolean binary = false;
            for (byte b : bytes) if (b == 0) { binary = true; break; }
            if (binary) continue;
            String rel = root.relativize(file).toString().replace('\\', '/');
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length && hits.size() < 60; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    String line = lines[i].trim();
                    hits.add(rel + ":" + (i + 1) + ": " + (line.length() > 200 ? line.substring(0, 200) : line));
                }
            }
        }
        String more = hits.size() >= 60 ? "\n… (more matches; narrow the query or the include glob)" : "";
        return new AgentSession.Outcome(true, (hits.isEmpty() ? "No matches." : String.join("\n", hits)) + more,
            "Searched for \"" + query + "\": " + hits.size() + " match" + (hits.size() == 1 ? "" : "es"));
    }

    private AgentSession.Outcome findSymbol(AgentProtocol.ToolCall call) {
        String name = AgentProtocol.stringArg(call, "name", true);
        ProjectIndexService index = ProjectIndexService.getInstance(project);
        List<String> lines = new ArrayList<>();
        if (index.isReady()) {
            for (ProjectIndexService.Hit hit : index.lookup(name, 10)) {
                lines.add(hit.path() + ":" + (hit.symbol().line() + 1) + ": " + hit.symbol().signature());
            }
        }
        return new AgentSession.Outcome(true, lines.isEmpty() ? "No declaration of " + name + " found." : String.join("\n", lines),
            "Looked up " + name + ": " + lines.size() + " found");
    }

    private AgentSession.Outcome getProblems(AgentProtocol.ToolCall call) throws InterruptedException {
        String raw = AgentProtocol.stringArg(call, "path", false);
        List<String> targets = raw != null ? List.of(AgentProtocol.safeRelativePath(raw, root.toString())) : new ArrayList<>(originals.keySet());
        if (targets.isEmpty()) return new AgentSession.Outcome(true, "No files have been changed yet; name a path.", "Checked problems: nothing changed yet");

        // The IDE analyses only files open in an editor, and needs a moment once one is.
        List<VirtualFile> files = new ArrayList<>();
        for (String rel : targets) { VirtualFile vf = find(rel); if (vf != null) files.add(vf); }
        ApplicationManager.getApplication().invokeAndWait(() -> {
            for (VirtualFile vf : files) FileEditorManager.getInstance(project).openFile(vf, false);
        });
        Thread.sleep(2000);

        List<String> lines = new ArrayList<>();
        int errors = 0;
        for (VirtualFile vf : files) {
            Document doc = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
            if (doc == null) continue;
            AtomicReference<List<ProblemsAtCaret.Problem>> found = new AtomicReference<>(List.of());
            ApplicationManager.getApplication().invokeAndWait(() -> found.set(ProblemsAtCaret.inFile(project, doc)));
            String rel = root.relativize(vf.toNioPath()).toString().replace('\\', '/');
            for (ProblemsAtCaret.Problem p : found.get()) {
                if (p.severity().equals("error")) errors++;
                lines.add(rel + ":" + (p.line() + 1) + ": " + p.severity() + ": " + p.description());
            }
        }
        int warnings = lines.size() - errors;
        return new AgentSession.Outcome(true, lines.isEmpty() ? "No errors or warnings reported." : String.join("\n", lines),
            "Checked problems" + (raw != null ? " in " + targets.get(0) : "") + ": " + errors + " error" + (errors == 1 ? "" : "s")
                + ", " + warnings + " warning" + (warnings == 1 ? "" : "s"));
    }

    // ── Changing ─────────────────────────────────────────────────────────────

    private AgentSession.Outcome editFile(AgentProtocol.ToolCall call) {
        String rel = rel(call);
        String search = AgentProtocol.stringArg(call, "search", true);
        String replace = AgentProtocol.stringArg(call, "replace", true);
        VirtualFile vf = find(rel);
        if (vf == null || vf.isDirectory()) {
            return new AgentSession.Outcome(false, rel + " does not exist; use create_file for a new file.", "Edit " + rel + ": no such file");
        }
        Document doc = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
        if (doc == null) return new AgentSession.Outcome(false, rel + " is not a text file.", "Edit " + rel + " failed");

        String text = ReadAction.compute(doc::getText);
        AgentProtocol.Match match = AgentProtocol.findUnique(text, search);
        if (match.error() != null) {
            return new AgentSession.Outcome(false, "Could not edit " + rel + ": " + match.error() + ".", "Edit " + rel + " failed: " + match.error());
        }

        originals.putIfAbsent(rel, text);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WriteCommandAction.runWriteCommandAction(project, "LLM Copilot Agent: Edit " + rel, null,
                () -> doc.replaceString(match.start(), match.end(), replace));
            FileDocumentManager.getInstance().saveDocument(doc);
        });

        int removed = text.substring(match.start(), match.end()).split("\n", -1).length;
        int added = replace.split("\n", -1).length;
        return new AgentSession.Outcome(true, "Edited " + rel + ".", "Edited " + rel + " (+" + added + " −" + removed + ")");
    }

    private AgentSession.Outcome createFile(AgentProtocol.ToolCall call) throws IOException {
        String rel = rel(call);
        String content = AgentProtocol.stringArg(call, "content", true);
        Path path = root.resolve(rel);
        boolean existed = Files.exists(path);
        if (!originals.containsKey(rel)) originals.put(rel, existed ? Files.readString(path, StandardCharsets.UTF_8) : null);
        write(path, content);
        int lines = content.split("\n", -1).length;
        return new AgentSession.Outcome(true, (existed ? "Overwrote " : "Created ") + rel + ".",
            (existed ? "Rewrote " : "Created ") + rel + " (" + lines + " lines)");
    }

    /** Writes through the open document when there is one, so the editor and the disk agree. */
    private void write(Path path, String content) throws IOException {
        VirtualFile existing = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        Document doc = existing == null ? null : ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(existing));
        if (doc != null) {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                WriteCommandAction.runWriteCommandAction(project, "LLM Copilot Agent: Write", null, () -> doc.setText(content));
                FileDocumentManager.getInstance().saveDocument(doc);
            });
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
    }

    private AgentSession.Outcome runCommand(AgentProtocol.ToolCall call, BooleanSupplier cancelled) throws Exception {
        String command = AgentProtocol.stringArg(call, "command", true);
        if (confirmCommand.apply(command) != Decision.RUN) {
            return new AgentSession.Outcome(false,
                "The user chose not to run this command. Carry on without it, or finish and explain what is left.", "Skipped: " + command);
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb = windows ? new ProcessBuilder("cmd.exe", "/c", command) : new ProcessBuilder("/bin/sh", "-c", command);
        pb.directory(root.toFile()).redirectErrorStream(true);
        pb.environment().put("CI", "1");
        Process proc = pb.start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try { output.append(new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8)); }
            catch (IOException ignored) { }
        }, "llm-agent-command");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + commandTimeoutSeconds * 1000L;
        boolean timedOut = false;
        while (!proc.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (cancelled.getAsBoolean() || System.currentTimeMillis() > deadline) {
                timedOut = !cancelled.getAsBoolean();
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                break;
            }
        }
        reader.join(2000);
        int code = proc.isAlive() ? -1 : proc.exitValue();
        String text = output.length() == 0 ? "(no output)" : output.toString();
        return new AgentSession.Outcome(code == 0,
            (timedOut ? "Stopped after " + commandTimeoutSeconds + "s.\n" : "") + "Exit code " + code + "\n" + text,
            "Ran " + command + " — " + (timedOut ? "timed out" : "exit " + code));
    }

    /** Put every touched file back the way the agent found it. Returns how many were restored. */
    public int undoAll() {
        int restored = 0;
        for (Map.Entry<String, String> e : originals.entrySet()) {
            Path path = root.resolve(e.getKey());
            try {
                if (e.getValue() == null) {
                    VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                    if (vf != null) {
                        ApplicationManager.getApplication().invokeAndWait(() ->
                            WriteCommandAction.runWriteCommandAction(project, "LLM Copilot Agent: Undo", null, () -> {
                                try { vf.delete(this); } catch (IOException ignored) { }
                            }));
                    } else {
                        Files.deleteIfExists(path);
                    }
                } else {
                    write(path, e.getValue());
                }
                restored++;
            } catch (IOException ignored) { }
        }
        originals.clear();
        return restored;
    }
}
