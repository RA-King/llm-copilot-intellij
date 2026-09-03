package com.llmcopilot.completion;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One place where every language-specific shape the completion pipeline needs to
 * recognise is written down: how a loop names the thing it iterates, how a local is
 * declared, what an "empty" initialiser looks like, which types mean "returns nothing",
 * and which words open a block without declaring anything.
 *
 * <p>The pattern lists are ordered and global rather than partitioned per language. The
 * syntaxes are distinct enough that {@code for _, x := range xs} cannot be mistaken for
 * {@code foreach ($xs as $x)}, and one ordered list is far easier to keep correct than
 * sixteen near-duplicate tables. Only the handful of facts that genuinely vary by
 * language — comment markers, block style, void spellings — are keyed by language id.
 */
public final class LanguageProfile {

    private LanguageProfile() {}

    // ── Language identity ──

    /** How a language delimits a block, which decides what "opens" one. */
    public enum BlockStyle { BRACE, INDENT, END }

    private static final Set<String> INDENT_LANGS = Set.of("python", "yaml", "coffeescript", "nim");
    private static final Set<String> END_LANGS    = Set.of("ruby", "lua", "elixir", "crystal");

    public static BlockStyle blockStyleFor(String language) {
        if (language == null) return BlockStyle.BRACE;
        if (INDENT_LANGS.contains(language)) return BlockStyle.INDENT;
        if (END_LANGS.contains(language))    return BlockStyle.END;
        return BlockStyle.BRACE;
    }

    /** Line-comment markers, longest first so {@code ///} is stripped before {@code //}. */
    public static List<String> lineCommentsFor(String language) {
        if (language == null) return List.of("//");
        return switch (language) {
            case "python", "ruby", "perl", "r", "shellscript", "yaml", "elixir", "crystal", "nim" -> List.of("#");
            case "lua", "sql", "haskell" -> List.of("--");
            case "rust" -> List.of("///", "//!", "//");
            case "php"  -> List.of("//", "#");
            default     -> List.of("//");
        };
    }

    /**
     * Words that open a block but declare nothing. Without this a scope scan reads
     * {@code foreach ($users as $user) &#123;} as a declaration named {@code foreach}, and
     * every signature downstream is wrong.
     */
    private static final Set<String> CONTROL_KEYWORDS = Set.of(
        "if", "elif", "elsif", "else", "unless", "for", "foreach", "while", "do", "loop",
        "switch", "match", "when", "case", "select", "try", "begin", "catch", "except",
        "rescue", "finally", "ensure", "with", "using", "guard", "defer", "go", "return",
        "yield", "throw", "raise", "await", "in", "of", "repeat", "until");

    public static boolean isControlKeyword(String word) {
        return word != null && CONTROL_KEYWORDS.contains(word);
    }

    private static final Pattern LEADING_WORD = Pattern.compile("^([A-Za-z_]\\w*)");

    /**
     * True when a line is a control-flow header rather than a declaration, allowing for a
     * closing brace sharing the line ({@code &#125; else if (x) &#123;}).
     */
    public static boolean isControlLine(String trimmed) {
        if (trimmed == null) return false;
        Matcher m = LEADING_WORD.matcher(trimmed.replaceFirst("^\\}\\s*", ""));
        return m.find() && isControlKeyword(m.group(1));
    }

    // ── Loops ──

    /** The per-item variable and the collection a loop header names. */
    public record LoopMatch(String binding, String iterable) {}

    private record LoopForm(Pattern re, int binding, int iterable) {}

