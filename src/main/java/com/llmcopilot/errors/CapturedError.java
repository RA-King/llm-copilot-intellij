package com.llmcopilot.errors;

/**
 * One failure, as it was captured. The text has already been trimmed to the
 * part worth reading by {@link ErrorParser#extractErrorBlock}.
 */
public record CapturedError(
    int id,
    /** One of {@link #TERMINAL}, {@link #RUN} or {@link #DEBUG}. */
    String source,
    /** The run configuration, terminal or debug session that produced it. */
    String origin,
    String text,
    /** Process exit code where one was reported, or null. */
    Integer exitCode,
    long at
) {
    public static final String TERMINAL = "terminal";
    public static final String RUN      = "run";
    public static final String DEBUG    = "debug";

    /** Whether the output came from a debugger rather than a plain run. */
    public boolean isDebug() {
        return DEBUG.equals(source);
    }

    /** The first line with anything on it, short enough for a notification. */
    public String firstLine() {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            return trimmed.length() > 100 ? trimmed.substring(0, 97) + "…" : trimmed;
        }
        return "";
    }
}
