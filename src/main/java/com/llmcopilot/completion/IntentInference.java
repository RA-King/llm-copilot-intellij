package com.llmcopilot.completion;

import com.intellij.openapi.editor.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what the author is <em>trying to do</em> from the code already written, so the
 * completion prompt can carry an explicit hypothesis instead of leaving the model to
 * guess from surrounding text.
 *
 * <p>Four sources of evidence, none of which need PSI or a resolved index:
 *
 * <ul>
 *   <li>The name of the enclosing declaration. {@code fetchUserOrders} is a verb applied
 *       to a subject, and the verb says what shape the body takes — a fetch awaits and
 *       returns, a validate checks and rejects, a collect accumulates into something.</li>
 *   <li>The signature. Parameters nothing has referenced are work not yet done; a declared
 *       return type that no main-path return satisfies is a debt still outstanding.</li>
 *   <li>The locals. One initialised to an empty collection just before the loop the caret
 *       sits in is an accumulator, and the statement being typed almost certainly writes
 *       to it.</li>
 *   <li>The block the caret is directly inside — a loop over a named collection, a catch
 *       with a bound error, a chain of guards — which constrains what can follow.</li>
 * </ul>
 *
 * <p>Deliberately text-based rather than PSI-based. {@link PsiCodeContextCollector} answers
 * <em>where</em> the caret is from the parse tree; this answers <em>what is being written</em>,
 * needs no committed document, works for languages with no reference resolution, and is
 * cheap enough to run on every trigger.
 */
public final class IntentInference {

    /** What the enclosing declaration's name says it exists to do. */
    public enum GoalKind {
        FETCH, CREATE, TRANSFORM, COMPUTE, VALIDATE, PREDICATE, MUTATE, HANDLE, TEST, UNKNOWN
    }

    /** The kind of block the caret sits directly inside. */
    public enum ConstructKind { LOOP, BRANCH, TRY, CATCH, FINALLY, SWITCH, WITH, CALLBACK }

    /**
     * How much code the caret is asking for. Finishing a half-written expression is one
     * line; an empty line in a body takes a statement or two; the line after an opening
     * brace takes the block.
     */
    public enum Shape { EXPRESSION, STATEMENT, BLOCK }

    /** A local or parameter, with its declared type when the language states one. */
    public record Binding(String name, String type, int line) {}

    /** The innermost still-open block above the caret. */
    public record OpenConstruct(ConstructKind kind, String header, int line,
                                String binding, String iterable, String condition) {}

    /** Everything inferred about the caret, ready to be rendered into the prompt. */
    public record Reading(String goal, GoalKind goalKind, String subject,
                          List<String> unusedParams, List<Binding> unusedLocals,
                          Binding accumulator, OpenConstruct openConstruct,
                          int guardCount, boolean returnPending, Shape shape,
                          List<String> nextSteps) {

        public static final Reading EMPTY = new Reading(
            "", GoalKind.UNKNOWN, "", List.of(), List.of(), null, null, 0, false,
            Shape.STATEMENT, List.of());

        public boolean isEmpty() {
            return goalKind == GoalKind.UNKNOWN && openConstruct == null && accumulator == null
                && unusedParams.isEmpty() && unusedLocals.isEmpty()
                && guardCount == 0 && !returnPending && nextSteps.isEmpty();
        }
    }

    /** Lines above the caret searched for the enclosing declaration. */
    private static final int LOOKBACK = 150;

    private IntentInference() {}

    // ── Reading a name ────────────────────────────────────────────────────────

    private static final Map<String, GoalKind> VERBS = verbs();

    private static Map<String, GoalKind> verbs() {
        Map<String, GoalKind> m = new LinkedHashMap<>();
        for (String v : "fetch get load read find query list search lookup retrieve resolve request download".split(" "))
            m.put(v, GoalKind.FETCH);
        for (String v : "create build make init construct generate render compose setup spawn clone collect gather accumulate aggregate assemble".split(" "))
            m.put(v, GoalKind.CREATE);
        for (String v : "parse format convert map serialize serialise deserialize deserialise encode decode normalize normalise transform to from stringify extract filter sort merge split summarize summarise flatten group reduce join wrap".split(" "))
            m.put(v, GoalKind.TRANSFORM);
        for (String v : "calculate compute count sum total average measure score rank diff compare".split(" "))
            m.put(v, GoalKind.COMPUTE);
        for (String v : "validate check verify ensure assert require guard sanitize sanitise".split(" "))
            m.put(v, GoalKind.VALIDATE);
        for (String v : "is has can should contains matches equals exists supports allows needs".split(" "))
            m.put(v, GoalKind.PREDICATE);
        for (String v : "set add append push insert update save store write persist delete remove clear reset apply register dispose close send emit publish sync refresh install".split(" "))
            m.put(v, GoalKind.MUTATE);
        for (String v : "handle on process dispatch run execute start main".split(" "))
            m.put(v, GoalKind.HANDLE);
        return Map.copyOf(m);
    }