    /**
     * Ordered by specificity. Go's two-value range has to be tried before its one-value
     * form, and both before the generic {@code for x in xs}.
     */
    private static final List<LoopForm> LOOP_FORMS = List.of(
        // Go: for i, user := range users        for user := range users
        new LoopForm(Pattern.compile("^for\\s+[\\w_]+\\s*,\\s*([A-Za-z_]\\w*)\\s*:=\\s*range\\s+([\\w.$]+)"), 1, 2),
        new LoopForm(Pattern.compile("^for\\s+([A-Za-z_]\\w*)\\s*:=\\s*range\\s+([\\w.$]+)"), 1, 2),
        // C#: foreach (var user in users)
        new LoopForm(Pattern.compile("^foreach\\s*\\(\\s*(?:[\\w<>\\[\\],.?]+\\s+)?([A-Za-z_$]\\w*)\\s+in\\s+([\\w.$]+(?:\\([^)]*\\))?)"), 1, 2),
        // PHP: foreach ($users as $key => $user)    foreach ($users as $user)
        new LoopForm(Pattern.compile("^foreach\\s*\\(\\s*([\\w$.>\\-]+)\\s+as\\s+\\$\\w+\\s*=>\\s*(\\$\\w+)"), 2, 1),
        new LoopForm(Pattern.compile("^foreach\\s*\\(\\s*([\\w$.>\\-]+)\\s+as\\s+(\\$\\w+)"), 2, 1),
        // JS/TS: for (const user of users)       for (const k in obj)
        new LoopForm(Pattern.compile("^for\\s*(?:await\\s*)?\\(\\s*(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s+(?:of|in)\\s+([\\w.$]+(?:\\([^)]*\\))?)"), 1, 2),
        // Java / C++: for (User user : users)    for (const auto& user : users)
        new LoopForm(Pattern.compile("^for\\s*\\(\\s*(?:const\\s+)?(?:[\\w:<>\\[\\],.*&\\s]+?)[\\s&*]+([A-Za-z_$]\\w*)\\s*:\\s*([\\w.$]+(?:\\([^)]*\\))?)"), 1, 2),
        // Scala: for (user <- users)
        new LoopForm(Pattern.compile("^for\\s*[({]\\s*([A-Za-z_$]\\w*)\\s*<-\\s*([\\w.$]+(?:\\([^)]*\\))?)"), 1, 2),
        // Python / Rust / Swift / Kotlin: for user in users
        new LoopForm(Pattern.compile("^for\\s+\\(?\\s*([A-Za-z_$][\\w$]*)\\s+in\\s+([\\w.$&]+(?:\\([^)]*\\))?)"), 1, 2),
        // Ruby / JS block form: users.each do |user|    users.forEach(user => …)
        new LoopForm(Pattern.compile("^([\\w.$@]+)\\s*\\.\\s*(?:each|each_with_index|map|forEach)\\s*(?:do|\\{)\\s*\\|\\s*([A-Za-z_$]\\w*)"), 2, 1),
        new LoopForm(Pattern.compile("^([\\w.$]+)\\s*\\.\\s*(?:forEach|map|filter)\\s*\\(\\s*\\(?\\s*([A-Za-z_$][\\w$]*)"), 2, 1),
        // C-style counter: for (int i = 0; i < n; i++)
        new LoopForm(Pattern.compile("^for\\s*\\(\\s*(?:const|let|var|int|size_t|usize|auto)?\\s*([A-Za-z_$][\\w$]*)\\s*="), 1, 0));

    /** Reads the per-item variable and the collection out of a loop header. */
    public static LoopMatch matchLoop(String trimmed) {
        if (trimmed == null) return null;
        for (LoopForm form : LOOP_FORMS) {
            Matcher m = form.re().matcher(trimmed);
            if (!m.find()) continue;
            return new LoopMatch(groupOr(m, form.binding()), groupOr(m, form.iterable()));
        }
        return null;
    }

    private static String groupOr(Matcher m, int group) {
        if (group <= 0 || group > m.groupCount()) return "";
        String g = m.group(group);
        return g == null ? "" : g;
    }

    // ── Local declarations ──

    /** A local declaration: its name, the type stated for it, and its initialiser. */
    public record LocalMatch(String name, String type, String init) {}

    private record LocalForm(Pattern re, int name, int type, int init) {}

    /**
     * Ordered most specific first. The bare {@code x = expr} form is last and deliberately
     * narrow: it fires only for a plain identifier, so it catches Python, Ruby and PHP
     * locals without swallowing comparisons or member assignments.
     */
    private static final List<LocalForm> LOCAL_FORMS = List.of(
        // Rust: let mut names: Vec<String> = Vec::new();
        new LocalForm(Pattern.compile("^let\\s+mut\\s+([A-Za-z_]\\w*)\\s*(?::\\s*([^=]+?))?\\s*=\\s*(.+?);?$"), 1, 2, 3),
        // JS/TS/Swift/Kotlin/Scala: const names: string[] = []
        new LocalForm(Pattern.compile("^(?:const|let|var|final|val|auto)\\s+([A-Za-z_$][\\w$]*)\\s*(?::\\s*([^=]+?))?\\s*=\\s*(.+?);?$"), 1, 2, 3),
        // Go: names := []string{}
        new LocalForm(Pattern.compile("^([A-Za-z_]\\w*)\\s*:=\\s*(.+?)$"), 1, 0, 2),
        // Go: var names []string
        new LocalForm(Pattern.compile("^var\\s+([A-Za-z_]\\w*)\\s+([\\w\\[\\]{}*.]+)\\s*$"), 1, 2, 0),
        // PHP: $names = [];
        new LocalForm(Pattern.compile("^(\\$\\w+)\\s*=\\s*(.+?);?$"), 1, 0, 2),
        // Type-first with initialiser: List<String> names = new ArrayList<>();
        new LocalForm(Pattern.compile("^(?:final\\s+)?([A-Z][\\w:<>\\[\\],.]*(?:\\s*[*&])?)\\s+([A-Za-z_$]\\w*)\\s*=\\s*(.+?);?$"), 2, 1, 3),
        // Type-first, no initialiser: std::vector<std::string> names;
        new LocalForm(Pattern.compile("^(?:const\\s+)?([a-z_]\\w*(?:::[\\w<>:,\\s]+)+|[A-Z][\\w<>\\[\\],.]*)\\s+([A-Za-z_$]\\w*)\\s*;$"), 2, 1, 0),
        // Python / Ruby: names = []
        new LocalForm(Pattern.compile("^([a-z_]\\w*)\\s*(?::\\s*([^=]+?))?\\s*=(?!=)\\s*(.+?)$"), 1, 2, 3));

