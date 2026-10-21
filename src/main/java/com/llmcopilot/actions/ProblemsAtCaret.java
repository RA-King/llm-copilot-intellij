package com.llmcopilot.actions;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;

/** The errors and warnings the IDE is showing on the caret's line, or in a whole file. */
public final class ProblemsAtCaret {

    private ProblemsAtCaret() {}

    /** {@code line} is zero-based. */
    public record Problem(int line, String severity, String description) {
        public String describe() { return "line " + (line + 1) + " " + severity + ": " + description; }
    }

    public static List<Problem> onCaretLine(Project project, Editor editor) {
        Document doc = editor.getDocument();
        int line = doc.getLineNumber(editor.getCaretModel().getOffset());
        return in(project, doc, doc.getLineStartOffset(line), doc.getLineEndOffset(line));
    }

    public static List<Problem> inFile(Project project, Document doc) {
        return in(project, doc, 0, doc.getTextLength());
    }

    private static List<Problem> in(Project project, Document doc, int start, int end) {
        List<Problem> problems = new ArrayList<>();
        DaemonCodeAnalyzerEx.processHighlights(doc, project, HighlightSeverity.WEAK_WARNING, start, end, info -> {
            String description = describe(info);
            if (description != null && problems.stream().noneMatch(p -> p.description().equals(description))) {
                int line = doc.getLineNumber(Math.min(info.getStartOffset(), doc.getTextLength()));
                problems.add(new Problem(line, info.getSeverity().compareTo(HighlightSeverity.ERROR) >= 0 ? "error" : "warning",
                    description));
            }
            return problems.size() < 50;
        });
        return problems;
    }

    private static String describe(HighlightInfo info) {
        String text = info.getDescription();
        return text == null || text.isBlank() ? null : text.replaceAll("<[^>]+>", "").trim();
    }
}
