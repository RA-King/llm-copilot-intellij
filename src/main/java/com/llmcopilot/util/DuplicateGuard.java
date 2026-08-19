package com.llmcopilot.util;

import java.util.*;

/**
 * Three-level duplication guard — mirrors duplicationGuard.ts.
 * Prevents ghost text from suggesting code that already exists in the file.
 */
public class DuplicateGuard {

    /** Main entry point. Returns cleaned completion or null if it should be suppressed. */
    public static String guard(String completion, String prefix, String suffix, String typedOnLine) {
        if (completion == null || completion.isBlank()) return null;

        // Level 3: strip echoed prefixes
        String cleaned = stripEchoes(completion, prefix, typedOnLine);
        if (cleaned.isBlank()) return null;

        // Level 2: block duplicate check
        if (isBlockDuplicate(cleaned, prefix, suffix)) return null;

        // Level 1: per-line dedup
        return deduplicateLines(cleaned, prefix, suffix);
    }

    private static String normLine(String line) {
        return line.trim().replaceAll("\\s+", " ");
    }

    private static boolean isTrivial(String norm) {
        if (norm.length() < 4) return true;
        if (norm.matches("[{}()\\[\\];,]+")) return true;
        if (norm.matches("^}\\s*[;,]?$")) return true;
        if (norm.matches("^(//|/\\*|\\*|#).*")) return true;
        return false;
    }

    private static Set<String> buildLineSet(String text) {
        Set<String> set = new HashSet<>();
        for (String line : text.split("\n")) {
            String n = normLine(line);
            if (!isTrivial(n)) set.add(n);
        }
        return set;
    }

    private static String deduplicateLines(String completion, String prefix, String suffix) {
        Set<String> existing = buildLineSet(prefix + "\n" + suffix);
        String[] lines = completion.split("\n");

        List<String> nonTrivial = new ArrayList<>();
        for (String l : lines) if (!isTrivial(normLine(l))) nonTrivial.add(normLine(l));
        if (nonTrivial.isEmpty()) return null;

        long dups = nonTrivial.stream().filter(existing::contains).count();
        if ((double) dups / nonTrivial.size() > 0.5) return null;

        List<String> filtered = new ArrayList<>();
        for (String line : lines) {
            String n = normLine(line);
            if (isTrivial(n) || !existing.contains(n)) filtered.add(line);
        }
        String result = String.join("\n", filtered).trim();
        return result.isBlank() ? null : result;
    }

    private static boolean isBlockDuplicate(String completion, String prefix, String suffix) {
        String normC = normaliseBlock(completion);
        if (normC.length() < 20) return false;
        return normaliseBlock(prefix).contains(normC) || normaliseBlock(suffix).contains(normC);
    }

    private static String normaliseBlock(String text) {
        return Arrays.stream(text.split("\n"))
            .map(String::trim).filter(l -> !l.isEmpty())
            .reduce("", (a, b) -> a + " " + b)
            .replaceAll("\\s+", " ").toLowerCase().trim();
    }

    private static String stripEchoes(String completion, String prefix, String typedOnLine) {
        String result = completion;
        String typed = typedOnLine == null ? "" : typedOnLine.strip();
        if (!typed.isEmpty()) {
            String compTrimmed = result.stripLeading();
            if (compTrimmed.startsWith(typed)) {
                int wsLen = result.length() - result.stripLeading().length();
                result = result.substring(0, wsLen) + compTrimmed.substring(typed.length());
            }
        }
        // Strip last 3 prefix lines if echoed at top of completion
        String[] prefLines = Arrays.stream(prefix.split("\n"))
            .map(String::trim).filter(l -> !isTrivial(normLine(l)))
            .toArray(String[]::new);
        String[] compLines = result.split("\n");
        int strip = 0;
        for (int i = 0; i < Math.min(compLines.length, Math.min(3, prefLines.length)); i++) {
            String cn = normLine(compLines[i]);
            if (!isTrivial(cn) && Arrays.asList(prefLines).contains(cn)) strip = i + 1;
            else break;
        }
        if (strip > 0) {
            result = String.join("\n", Arrays.copyOfRange(compLines, strip, compLines.length));
        }
        result = result.replaceAll("^\n+", "");
        return result;
    }
}
