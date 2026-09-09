package com.llmcopilot.errors;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.llmcopilot.settings.LLMCopilotSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Holds the failures that have scrolled past in a run, debug or terminal
 * console, so they can still be asked about a minute later — once the user has
 * finished reading the trace and decided they want help with it.
 *
 * <p>One instance per project; the run listener writes to it and the actions
 * read from it.
 */
@Service(Service.Level.PROJECT)
public final class ErrorCaptureService {

    /** How many past failures stay available to the picker. */
    private static final int MAX_KEPT = 20;

    private final LinkedList<CapturedError> captures = new LinkedList<>();
    private int nextId = 1;

    public static ErrorCaptureService getInstance(Project project) {
        return project.getService(ErrorCaptureService.class);
    }

    /**
     * Trims the output to the part that matters and keeps it. Returns null when
     * there was nothing worth keeping, or when the same failure has just been
     * recorded — the same trace often arrives twice, once on stderr and once as
     * the runner's own summary of it.
     */
    public synchronized CapturedError record(String source, String origin, String rawText, Integer exitCode) {
        int maxLines = LLMCopilotSettings.getInstance().getErrorMaxOutputLines();
        String text = ErrorParser.extractErrorBlock(rawText == null ? "" : rawText, maxLines);
        if (text.isEmpty()) return null;

        CapturedError newest = captures.peekFirst();
        if (newest != null && newest.text().equals(text)) return null;

        CapturedError captured = new CapturedError(
            nextId++, source, origin, text, exitCode, System.currentTimeMillis());
        captures.addFirst(captured);
        while (captures.size() > MAX_KEPT) captures.removeLast();
        return captured;
    }

    /** Newest first. */
    public synchronized List<CapturedError> recent() {
        return new ArrayList<>(captures);
    }

    /**
     * The most recent capture, optionally restricted to particular sources —
     * {@code latest(CapturedError.RUN, CapturedError.DEBUG)} for the console.
     */
    public synchronized CapturedError latest(String... sources) {
        List<String> wanted = Arrays.asList(sources);
        for (CapturedError captured : captures) {
            if (wanted.isEmpty() || wanted.contains(captured.source())) return captured;
        }
        return null;
    }
}
