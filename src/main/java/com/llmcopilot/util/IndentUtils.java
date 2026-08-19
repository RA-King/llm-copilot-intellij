package com.llmcopilot.util;

import com.intellij.openapi.editor.Editor;

public class IndentUtils {

    /** Strip LLM artefacts and re-indent to match the cursor's base indent */
    public static String reindent(String raw, String linePrefix, Editor editor) {
        // 1. Strip artefacts
        String s = raw
            .replaceAll("(?s)^```\\w*\\r?\\n?", "")
            .replaceAll("\\r?\\n?```\\s*$", "")
            .replaceAll("(?i)^Completion:\\s*\\n?", "")
            .stripTrailing();
        if (s.isBlank()) return "";

        // 2. Detect base indent from linePrefix
        String baseIndent = linePrefix.isEmpty() ? ""
            : linePrefix.substring(0, linePrefix.length() - linePrefix.stripLeading().length());

        // 3. Detect indent style from editor
        boolean useTabs = editor.getSettings().isUseTabCharacter(editor.getProject());
        int tabSize = editor.getSettings().getTabSize(editor.getProject());

        // 4. Find minimum indent of completion
        String[] lines = s.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int n = 0;
            for (char c : line.toCharArray()) {
                if (c == '\t') n += tabSize;
                else if (c == ' ') n++;
                else break;
            }
            minIndent = Math.min(minIndent, n);
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;

        int baseLen = measureIndent(baseIndent, tabSize);

        // 5. Re-indent each line
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                out.append(i == 0 ? "" : "\n");
                continue;
            }
            int lineIndent = measureIndent(line, tabSize);
            int relative = Math.max(0, lineIndent - minIndent);
            int target = i == 0 ? relative : baseLen + relative;
            String newLeading = buildIndent(target, useTabs, tabSize);
            if (i > 0) out.append("\n");
            out.append(newLeading).append(line.stripLeading());
        }
        return out.toString();
    }

    private static int measureIndent(String line, int tabSize) {
        int n = 0;
        for (char c : line.toCharArray()) {
            if (c == '\t') n += tabSize;
            else if (c == ' ') n++;
            else break;
        }
        return n;
    }

    private static String buildIndent(int len, boolean useTabs, int tabSize) {
        if (useTabs) {
            return "\t".repeat(len / tabSize) + " ".repeat(len % tabSize);
        }
        return " ".repeat(len);
    }
}
