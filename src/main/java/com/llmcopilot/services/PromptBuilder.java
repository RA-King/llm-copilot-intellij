package com.llmcopilot.services;

import java.util.List;

/** All LLM prompt builders — mirrors the TypeScript prompt builders in llmProvider.ts */
public class PromptBuilder {

    public static List<LLMClient.ChatMessage> explain(String code, String lang) {
        return List.of(
            sys("You are an expert code reviewer. Give clear, concise explanations."),
            usr("Explain this " + lang + " code:\n```" + lang + "\n" + code + "\n```")
        );
    }

    public static List<LLMClient.ChatMessage> fix(String code, String lang) {
        return List.of(
            sys("You are an expert programmer. Fix bugs. Return only the corrected code."),
            usr("Fix bugs in this " + lang + " code:\n```" + lang + "\n" + code + "\n```")
        );
    }

    public static List<LLMClient.ChatMessage> refactor(String code, String lang, String instruction) {
        return List.of(
            sys("You are an expert " + lang + " programmer. Perform the requested refactoring. " +
                "Return ONLY the refactored code — no markdown, no explanation."),
            usr("Refactor this " + lang + " code.\nInstruction: " + instruction +
                "\n\nCode:\n```" + lang + "\n" + code + "\n```\n\nReturn only the refactored code:")
        );
    }

    public static List<LLMClient.ChatMessage> generateTests(String code, String lang, String framework) {
        String fw = framework == null || framework.isBlank()
            ? "Use the most common testing framework for the language."
            : "Use the " + framework + " testing framework.";
        return List.of(
            sys("You are an expert " + lang + " test engineer. " + fw +
                " Cover happy paths, edge cases, and error cases. Output only raw test code."),
            usr("Generate unit tests for:\n```" + lang + "\n" + code + "\n```")
        );
    }

    public static List<LLMClient.ChatMessage> generateDocComment(String code, String lang) {
        return List.of(
            sys("Generate a documentation comment appropriate for " + lang + ". " +
                "Return ONLY the comment content as PLAIN TEXT — do NOT include " +
                "comment markers like ///, /**, *, #, or --. " +
                "The tool will wrap your text in the correct markers automatically."),
            usr("Generate a comprehensive docstring for this " + lang + " code:\n```" + lang + "\n" + code + "\n```")
        );
    }

    public static List<LLMClient.ChatMessage> generateConstructor(String classCode, String lang) {
        return List.of(
            sys("You are an expert " + lang + " programmer. Output ONLY raw code — no markdown fences, no explanation."),
            usr("Generate ONLY a constructor for this " + lang + " class. " +
                "All fields as parameters with types. Full body with field assignments.\n```" + lang + "\n" + classCode + "\n```")
        );
    }

    public static List<LLMClient.ChatMessage> generateGetterSetter(String classCode, String lang,
                                                                     String fieldName, String fieldType) {
        return List.of(
            sys("You are an expert " + lang + " programmer. Output ONLY raw code — no markdown, no explanation."),
            usr("Generate ONLY the getter and setter for the single field \"" + fieldName +
                "\" (type: " + fieldType + ") in this " + lang + " class. " +
                "ONE field only — do not generate accessors for any other field.\n```" + lang + "\n" + classCode + "\n```")
        );
    }

    public static List<LLMClient.ChatMessage> implementMethod(String methodSig, String classContext, String lang) {
        return List.of(
            sys("You are an expert " + lang + " programmer implementing a single interface method. " +
                "Output ONLY the complete implementation — no surrounding class, no markdown, no explanation."),
            usr("Implement ONLY this single " + lang + " method:\n" + methodSig +
                "\n\nClass context:\n```" + lang + "\n" + classContext + "\n```\n\nReturn ONLY this one method:")
        );
    }

    public static List<LLMClient.ChatMessage> inlineChat(String instruction, String selectedCode,
                                                          String surroundingContext, String lang) {
        StringBuilder sb = new StringBuilder(instruction);
        if (selectedCode != null && !selectedCode.isBlank())
            sb.append("\n\nSelected code:\n```").append(lang).append("\n").append(selectedCode).append("\n```");
        if (surroundingContext != null && !surroundingContext.isBlank())
            sb.append("\n\nContext:\n```").append(lang).append("\n").append(surroundingContext).append("\n```");
        return List.of(
            sys("You are an expert " + lang + " assistant in the editor. Be concise and direct."),
            usr(sb.toString())
        );
    }

    public static List<LLMClient.ChatMessage> commitMessage(String diff) {
        return List.of(
            sys("Generate a concise git commit message in Conventional Commits format (type: description). Output ONLY the message."),
            usr("Write a commit message for this diff:\n\n" + diff.substring(0, Math.min(3000, diff.length())))
        );
    }

    // ── Error assistance ─────────────────────────────────────────────────────

    /**
     * Everything known about a failure the user is looking at, gathered from a
     * run, debug or terminal console and — where a frame pointed at a file in
     * the project — the source around the line that threw.
     */
    public record ErrorContext(
        /** Which console the output came from: "terminal", "run" or "debug". */
        String source,
        /** The run configuration, session or command that produced it. */
        String origin,
        /** Runtime or tool the trace format belongs to — node, python, compiler, … */
        String runtime,
        String headline,
        /** The output itself, already trimmed to the part that matters. */
        String errorText,
        /** Source around the failing lines, fenced and labelled; "" when none resolved. */
        String codeContext
    ) { }

