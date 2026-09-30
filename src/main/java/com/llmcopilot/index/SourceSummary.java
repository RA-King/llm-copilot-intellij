package com.llmcopilot.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces one source file to its declarations and its imports.
 *
 * <p>Regex rather than PSI, deliberately. The index has to cope with a dozen
 * languages — including ones whose plugins may not be installed, which is most
 * of them in IntelliJ IDEA Community — and with files that do not currently
 * parse. It only ever needs the declaration line, never the body, and anything
 * subtler is the language's own resolver's job, which
 * {@code PsiCodeContextCollector} already asks for the file being edited.
 *
 * <p>Pure and free of IDE types, so it is testable without a running IDE.
 * Mirrors {@code summariseSource} in {@code projectIndex.ts}.
 */
public final class SourceSummary {

    private SourceSummary() { }

    // ── Shape ────────────────────────────────────────────────────────────────

    public enum Kind { CLASS, INTERFACE, STRUCT, TRAIT, ENUM, FUNCTION, METHOD, TYPE, CONST }

    /** One declaration, with the line it was written on so it can be quoted. */
    public record Symbol(String name, Kind kind, int line, String signature, boolean exported) { }

    /** Everything the index keeps about one file. */
    public record FileSummary(
        String path,
        String language,
        long modified,
        long size,
        List<Symbol> symbols,
        /** Module specifiers exactly as written. */
        List<String> imports,
        /** Names those imports bring into scope. */
        List<String> importedNames
    ) {
        public static FileSummary empty(String path, String language) {
            return new FileSummary(path, language, 0, 0, List.of(), List.of(), List.of());
        }
    }

    // ── Limits ───────────────────────────────────────────────────────────────

    /** Every language this handles puts its imports at the top. */
    private static final int IMPORT_WINDOW = 120;
    private static final int MAX_SYMBOLS   = 240;
    /** A line this long is minified or generated; parsing it teaches nothing. */
    private static final int MAX_LINE      = 400;

    // ── Declaration patterns ─────────────────────────────────────────────────

    private record Decl(Pattern pattern, Kind kind, int group) { }

    private static Decl d(String regex, Kind kind, int group) {
        return new Decl(Pattern.compile(regex), kind, group);
    }

    private static final List<Decl> TS = List.of(
        d("^\\s*(?:export\\s+(?:default\\s+)?)?(?:abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)", Kind.CLASS, 1),
        d("^\\s*(?:export\\s+)?interface\\s+([A-Za-z_$][\\w$]*)", Kind.INTERFACE, 1),
        d("^\\s*(?:export\\s+)?(?:declare\\s+)?enum\\s+([A-Za-z_$][\\w$]*)", Kind.ENUM, 1),
        d("^\\s*(?:export\\s+)?type\\s+([A-Za-z_$][\\w$]*)", Kind.TYPE, 1),
        d("^\\s*(?:export\\s+(?:default\\s+)?)?(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)", Kind.FUNCTION, 1),
        d("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>", Kind.FUNCTION, 1),
        d("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)", Kind.CONST, 1)
    );

    private static final List<Decl> JAVA = List.of(
        d("^\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?(?:final\\s+)?(?:abstract\\s+)?(?:sealed\\s+)?(?:class|interface|enum|record)\\s+([A-Za-z_]\\w*)", Kind.CLASS, 1),
        d("^\\s*(?:public|protected|private)\\s+(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?(?:<[^>]+>\\s*)?[\\w<>\\[\\],.?\\s]+\\s+([A-Za-z_]\\w*)\\s*\\(", Kind.METHOD, 1)
    );

    private static final List<Decl> KOTLIN = List.of(
        d("^\\s*(?:open\\s+|abstract\\s+|sealed\\s+|data\\s+|private\\s+|internal\\s+)*(?:class|interface|object)\\s+([A-Za-z_]\\w*)", Kind.CLASS, 1),
        d("^\\s*(?:private\\s+|internal\\s+|public\\s+|override\\s+|suspend\\s+)*fun\\s+(?:<[^>]+>\\s*)?([A-Za-z_]\\w*)", Kind.FUNCTION, 1)
    );