    /**
     * Splits an identifier into lowercase words. camelCase, PascalCase, snake_case,
     * kebab-case and SCREAMING_CASE all reduce to the same shape, and runs of capitals
     * stay whole: {@code parseHTTPResponse} becomes {@code [parse, http, response]}.
     */
    public static List<String> splitIdentifier(String name) {
        if (name == null || name.isBlank()) return List.of();
        String spaced = name.replaceAll("^[_$]+|[_$]+$", "")
                            .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        List<String> words = new ArrayList<>();
        for (String w : spaced.split("[\\s_.\\-]+")) {
            if (!w.isBlank()) words.add(w.toLowerCase());
        }
        return words;
    }

    /** A name read as a verb applied to a subject. */
    public record Named(GoalKind kind, String goal, String subject) {}

    /**
     * Reads a declaration name as an intention. Names whose first word is not a verb this
     * knows report {@link GoalKind#UNKNOWN} rather than a guess.
     */
    public static Named classifyName(String name) {
        List<String> words = splitIdentifier(name);
        if (words.isEmpty()) return new Named(GoalKind.UNKNOWN, "", "");

        String first = words.get(0);
        String rest  = String.join(" ", words.subList(1, words.size()));

        if (first.matches("test|it|should|spec") || words.get(words.size() - 1).matches("test|spec")) {
            return new Named(GoalKind.TEST, ("test " + rest).trim(), rest);
        }

        GoalKind kind = VERBS.getOrDefault(first, GoalKind.UNKNOWN);
        if (kind == GoalKind.PREDICATE) {
            return new Named(kind, ("decide whether something " + first + " " + rest).trim(), rest);
        }
        if (kind == GoalKind.UNKNOWN) {
            return new Named(kind, String.join(" ", words), rest.isBlank() ? String.join(" ", words) : rest);
        }
        return new Named(kind, (first + " " + rest).trim(), rest);
    }

    // ── Document helpers ──────────────────────────────────────────────────────

    private static String lineText(Document doc, int line) {
        if (line < 0 || line >= doc.getLineCount()) return "";
        return doc.getCharsSequence()
                  .subSequence(doc.getLineStartOffset(line), doc.getLineEndOffset(line))
                  .toString();
    }

