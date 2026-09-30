package com.llmcopilot.index;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.llmcopilot.settings.LLMCopilotSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * What the whole application looks like, held in memory for the life of the
 * project window.
 *
 * <p>The per-keystroke collectors answer questions about the caret and the file
 * it sits in. Neither of them can answer "where is {@code OrderRepository}
 * declared", "who calls this" or "what is this project", and those are the
 * questions a deep answer turns on — the difference between a fix that compiles
 * and a fix that is right.
 *
 * <p>So the project's sources are read once in the background and reduced to:
 *
 * <ul>
 *   <li>a symbol table, name to the declarations of it, each carrying the line
 *       it was written on so a lookup returns something quotable;</li>
 *   <li>an import graph in both directions — forwards for what a file depends
 *       on, backwards for what depends on it;</li>
 *   <li>a digest of the project's shape, for the prompts that need orientation
 *       rather than detail.</li>
 * </ul>
 *
 * <p>Everything a caller asks for is answered from memory in microseconds, which
 * is what makes cross-file context affordable on every keystroke rather than
 * only on invoke. Callers must tolerate {@link #isReady()} being false: the
 * build runs off the EDT and nothing waits for it.
 */
@Service(Service.Level.PROJECT)
public final class ProjectIndexService {

    // ── Languages ────────────────────────────────────────────────────────────

    private static final Map<String, String> EXT_LANGUAGE = Map.ofEntries(
        Map.entry("ts", "typescript"), Map.entry("tsx", "typescriptreact"),
        Map.entry("mts", "typescript"), Map.entry("cts", "typescript"),
        Map.entry("js", "javascript"), Map.entry("jsx", "javascriptreact"),
        Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
        Map.entry("py", "python"), Map.entry("pyi", "python"),
        Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"),
        Map.entry("scala", "scala"), Map.entry("cs", "csharp"), Map.entry("rs", "rust"),
        Map.entry("go", "go"), Map.entry("rb", "ruby"), Map.entry("php", "php"),
        Map.entry("swift", "swift"), Map.entry("dart", "dart"),
        Map.entry("c", "c"), Map.entry("h", "c"), Map.entry("cpp", "cpp"),
        Map.entry("cc", "cpp"), Map.entry("cxx", "cpp"), Map.entry("hpp", "cpp")
    );

    private static final Set<String> SKIP_DIRECTORIES = Set.of(
        "node_modules", ".git", "dist", "build", "out", "target", "vendor",
        "__pycache__", ".venv", "venv", ".gradle", "bin", "obj", "coverage",
        ".next", ".idea", ".mvn");

    private static final Set<String> MANIFEST_NAMES = Set.of(
        "package.json", "pyproject.toml", "requirements.txt", "setup.py", "Pipfile",
        "go.mod", "Cargo.toml", "pom.xml", "build.gradle", "build.gradle.kts",
        "composer.json", "Gemfile", "pubspec.yaml", "Package.swift", "CMakeLists.txt");

    private static final String[] JS_EXTENSIONS = { ".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs" };

    // ── State ────────────────────────────────────────────────────────────────

    public enum State { IDLE, BUILDING, READY }

    /** Snapshot for the diagnostics action. */
    public record Status(State state, int files, int symbols, long builtInMs, boolean truncated) { }

    /** Where one declaration lives. */
    public record Hit(String path, SourceSummary.Symbol symbol) { }

    private final Project project;

    private final Map<String, SourceSummary.FileSummary> files = new ConcurrentHashMap<>();
    /** Lowercased name to declarations of it. */
    private final Map<String, List<Hit>> symbolIndex = new ConcurrentHashMap<>();
    /** Path to the paths importing it. */
    private final Map<String, Set<String>> importers = new ConcurrentHashMap<>();
    /** Lowercased basename to paths, for resolving non-relative specifiers. */
    private final Map<String, List<String>> byBasename = new ConcurrentHashMap<>();
    /** Path to the paths it imports, as far as they resolved inside the project. */
    private final Map<String, List<String>> edges = new ConcurrentHashMap<>();

    private final List<String> manifests = new ArrayList<>();
    private final AtomicBoolean building = new AtomicBoolean(false);

    private volatile State state = State.IDLE;
    private volatile long builtInMs = 0;
    private volatile boolean truncated = false;

    public ProjectIndexService(Project project) {
        this.project = project;
    }

    public static ProjectIndexService getInstance(Project project) {
        return project.getService(ProjectIndexService.class);
    }

    public boolean isReady() { return state == State.READY; }

    public Status status() {
        return new Status(state, files.size(), symbolIndex.size(), builtInMs, truncated);
    }

    // ── Building ─────────────────────────────────────────────────────────────

    /**
     * Read the project and rebuild every table. Safe to call from any thread;
     * a second call while one is running returns immediately rather than
     * doubling the work.
     */
    public void build() {
        if (!LLMCopilotSettings.getInstance().isProjectIndexEnabled()) { clear(); return; }
        if (!building.compareAndSet(false, true)) return;

        long started = System.currentTimeMillis();
        state = State.BUILDING;

        try {
            LLMCopilotSettings settings = LLMCopilotSettings.getInstance();
            int ceiling = settings.getProjectIndexMaxFiles();
            long sizeCeiling = (long) settings.getProjectIndexMaxFileSizeKb() * 1024;

            Map<String, SourceSummary.FileSummary> next = new HashMap<>();
            List<String> foundManifests = new ArrayList<>();
            boolean hitCeiling = collect(next, foundManifests, ceiling, sizeCeiling);

            files.clear();
            files.putAll(next);
            synchronized (manifests) {
                manifests.clear();
                manifests.addAll(foundManifests.stream().limit(12).toList());
            }

            reindex();
            truncated = hitCeiling;
            builtInMs = System.currentTimeMillis() - started;
            state = State.READY;
        } catch (Exception ex) {
            // An index that failed to build is simply absent; everything
            // downstream treats it as optional.
            state = files.isEmpty() ? State.IDLE : State.READY;
        } finally {
            building.set(false);
        }
    }

    /** Build off the EDT, for the startup path and the rebuild action. */
    public void buildInBackground() {
        ApplicationManager.getApplication().executeOnPooledThread(this::build);
    }

    private boolean collect(
        Map<String, SourceSummary.FileSummary> into, List<String> manifestsInto,
        int ceiling, long sizeCeiling
    ) {
        VirtualFile base = ProjectUtil.guessProjectDir(project);
        if (base == null) return false;

        String root = base.getPath();
        boolean[] hitCeiling = { false };

        // A read action is needed for the file index; the summarising itself is
        // pure string work and is deliberately done inside it too, because
        // releasing and reacquiring per file costs more than it saves.
        ApplicationManager.getApplication().runReadAction(() -> {
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);

            VfsUtilCore.visitChildrenRecursively(base, new VirtualFileVisitor<Void>() {
                @Override
                public boolean visitFile(VirtualFile file) {
                    if (into.size() >= ceiling) { hitCeiling[0] = true; return false; }

                    if (file.isDirectory()) {
                        return !SKIP_DIRECTORIES.contains(file.getName())
                            && !fileIndex.isExcluded(file);
                    }

                    if (MANIFEST_NAMES.contains(file.getName())) {
                        manifestsInto.add(relative(root, file.getPath()));
                    }

                    String language = EXT_LANGUAGE.get(
                        file.getExtension() == null ? "" : file.getExtension().toLowerCase(Locale.ROOT));
                    if (language == null) return true;
                    if (file.getLength() > sizeCeiling) return true;

                    try {
                        String text = VfsUtilCore.loadText(file);
                        String path = relative(root, file.getPath());
                        into.put(path, SourceSummary.summarise(
                            path, text, language, file.getTimeStamp(), file.getLength()));
                    } catch (Exception ignored) {
                        // Binary, unreadable, or deleted mid-walk.
                    }
                    return true;
                }
            });
        });

        return hitCeiling[0];
    }

    /** Re-read one file after it was saved, leaving the rest of the index alone. */
    public void refresh(VirtualFile file) {
        if (!isReady() || file == null || file.isDirectory()) return;

        String extension = file.getExtension();
        String language = EXT_LANGUAGE.get(extension == null ? "" : extension.toLowerCase(Locale.ROOT));
        if (language == null) return;

        VirtualFile base = ProjectUtil.guessProjectDir(project);
        if (base == null) return;
        String path = relative(base.getPath(), file.getPath());

        try {
            String text = VfsUtilCore.loadText(file);
            files.put(path, SourceSummary.summarise(
                path, text, language, file.getTimeStamp(), file.getLength()));
        } catch (Exception ex) {
            files.remove(path);
        }
        reindex();
    }

    public void clear() {
        files.clear();
        symbolIndex.clear();
        importers.clear();
        byBasename.clear();
        edges.clear();
        synchronized (manifests) { manifests.clear(); }
        state = State.IDLE;
        builtInMs = 0;
        truncated = false;
    }

    // ── Derived tables ───────────────────────────────────────────────────────

    private synchronized void reindex() {
        symbolIndex.clear();
        importers.clear();
        byBasename.clear();
        edges.clear();

        for (SourceSummary.FileSummary file : files.values()) {
            byBasename
                .computeIfAbsent(stem(file.path()), k -> new ArrayList<>())
                .add(file.path());

            for (SourceSummary.Symbol symbol : file.symbols()) {
                symbolIndex
                    .computeIfAbsent(symbol.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(new Hit(file.path(), symbol));
            }
        }

        for (SourceSummary.FileSummary file : files.values()) {
            List<String> resolved = new ArrayList<>();
            for (String specifier : file.imports()) {
                String target = resolve(file.path(), specifier, file.language());
                if (target == null || target.equals(file.path())) continue;
                resolved.add(target);
                importers.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet()).add(file.path());
            }
            if (!resolved.isEmpty()) edges.put(file.path(), resolved);
        }
    }

    /**
     * Turn a module specifier into a file this index holds. Relative paths are
     * resolved properly; everything else falls back to matching the last
     * segment against file names, which is how a Java or C# import has to be
     * read anyway.
     */
    private String resolve(String from, String specifier, String language) {
        if (specifier.startsWith(".")) {
            String base = normalise(parent(from) + "/" + specifier);
            if (files.containsKey(base)) return base;
            for (String extension : JS_EXTENSIONS) {
                if (files.containsKey(base + extension)) return base + extension;
                if (files.containsKey(base + "/index" + extension)) return base + "/index" + extension;
            }
            if (files.containsKey(base + ".py")) return base + ".py";
            if (files.containsKey(base + ".rb")) return base + ".rb";
            return null;
        }

        if ("python".equals(language)) {
            String asPath = specifier.replace('.', '/');
            for (String candidate : new String[]{ asPath + ".py", asPath + "/__init__.py" }) {
                if (files.containsKey(candidate)) return candidate;
                for (String held : files.keySet()) {
                    if (held.endsWith("/" + candidate)) return held;
                }
            }
        }

        String tail = lastSegment(specifier);
        if (tail.isEmpty()) return null;
        List<String> candidates = byBasename.get(tail.toLowerCase(Locale.ROOT));
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        String wanted = specifier.replace('.', '/').replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            String withoutExtension = candidate.replaceAll("\\.\\w+$", "").toLowerCase(Locale.ROOT);
            if (wanted.contains(withoutExtension)) return candidate;
        }
        return null;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /** Every declaration of a name, best match first. */
    public List<Hit> lookup(String name, int limit) {
        List<Hit> hits = symbolIndex.get(name.toLowerCase(Locale.ROOT));
        if (hits == null || hits.isEmpty()) return List.of();

        return hits.stream()
            .sorted(Comparator.comparingInt((Hit hit) -> score(hit, name)).reversed())
            .limit(limit)
            .toList();
    }

    private static int score(Hit hit, String name) {
        int score = 0;
        if (hit.symbol().name().equals(name)) score += 4;
        if (hit.symbol().exported()) score += 2;
        if (hit.symbol().kind() == SourceSummary.Kind.CLASS
            || hit.symbol().kind() == SourceSummary.Kind.INTERFACE) score += 1;
        return score;
    }

    /** The files that import this one — who breaks if its contract changes. */
    public List<String> dependents(String path, int limit) {
        Set<String> back = importers.get(normalise(path));
        if (back == null) return List.of();
        return back.stream().sorted().limit(limit).toList();
    }

    /** The files this one imports, as far as they resolved inside the project. */
    public List<String> dependencies(String path) {
        return edges.getOrDefault(normalise(path), List.of());
    }

    public SourceSummary.FileSummary get(String path) {
        return files.get(normalise(path));
    }

    /**
     * Files near this one in the sense that matters for completion: what it
     * imports, what imports it, one more hop out, then its siblings on disk.
     */
    public List<String> neighbourhood(String path, int limit) {
        String self = normalise(path);
        Set<String> ordered = new LinkedHashSet<>();

        ordered.addAll(dependencies(self));
        Set<String> back = importers.get(self);
        if (back != null) ordered.addAll(back);

        // One more hop, so a type reached through a barrel file is still found.
        for (String edge : List.copyOf(ordered)) {
            if (ordered.size() >= limit * 2) break;
            ordered.addAll(dependencies(edge));
        }

        String directory = parent(self);
        for (String held : files.keySet()) {
            if (ordered.size() >= limit * 2) break;
            if (parent(held).equals(directory)) ordered.add(held);
        }

        ordered.remove(self);
        return ordered.stream().filter(files::containsKey).limit(limit).toList();
    }

    /**
     * The declarations behind a set of names, rendered for a prompt. This is
     * the whole point of the index on the completion path: the model is told
     * what a call actually takes, rather than guessing.
     */
    public String declarationsFor(Iterable<String> names, int budgetChars) {
        StringBuilder out = new StringBuilder();
        Set<String> used = new HashSet<>();
        int spent = 0;

        for (String name : names) {
            if (spent >= budgetChars) break;
            for (Hit hit : lookup(name, 2)) {
                String key = hit.path() + ":" + hit.symbol().line();
                if (!used.add(key)) continue;
                String line = "//   " + hit.path() + ":" + (hit.symbol().line() + 1)
                    + "  " + hit.symbol().signature() + "\n";
                if (spent + line.length() > budgetChars) break;
                out.append(line);
                spent += line.length();
            }
        }

        return out.length() == 0
            ? ""
            : "// Declarations found elsewhere in this project:\n" + out.toString().stripTrailing();
    }

    /**
     * The cross-file block for a completion: the declarations of the names in
     * play at the caret, plus a sketch of the files this one is wired to.
     * Bounded by characters, so one large module cannot crowd out five small
     * ones.
     */
    public String completionContext(String path, Iterable<String> referenced, int budgetChars) {
        if (!isReady() || budgetChars <= 0) return "";

        List<String> sections = new ArrayList<>();
        String declarations = declarationsFor(referenced, (int) (budgetChars * 0.6));
        if (!declarations.isEmpty()) sections.add(declarations);

        StringBuilder sketch = new StringBuilder();
        int spent = declarations.length();

        for (String neighbour : neighbourhood(path, 6)) {
            SourceSummary.FileSummary file = files.get(neighbour);
            if (file == null || file.symbols().isEmpty()) continue;

            List<SourceSummary.Symbol> exported = file.symbols().stream()
                .filter(SourceSummary.Symbol::exported).limit(6).toList();
            if (exported.isEmpty()) continue;

            StringBuilder block = new StringBuilder("// ").append(neighbour).append("\n");
            for (SourceSummary.Symbol symbol : exported) {
                block.append("//   ").append(symbol.signature()).append("\n");
            }
            if (spent + block.length() > budgetChars) break;
            sketch.append(block);
            spent += block.length();
        }

        if (sketch.length() > 0) {
            sections.add("// Related files:\n" + sketch.toString().stripTrailing());
        }

        return String.join("\n\n", sections);
    }

    /** Orientation rather than detail — what kind of project this is. */
    public String renderDigest() {
        if (files.isEmpty()) return "";

        Map<String, Long> byLanguage = files.values().stream()
            .collect(Collectors.groupingBy(SourceSummary.FileSummary::language, Collectors.counting()));
        Map<String, Long> byArea = files.keySet().stream()
            .collect(Collectors.groupingBy(ProjectIndexService::topLevel, Collectors.counting()));

        List<String> lines = new ArrayList<>();
        lines.add("Project: " + files.size() + " source files indexed.");

        String languages = byLanguage.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(4)
            .map(e -> e.getKey() + " (" + e.getValue() + ")")
            .collect(Collectors.joining(", "));
        if (!languages.isEmpty()) lines.add("Languages: " + languages + ".");

        String areas = byArea.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(8)
            .map(e -> e.getKey() + "/ (" + e.getValue() + ")")
            .collect(Collectors.joining(", "));
        if (!areas.isEmpty()) lines.add("Layout: " + areas + ".");

        List<String> held = manifests();
        if (!held.isEmpty()) lines.add("Manifests: " + String.join(", ", held) + ".");

        String entryPoints = files.values().stream()
            .filter(f -> !importers.containsKey(f.path()))
            .filter(f -> dependencies(f.path()).size() >= 2)
            .sorted(Comparator.comparingInt((SourceSummary.FileSummary f) -> dependencies(f.path()).size()).reversed())
            .limit(6)
            .map(SourceSummary.FileSummary::path)
            .collect(Collectors.joining(", "));
        if (!entryPoints.isEmpty()) lines.add("Likely entry points: " + entryPoints + ".");

        return String.join("\n", lines);
    }

    public List<String> manifests() {
        synchronized (manifests) { return List.copyOf(manifests); }
    }

    // ── Paths ────────────────────────────────────────────────────────────────

    private static String relative(String root, String absolute) {
        String path = absolute.replace('\\', '/');
        String base = root.replace('\\', '/');
        return path.startsWith(base + "/") ? path.substring(base.length() + 1) : path;
    }

    /** Collapse `.` and `..` the way a module resolver would. */
    static String normalise(String path) {
        String cleaned = path.replace('\\', '/');
        List<String> stack = new ArrayList<>();
        for (String part : cleaned.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!stack.isEmpty() && !"..".equals(stack.get(stack.size() - 1))) {
                    stack.remove(stack.size() - 1);
                    continue;
                }
            }
            stack.add(part);
        }
        return String.join("/", stack);
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String topLevel(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "." : path.substring(0, slash);
    }

    private static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    private static String lastSegment(String specifier) {
        String[] pieces = specifier.split("[./\\\\:]");
        for (int i = pieces.length - 1; i >= 0; i--) {
            if (!pieces[i].isEmpty()) return pieces[i];
        }
        return "";
    }
}
