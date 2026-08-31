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
}
