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

    public static String completionPrompt(String prefix, String suffix, String lang,
                                           String filename, String intent, int depth,
                                           String structuralGuide, String workspaceCtx,
                                           String keywordHint) {
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

        return "You are an expert " + lang + " code completion engine.\n" +
               "Suggest ONLY new code — NEVER rewrite or alter existing code.\n\n" +
               (structuralGuide != null ? structuralGuide + "\n" : "") +
               intentGuide + "\n" +
               wsSection +
               "\nRules:\n" +
               "- Output ONLY raw code. No markdown, no backticks, no explanation.\n" +
               "- Match indentation and naming conventions exactly.\n" +
               "- Never repeat code already above the cursor.\n\n" +
               "File: " + filename + "\n\n" +
               "```" + lang + "\n" + prefix + "<CURSOR>" + suffix + "\n```\n\nCompletion:";
    }

    private static LLMClient.ChatMessage sys(String content) { return new LLMClient.ChatMessage("system", content); }
    private static LLMClient.ChatMessage usr(String content) { return new LLMClient.ChatMessage("user", content); }
}