    /** Reads a local declaration off one trimmed, literal-stripped line. */
    public static LocalMatch matchLocal(String trimmed) {
        if (trimmed == null || isControlLine(trimmed)) return null;
        for (LocalForm form : LOCAL_FORMS) {
            Matcher m = form.re().matcher(trimmed);
            if (!m.find()) continue;
            String name = groupOr(m, form.name());
            if (name.isEmpty() || isControlKeyword(name)) continue;
            return new LocalMatch(name, groupOr(m, form.type()).trim(), groupOr(m, form.init()).trim());
        }
        return null;
    }

    // ── Empty initialisers ──

    /**
     * The shapes that mean "nothing in it yet". A local holding one of these just before a
     * loop is an accumulator the loop exists to fill.
     */
    private static final Pattern EMPTY_INIT = Pattern.compile("^(?:"
        + "\\[\\s*\\]"                                     // []
        + "|\\{\\s*\\}"                                    // {}
        + "|0(?:\\.0+)?[uUlLfF]?"                          // 0, 0.0, 0L
        + "|''|\"\"|``|'''|\"\"\""                         // empty strings
        + "|new\\s+\\w+(?:<[^>]*>)?\\s*\\(\\s*\\)"         // new ArrayList<>()
        + "|\\w+(?:<[^>]*>)?::new\\(\\)"                   // Vec::new()
        + "|vec!\\[\\s*\\]"                                // vec![]
        + "|make\\([^)]*\\)"                               // make(map[string]int)
        + "|\\[\\][\\w.]+\\{\\s*\\}"                       // []string{}
        + "|map\\[[^\\]]*\\][\\w.]+\\{\\s*\\}"             // map[string]int{}
        + "|(?:mutableListOf|mutableSetOf|mutableMapOf|listOf|arrayListOf|emptyList|emptyMap)"
        + "\\s*(?:<[^>]*>)?\\s*\\(\\s*\\)"
        + "|(?:ListBuffer|ArrayBuffer|Buffer|Set|Map|List|Seq)\\s*\\[[^\\]]*\\]\\s*\\(\\s*\\)"
        + "|(?:list|dict|set|tuple|str|int|float|bytearray)\\(\\s*\\)"
        + "|array\\(\\s*\\)"                               // PHP array()
        + "|StringBuilder\\s*\\(\\s*\\)|StringBuffer\\s*\\(\\s*\\)"
        + "|nil|None|null|undefined"
        + ")\\s*$");

    private static final Pattern PACKAGE_PREFIX = Pattern.compile("^(?:[a-z]\\w*\\.)+");

    /**
     * A type-first declaration with no initialiser ({@code std::vector<std::string> names;})
     * is empty by construction — C++ and Java default-construct it.
     */
    public static boolean isEmptyInitialiser(String init, String declaredType) {
        String text = init == null ? "" : init.trim().replaceAll("[;,]+$", "").trim();
        if (text.isEmpty()) return declaredType != null && !declaredType.isBlank();
        if (EMPTY_INIT.matcher(text).find()) return true;
        // A fully-qualified constructor names the same empty collection as its bare form:
        // scala.collection.mutable.ListBuffer[String](). Only lowercase package segments
        // are dropped, so a method call on a value is left alone.
        return EMPTY_INIT.matcher(PACKAGE_PREFIX.matcher(text).replaceFirst("")).find();
    }

    public static boolean isEmptyInitialiser(String init) {
        return isEmptyInitialiser(init, "");
    }

    // ── Types that mean "returns nothing" ──

    private static final Set<String> VOID_TYPES = Set.of(
        "void", "none", "unit", "()", "undefined", "never", "nothing", "nil", "");

    private static final Pattern ASYNC_WRAPPER =
        Pattern.compile("^(?:Promise|Future|Task|Awaitable|Deferred)\\s*<\\s*([\\s\\S]*)>$");

    /** Unwraps one async wrapper before deciding, so {@code Promise<void>} is still void. */
    public static boolean isVoidType(String type) {
        if (type == null) return true;
        String bare = type.trim();
        Matcher m = ASYNC_WRAPPER.matcher(bare);
        if (m.matches()) bare = m.group(1).trim();
        return VOID_TYPES.contains(bare.toLowerCase());
    }
}