    static int indentWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') width++;
            else if (c == '\t') width += 4;
            else break;
        }
        return width;
    }

    /** Blanks string and character literals so a brace inside one cannot be read as code. */
    static String stripLiterals(String line) {
        StringBuilder out = new StringBuilder(line.length());
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                out.append(c == quote ? c : ' ');
                if (c == quote && (i == 0 || line.charAt(i - 1) != '\\')) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') { quote = c; out.append(c); continue; }
            out.append(c);
        }
        return out.toString();
    }

    /** Body text between {@code fromLine} and {@code toLine}, literals blanked. */
    private static String bodyText(Document doc, int fromLine, int toLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, fromLine); i <= Math.min(toLine, doc.getLineCount() - 1); i++) {
            sb.append(stripLiterals(lineText(doc, i))).append('\n');
        }
        return sb.toString();
    }

    private static int referenceCount(String body, String name) {
        if (name == null || name.isBlank()) return 0;
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(body);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    // ── The enclosing declaration and the block above the caret ───────────────

    /**
     * Lines that structurally contain the caret, innermost first. A line is an ancestor
     * only when it is less indented than every line already accepted, which keeps closing
     * braces and finished sibling blocks out and works for brace and indentation
     * languages alike.
     */
    static List<Integer> ancestorLines(Document doc, int caretLine, int caretIndent) {
        List<Integer> out = new ArrayList<>();
        int minIndent = caretIndent;
        for (int i = caretLine - 1; i >= 0 && i >= caretLine - LOOKBACK; i--) {
            String raw = lineText(doc, i);
            if (raw.isBlank()) continue;
            int indent = indentWidth(raw);
            if (indent >= minIndent) continue;
            out.add(i);
            minIndent = indent;
            if (minIndent == 0) break;
        }
        return out;
    }

    private static final Pattern FUNCTION_HEADER = Pattern.compile(
        "(?:^|\\s)(?:function|fn|def|func|fun|sub)\\s+([A-Za-z_$][\\w$]*)"
      + "|(?:^|\\s)([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\([^;]*\\)\\s*(?:->|:)?[^;{]*\\{"
      + "|(?:^|\\s)([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\(");

    private static final Pattern CONTROL_KEYWORD = Pattern.compile(
        "^(?:if|unless|for|foreach|while|do|loop|switch|match|when|try|begin|catch|except"
      + "|rescue|else|elif|finally|ensure|with|using|return|yield)\\b");

    /** The declaration name on a header line, or {@code null} when it is not one. */
    static String functionNameOn(String rawLine) {
        // A closing brace can share the line with the keyword that follows it
        // (`} catch (IOException err) {`), so drop it before classifying.
        String line = stripLiterals(rawLine).trim().replaceFirst("^\\}\\s*", "");
        if (CONTROL_KEYWORD.matcher(line).find()) return null;
        Matcher m = FUNCTION_HEADER.matcher(line);
        if (!m.find()) return null;
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) != null) return m.group(g);
        }
        return null;
    }

    private static final List<Map.Entry<Pattern, ConstructKind>> BLOCK_OPENERS = List.of(
        Map.entry(Pattern.compile("^(?:for|foreach)\\b", Pattern.CASE_INSENSITIVE), ConstructKind.LOOP),
        Map.entry(Pattern.compile("^while\\b|^do\\b|^loop\\b"),                     ConstructKind.LOOP),
        Map.entry(Pattern.compile("^(?:\\}\\s*)?else\\s+if\\b|^elif\\b"),           ConstructKind.BRANCH),
        Map.entry(Pattern.compile("^(?:\\}\\s*)?else\\b"),                          ConstructKind.BRANCH),
        Map.entry(Pattern.compile("^if\\b|^unless\\b"),                             ConstructKind.BRANCH),
        Map.entry(Pattern.compile("^try\\b|^begin\\b"),                             ConstructKind.TRY),
        Map.entry(Pattern.compile("^(?:\\}\\s*)?(?:catch|except|rescue)\\b"),       ConstructKind.CATCH),
        Map.entry(Pattern.compile("^(?:\\}\\s*)?finally\\b|^ensure\\b"),            ConstructKind.FINALLY),
        Map.entry(Pattern.compile("^switch\\b|^match\\b|^when\\b"),                 ConstructKind.SWITCH),
        Map.entry(Pattern.compile("^with\\b|^using\\b"),                            ConstructKind.WITH));

    private static boolean opensBlock(String text) {
        return text.endsWith("{") || text.endsWith("(") || text.endsWith("[")
            || text.endsWith(":") || text.matches(".*\\b(?:do|then)\\s*(?:\\|[^|]*\\|)?$");
    }

    /** Classifies an ancestor line as the block the caret is writing into. */
    static OpenConstruct constructOn(String rawLine, int line) {
        String text = stripLiterals(rawLine).trim();
        if (!opensBlock(text)) return null;
        for (Map.Entry<Pattern, ConstructKind> e : BLOCK_OPENERS) {
            if (!e.getKey().matcher(text).find()) continue;
            ConstructKind kind = e.getValue();
            return new OpenConstruct(kind, text, line, loopBinding(text, kind),
                                     loopIterable(text), blockCondition(text, kind));
        }
        return new OpenConstruct(ConstructKind.CALLBACK, text, line, "", "", "");
    }

    private static String loopBinding(String header, ConstructKind kind) {
        if (kind == ConstructKind.CATCH) {
            return group(header, "(?:catch|except|rescue)\\s*\\(?\\s*(?:[\\w.]+\\s+(?:as\\s+)?)?([A-Za-z_$][\\w$]*)", 1);
        }
        if (kind != ConstructKind.LOOP) return "";
        String name = group(header, "for\\s*\\(?\\s*(?:const|let|var|final|auto)?\\s*([A-Za-z_$][\\w$]*)\\s+(?:of|in)\\b", 1);
        if (!name.isEmpty()) return name;
        name = group(header, "for\\s+([A-Za-z_$][\\w$]*)\\s+in\\b", 1);
        if (!name.isEmpty()) return name;
        name = group(header, "for\\s*\\(\\s*(?:[\\w<>\\[\\].]+\\s+)?([A-Za-z_$][\\w$]*)\\s*:", 1);
        if (!name.isEmpty()) return name;
        return group(header, "for\\s*\\(\\s*(?:const|let|var|int|size_t)?\\s*([A-Za-z_$][\\w$]*)\\s*=", 1);
    }

    private static String loopIterable(String header) {
        String it = group(header, "\\b(?:of|in)\\s+([A-Za-z_$][\\w$.]*)", 1);
        if (!it.isEmpty()) return it;
        return group(header, ":\\s*([A-Za-z_$][\\w$.]*)\\s*\\)", 1);
    }

    private static String blockCondition(String header, ConstructKind kind) {
        if (kind != ConstructKind.BRANCH && kind != ConstructKind.LOOP && kind != ConstructKind.SWITCH) return "";
        String paren = group(header, "\\(([^)]*)\\)\\s*[{:]?$", 1);
        if (!paren.isEmpty()) return paren.trim();
        return header.replaceAll("^(?:\\}\\s*)?(?:else\\s+if|if|elif|unless|while|switch|match)\\s*", "")
                     .replaceAll("[:{]$", "").trim();
    }

    private static String group(String text, String regex, int g) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() && m.group(g) != null ? m.group(g) : "";
    }

    // ── The signature ─────────────────────────────────────────────────────────

    /** Joins a header that wrapped across lines, so its parameter list is complete. */
    private static String headerText(Document doc, int line) {
        StringBuilder sb = new StringBuilder(stripLiterals(lineText(doc, line)));
        for (int i = line + 1; i < Math.min(line + 4, doc.getLineCount()); i++) {
            if (balanced(sb.toString())) break;
            sb.append(' ').append(stripLiterals(lineText(doc, i)).trim());
        }
        return sb.toString().trim();
    }

    private static boolean balanced(String text) {
        int depth = 0;
        boolean sawOpen = false;
        for (char c : text.toCharArray()) {
            if (c == '(') { depth++; sawOpen = true; }
            else if (c == ')') depth--;
        }
        return sawOpen && depth <= 0;
    }

    /** The text between the parameter list's own parentheses. */
    static String paramSource(String header) {
        int open = -1, depth = 0;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '(') {
                if (depth == 0 && open < 0) open = i;
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && open >= 0) return header.substring(open + 1, i);
            }
        }
        return "";
    }

    /** Splits on commas that are not nested inside brackets or generics. */
    static List<String> splitTopLevel(String source) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(' || c == '[' || c == '<' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '>' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                String chunk = source.substring(start, i).trim();
                if (!chunk.isEmpty()) out.add(chunk);
                start = i + 1;
            }
        }
        String last = source.substring(start).trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    /**
     * The parameter's name, whichever side of it the type sits on: {@code name: String},
     * {@code final String name} and {@code String... names} all yield {@code name}.
     */
    static String paramName(String chunk) {
        String text = chunk;
        int eq = text.indexOf('=');
        if (eq >= 0) text = text.substring(0, eq);
        int colon = text.indexOf(':');
        if (colon >= 0) text = text.substring(0, colon);
        text = text.replaceAll("[*&]", " ").replaceAll("\\.\\.\\.", " ").trim();
        if (text.isEmpty()) return "";
        String[] words = text.split("[\\s\\[\\]]+");
        for (int i = words.length - 1; i >= 0; i--) {
            if (words[i].matches("[A-Za-z_$][\\w$]*")) return words[i];
        }
        return "";
    }

    /** The declared return type, read from either side of the name. */
    static String returnType(String header, String name) {
        String after = group(header, "\\)\\s*(?:->|:)\\s*([^{;]+?)\\s*[{;]?\\s*$", 1);
        if (!after.isBlank()) return after.trim();

        String before = group(header, "([\\w<>\\[\\],.?]+)\\s+" + Pattern.quote(name) + "\\s*\\(", 1);
        if (before.isBlank()) return "";
        if (before.matches("public|private|protected|static|final|abstract|synchronized|native|default|new")) return "";
        return before;
    }

    // ── Progress through the body ─────────────────────────────────────────────

    private static final Pattern GUARD =
        Pattern.compile("^(?:if|unless)\\b.*\\b(?:return|throw|raise|panic|continue)\\b");

    private static int countGuards(Document doc, int fromLine, int toLine) {
        int guards = 0;
        for (int i = fromLine; i <= toLine && i < doc.getLineCount(); i++) {
            String text = stripLiterals(lineText(doc, i)).trim();
            if (text.isEmpty()) continue;
            if (GUARD.matcher(text).find()) { guards++; continue; }
            if (text.matches("^(?:if|unless)\\b.*")
                && stripLiterals(lineText(doc, i + 1)).trim().matches("^(?:return|throw|raise)\\b.*")) {
                guards++;
                continue;
            }
            if (!text.startsWith("}") && !text.startsWith(")") && !text.startsWith("]")) break;
        }
        return guards;
    }

    private static final Pattern LOCAL_DECL = Pattern.compile(
        "^(?:const|let|var|final|val|auto)\\s+([A-Za-z_$][\\w$]*)\\s*(?::\\s*([^=]+?))?\\s*="
      + "|^([A-Z][\\w<>\\[\\],.]*)\\s+([a-z_$][\\w$]*)\\s*="
      + "|^([a-z_$][\\w$]*)\\s*:?=\\s*");

    /** Locals declared between the header and the caret, in declaration order. */
    static List<Binding> localsIn(Document doc, int fromLine, int toLine) {
        List<Binding> out = new ArrayList<>();
        for (int i = fromLine; i <= toLine && i < doc.getLineCount(); i++) {
            String text = stripLiterals(lineText(doc, i)).trim();
            Matcher m = LOCAL_DECL.matcher(text);
            if (!m.find()) continue;
            if (m.group(1) != null)      out.add(new Binding(m.group(1), trimType(m.group(2)), i));
            else if (m.group(4) != null) out.add(new Binding(m.group(4), trimType(m.group(3)), i));
            else if (m.group(5) != null) out.add(new Binding(m.group(5), "", i));
        }
        return out;
    }

    private static String trimType(String type) {
        return type == null ? "" : type.trim();
    }

    private static final Pattern EMPTY_INIT = Pattern.compile(
        "^(?:\\[\\]|\\{\\}|0|0\\.0|''|\"\"|``|new\\s+\\w+(?:<[^>]*>)?\\(\\s*\\)"
      + "|make\\(|list\\(\\)|dict\\(\\)|set\\(\\)|\\w+::new\\(\\))");

    /** The right-hand side of a declaration, for spotting something being filled in. */
    private static String initialiserOf(Document doc, Binding binding) {
        String line = stripLiterals(lineText(doc, binding.line()));
        String rhs = group(line, "\\b" + Pattern.quote(binding.name()) + "\\b[^=]*=\\s*(.+?);?\\s*$", 1);
        return rhs.trim();
    }

    /**
     * A local initialised to an empty collection, zero or an empty string is being filled
     * in — and when the caret is inside a loop that follows it, the statement being typed
     * is almost certainly the one that writes to it.
     */
    private static Binding findAccumulator(Document doc, List<Binding> locals, OpenConstruct open) {
        List<Binding> candidates = new ArrayList<>();
        for (Binding b : locals) {
            if (EMPTY_INIT.matcher(initialiserOf(doc, b)).find()) candidates.add(b);
        }
        if (candidates.isEmpty()) return null;
        if (open != null && open.kind() == ConstructKind.LOOP) {
            for (int i = candidates.size() - 1; i >= 0; i--) {
                if (candidates.get(i).line() < open.line()) return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static final List<String> VOID_TYPES =
        List.of("void", "none", "unit", "()", "undefined", "never", "");

    /**
     * Whether the declared result is still owed. Returns already written that are indented
     * deeper than the body are guards and branch exits, not the answer.
     */
    private static boolean owesReturn(String returnType, String body) {
        String declared = returnType.trim().replaceAll("^(?:Promise|Future|Task)<|>$", "").trim();
        if (VOID_TYPES.contains(declared.toLowerCase())) return false;
        return !Pattern.compile("\\n {0,4}return\\s+\\S").matcher(body).find();
    }
}