    private static String describeFailure(ErrorContext ctx) {
        String where = "debug".equals(ctx.source())
            ? "Debug session: " + ctx.origin()
            : ("terminal".equals(ctx.source()) ? "Terminal: " : "Console: ") + ctx.origin();

        String code = ctx.codeContext().isBlank()
            ? "\n\nNo file from the trace could be resolved in the project, so reason from the output alone."
            : "\n\nThe source at the frames named above:\n" + ctx.codeContext();

        return where + "\nRuntime: " + ctx.runtime() +
               "\n\nOutput:\n```text\n" + ctx.errorText() + "\n```" + code;
    }

    /**
     * Asks for a shortlist rather than an answer. The list is shown in a popup
     * before anything longer is generated, so each entry has to read on one line
     * and be distinct from the others — different causes, not one cause phrased
     * three ways.
     */
    public static List<LLMClient.ChatMessage> errorSolutions(ErrorContext ctx, int count) {
        return List.of(
            sys("You are an expert debugging assistant. Given a failure and the code around it, propose " +
                "exactly " + count + " distinct candidate fixes, most likely first. Each must address a " +
                "different possible cause.\n\n" +
                "Format each one as:\n" +
                "1. Short imperative title, under ten words\n" +
                "   One or two sentences: the cause you are proposing, and the change that fixes it.\n\n" +
                "Name real identifiers, files and line numbers from the material you were given. " +
                "No preamble, no closing summary, no code fences."),
            usr(describeFailure(ctx))
        );
    }

    /** The chosen entry, expanded into an answer with the actual edit in it. */
    public static List<LLMClient.ChatMessage> errorWalkthrough(ErrorContext ctx, String title, String detail) {
        return List.of(
            sys("You are an expert debugging assistant working inside the IDE. State the cause in a " +
                "sentence or two, then give the exact change as a code block fenced with the language " +
                "name. Keep it to the smallest edit that fixes the failure, and say what to check next " +
                "if the cause cannot be confirmed from what you were shown."),
            usr(describeFailure(ctx) + "\n\nTake this approach:\n" + title +
                (detail == null || detail.isBlank() ? "" : "\n" + detail) +
                "\n\nShow me the change.")
        );
    }

    /** No fix yet — just what the output means. */
    public static List<LLMClient.ChatMessage> errorExplain(ErrorContext ctx) {
        return List.of(
            sys("You are an expert debugging assistant. Explain what the failure means and what sequence " +
                "of events produces it, in plain language and few paragraphs. Do not propose a fix unless " +
                "the cause is certain."),
            usr(describeFailure(ctx))
        );
    }

    /** A question of the user's own, carrying the failure as context. */
    public static List<LLMClient.ChatMessage> errorQuestion(ErrorContext ctx, String question) {
        return List.of(
            sys("You are an expert debugging assistant working inside the IDE. Answer the question using " +
                "the failure and code below. Be concise, and fence any code with the language name."),
            usr(question + "\n\n" + describeFailure(ctx))
        );
    }

    public static String completionPrompt(String prefix, String suffix, String lang,
                                           String filename, String intent, int depth,
                                           String structuralGuide, String workspaceCtx,
                                           String keywordHint) {
        return completionPrompt(prefix, suffix, lang, filename, intent, depth,
                                structuralGuide, workspaceCtx, keywordHint, null);
    }

    public static String completionPrompt(String prefix, String suffix, String lang,
                                           String filename, String intent, int depth,
                                           String structuralGuide, String workspaceCtx,
                                           String keywordHint, String intentReading) {
        String intentGuide;
        if (keywordHint != null && !keywordHint.isBlank()) {
            intentGuide = "The user just typed the keyword \"" + keywordHint + "\". " +
                "Complete the full " + keywordHint + " construct. " +
                "Output only what comes AFTER the keyword — do NOT repeat it.";
        } else {
            intentGuide = switch (intent) {
                case "new-block" -> "Suggest the NEXT logical declaration/function/class at top level.";
                case "new-statement" -> "Suggest the NEXT logical statement(s) inside the current block (depth " + depth + ").";
                default -> "Complete what the user has started typing on this line.";
            };
        }

        String wsSection = (workspaceCtx != null && !workspaceCtx.isBlank())
            ? "\n// ── Related declarations resolved by the IDE ──\n" +
              "// These signatures are real. Call them exactly as declared.\n" + workspaceCtx + "\n"
            : "";

        String intentSection = (intentReading != null && !intentReading.isBlank())
            ? "\n// ── What the code so far is working towards ──\n" + intentReading + "\n"
            : "";

        return "You are an expert " + lang + " code completion engine.\n" +
               "Suggest ONLY new code — NEVER rewrite or alter existing code.\n\n" +
               (structuralGuide != null ? structuralGuide + "\n" : "") +
               intentGuide + "\n" +
               wsSection +
               intentSection +
               "\nRules:\n" +
               "- Output ONLY raw code. No markdown, no backticks, no explanation.\n" +
               "- Match indentation and naming conventions exactly.\n" +
               "- Never repeat code already above the cursor.\n" +
               "- Continue the author's line of thought as described above; do not start a different one.\n" +
               "- Suggest only as much code as that section asks for. Stopping early beats running past it.\n\n" +
               "File: " + filename + "\n\n" +
               "```" + lang + "\n" + prefix + "<CURSOR>" + suffix + "\n```\n\nCompletion:";
    }

    private static LLMClient.ChatMessage sys(String content) { return new LLMClient.ChatMessage("system", content); }
    private static LLMClient.ChatMessage usr(String content) { return new LLMClient.ChatMessage("user", content); }
}