    private static final List<Decl> PYTHON = List.of(
        d("^\\s*class\\s+([A-Za-z_]\\w*)", Kind.CLASS, 1),
        d("^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)", Kind.FUNCTION, 1),
        d("^([A-Z_][A-Z0-9_]*)\\s*(?::[^=]+)?=", Kind.CONST, 1)
    );

    private static final List<Decl> CSHARP = List.of(
        d("^\\s*(?:public|internal|protected|private)?\\s*(?:static\\s+|abstract\\s+|sealed\\s+|partial\\s+)*(?:class|interface|struct|enum|record)\\s+([A-Za-z_]\\w*)", Kind.CLASS, 1),
        d("^\\s*(?:public|internal|protected|private)\\s+(?:static\\s+|virtual\\s+|override\\s+|async\\s+)*[\\w<>\\[\\]?,.\\s]+\\s+([A-Za-z_]\\w*)\\s*\\(", Kind.METHOD, 1)
    );

    private static final List<Decl> RUST = List.of(
        d("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?struct\\s+([A-Za-z_]\\w*)", Kind.STRUCT, 1),
        d("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?enum\\s+([A-Za-z_]\\w*)", Kind.ENUM, 1),
        d("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?trait\\s+([A-Za-z_]\\w*)", Kind.TRAIT, 1),
        d("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?type\\s+([A-Za-z_]\\w*)", Kind.TYPE, 1),
        d("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?fn\\s+([A-Za-z_]\\w*)", Kind.FUNCTION, 1)
    );

    private static final List<Decl> GO = List.of(
        d("^\\s*type\\s+([A-Za-z_]\\w*)\\s+(?:struct|interface)", Kind.STRUCT, 1),
        d("^\\s*type\\s+([A-Za-z_]\\w*)\\s+", Kind.TYPE, 1),
        d("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)\\s*\\(", Kind.FUNCTION, 1)
    );

    private static final List<Decl> RUBY = List.of(
        d("^\\s*class\\s+([A-Z]\\w*)", Kind.CLASS, 1),
        d("^\\s*module\\s+([A-Z]\\w*)", Kind.CLASS, 1),
        d("^\\s*def\\s+(?:self\\.)?([a-z_]\\w*[?!]?)", Kind.FUNCTION, 1)
    );

    private static final List<Decl> PHP = List.of(
        d("^\\s*(?:abstract\\s+|final\\s+)?class\\s+([A-Za-z_]\\w*)", Kind.CLASS, 1),
        d("^\\s*interface\\s+([A-Za-z_]\\w*)", Kind.INTERFACE, 1),
        d("^\\s*(?:public\\s+|private\\s+|protected\\s+|static\\s+)*function\\s+([A-Za-z_]\\w*)", Kind.FUNCTION, 1)
    );

    private static final List<Decl> SWIFT = List.of(
        d("^\\s*(?:public\\s+|internal\\s+|private\\s+|open\\s+)?(?:final\\s+)?(?:class|struct|enum|protocol|actor)\\s+([A-Za-z_]\\w*)", Kind.CLASS, 1),
        d("^\\s*(?:public\\s+|private\\s+|internal\\s+|static\\s+|override\\s+)*func\\s+([A-Za-z_]\\w*)", Kind.FUNCTION, 1)
    );

    private static final List<Decl> CPP = List.of(
        d("^\\s*(?:class|struct)\\s+([A-Za-z_]\\w*)", Kind.CLASS, 1),
        d("^\\s*(?:[\\w:<>*&\\s]+\\s+)?([A-Za-z_]\\w*)::([A-Za-z_]\\w*)\\s*\\(", Kind.METHOD, 2),
        d("^[A-Za-z_][\\w:<>*&\\s]*\\s+([A-Za-z_]\\w*)\\s*\\([^;]*\\)\\s*\\{?\\s*$", Kind.FUNCTION, 1)
    );

    private static final Map<String, List<Decl>> BY_LANGUAGE = Map.ofEntries(
        Map.entry("typescript", TS), Map.entry("typescriptreact", TS),
        Map.entry("javascript", TS), Map.entry("javascriptreact", TS),
        Map.entry("java", JAVA), Map.entry("kotlin", KOTLIN), Map.entry("scala", KOTLIN),
        Map.entry("python", PYTHON), Map.entry("csharp", CSHARP),
        Map.entry("rust", RUST), Map.entry("go", GO), Map.entry("ruby", RUBY),
        Map.entry("php", PHP), Map.entry("swift", SWIFT), Map.entry("dart", SWIFT),
        Map.entry("cpp", CPP), Map.entry("c", CPP)
    );

    /** Control-flow words the loose patterns would otherwise read as declarations. */
    private static final Set<String> RESERVED = Set.of(
        "if", "for", "while", "switch", "catch", "return", "else", "do", "try",
        "match", "when", "with", "case", "new", "delete", "typeof", "await");

    // ── Import patterns ──────────────────────────────────────────────────────

    private static final Pattern ES_IMPORT   = Pattern.compile("^\\s*import\\s+(?:(.+?)\\s+from\\s+)?['\"]([^'\"]+)['\"]");
    private static final Pattern CJS_REQUIRE = Pattern.compile("^\\s*(?:const|let|var)\\s+(.+?)\\s*=\\s*require\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern PY_FROM     = Pattern.compile("^\\s*from\\s+([\\w.]+)\\s+import\\s+(.+)$");
    private static final Pattern PY_IMPORT   = Pattern.compile("^\\s*import\\s+([\\w.]+)");
    private static final Pattern JVM_IMPORT  = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)");
    private static final Pattern GO_IMPORT   = Pattern.compile("^\\s*(?:\\w+\\s+)?\"([^\"]+)\"\\s*$");
    private static final Pattern RUST_USE    = Pattern.compile("^\\s*(?:pub\\s+)?use\\s+((?:\\w+::)*\\w+)(?:::\\{([^}]*)\\})?");
    private static final Pattern CS_USING    = Pattern.compile("^\\s*using\\s+(?:static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern PHP_USE     = Pattern.compile("^\\s*use\\s+([\\w\\\\]+)");
    private static final Pattern RB_REQUIRE  = Pattern.compile("^\\s*require(?:_relative)?\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern C_INCLUDE   = Pattern.compile("^\\s*#include\\s+[<\"]([^>\"]+)[>\"]");
    private static final Pattern BRACED      = Pattern.compile("\\{([^}]*)\\}");
    private static final Pattern IDENTIFIER  = Pattern.compile("^[A-Za-z_$][\\w$]*$");

    private record ImportHit(String specifier, List<String> names) { }

    // ── Reading a file ───────────────────────────────────────────────────────

    public static FileSummary summarise(
        String path, String text, String language, long modified, long size
    ) {
        List<Decl> patterns = BY_LANGUAGE.getOrDefault(language, List.of());
        String[] lines = text.split("\n", -1);

        List<Symbol> symbols = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        Set<String> importedNames = new LinkedHashSet<>();

        int importWindow = Math.min(lines.length, IMPORT_WINDOW);
        for (int i = 0; i < importWindow; i++) {
            ImportHit hit = readImport(lines[i], language);
            if (hit == null) continue;
            imports.add(hit.specifier());
            for (String name : hit.names()) {
                if (!name.isBlank()) importedNames.add(name);
            }
        }

        for (int i = 0; i < lines.length && symbols.size() < MAX_SYMBOLS; i++) {
            String line = lines[i];
            if (line.length() > MAX_LINE) continue;

            for (Decl decl : patterns) {
                Matcher m = decl.pattern().matcher(line);
                if (!m.find()) continue;

                String name = m.group(decl.group());
                if (name == null || name.isEmpty() || RESERVED.contains(name)) break;

                String signature = line.trim();
                if (signature.length() > 200) signature = signature.substring(0, 200);

                boolean exported = line.contains("export") || line.contains("pub ")
                    || line.contains("public") || "python".equals(language) || "go".equals(language);

                symbols.add(new Symbol(name, decl.kind(), i, signature, exported));
                break;
            }
        }

        return new FileSummary(path, language, modified, size,
            List.copyOf(symbols), List.copyOf(imports), List.copyOf(importedNames));
    }

    private static ImportHit readImport(String line, String language) {
        switch (language) {
            case "typescript", "typescriptreact", "javascript", "javascriptreact" -> {
                Matcher es = ES_IMPORT.matcher(line);
                if (es.find()) {
                    return new ImportHit(es.group(2), bindingNames(es.group(1) == null ? "" : es.group(1)));
                }
                Matcher cjs = CJS_REQUIRE.matcher(line);
                if (cjs.find()) return new ImportHit(cjs.group(2), bindingNames(cjs.group(1)));
                return null;
            }
            case "python" -> {
                Matcher from = PY_FROM.matcher(line);
                if (from.find()) {
                    List<String> names = new ArrayList<>();
                    for (String part : from.group(2).split(",")) {
                        String name = part.trim().split("\\s+as\\s+")[0].trim();
                        if (!name.isEmpty()) names.add(name);
                    }
                    return new ImportHit(from.group(1), names);
                }
                Matcher plain = PY_IMPORT.matcher(line);
                if (plain.find()) return new ImportHit(plain.group(1), List.of(lastSegment(plain.group(1), "\\.")));
                return null;
            }
            case "java", "kotlin", "scala" -> {
                Matcher m = JVM_IMPORT.matcher(line);
                if (!m.find()) return null;
                String tail = lastSegment(m.group(1), "\\.");
                return new ImportHit(m.group(1), "*".equals(tail) ? List.of() : List.of(tail));
            }
            case "go" -> {
                Matcher m = GO_IMPORT.matcher(line);
                return m.find() ? new ImportHit(m.group(1), List.of(lastSegment(m.group(1), "/"))) : null;
            }
            case "rust" -> {
                Matcher m = RUST_USE.matcher(line);
                if (!m.find()) return null;
                if (m.group(2) == null) {
                    return new ImportHit(m.group(1), List.of(lastSegment(m.group(1), "::")));
                }
                List<String> names = new ArrayList<>();
                for (String part : m.group(2).split(",")) {
                    String name = part.trim();
                    if (!name.isEmpty()) names.add(name);
                }
                return new ImportHit(m.group(1), names);
            }
            case "csharp" -> {
                Matcher m = CS_USING.matcher(line);
                return m.find() ? new ImportHit(m.group(1), List.of(lastSegment(m.group(1), "\\."))) : null;
            }
            case "php" -> {
                Matcher m = PHP_USE.matcher(line);
                return m.find() ? new ImportHit(m.group(1), List.of(lastSegment(m.group(1), "\\\\"))) : null;
            }
            case "ruby" -> {
                Matcher m = RB_REQUIRE.matcher(line);
                return m.find() ? new ImportHit(m.group(1), List.of()) : null;
            }
            case "c", "cpp" -> {
                Matcher m = C_INCLUDE.matcher(line);
                return m.find() ? new ImportHit(m.group(1), List.of()) : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code { a, b as c }}, {@code Foo}, {@code * as ns} — what lands in scope. */
    private static List<String> bindingNames(String clause) {
        List<String> names = new ArrayList<>();

        Matcher braced = BRACED.matcher(clause);
        if (braced.find()) {
            for (String part : braced.group(1).split(",")) {
                String name = lastAlias(part);
                if (!name.isEmpty()) names.add(name);
            }
        }

        String bare = clause.replaceAll("\\{[^}]*\\}", "").replaceAll("\\*\\s+as\\s+", "");
        for (String part : bare.split(",")) {
            String name = lastAlias(part);
            if (!name.isEmpty() && IDENTIFIER.matcher(name).matches()) names.add(name);
        }

        return names;
    }

    private static String lastAlias(String part) {
        String[] pieces = part.trim().split("\\s+as\\s+");
        return pieces[pieces.length - 1].trim();
    }

    private static String lastSegment(String text, String separator) {
        String[] pieces = text.split(separator);
        return pieces.length == 0 ? text : pieces[pieces.length - 1];
    }
}
