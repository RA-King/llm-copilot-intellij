package com.llmcopilot.review;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Credentials written into code in plain text. Matched by the formats
 * providers give their keys, which is precise enough to report without a
 * model; a password assigned in code is matched by shape, and only when the
 * value is not a placeholder or a lookup. Mirrors secretScan.ts.
 *
 * Used by code review, on the lines a change adds, and by the agent, on what
 * it writes.
 */
public final class SecretScan {

    private SecretScan() {}

    /** {@code line} is one-based; {@code file} is set for a diff and null otherwise. */
    public record Finding(String file, int line, String kind, String preview) {}

    public record Hit(String kind, String preview) {}

    /** {@code group} holds the secret itself; 0 for the whole match. */
    private record Rule(String kind, Pattern pattern, int group) {
        Rule(String kind, String regex) { this(kind, Pattern.compile(regex), 0); }
    }

    private static final List<Rule> RULES = List.of(
        new Rule("private key", "-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY(?: BLOCK)?-----"),
        new Rule("AWS access key", "\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
        new Rule("GitHub token", "\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,})\\b"),
        new Rule("GitLab token", "\\bglpat-[A-Za-z0-9_-]{20,}\\b"),
        new Rule("Anthropic API key", "\\bsk-ant-[A-Za-z0-9_-]{32,}"),
        new Rule("OpenAI API key", "\\bsk-(?:proj-|svcacct-|admin-)?[A-Za-z0-9_-]{20,}T3BlbkFJ[A-Za-z0-9_-]{20,}|\\bsk-proj-[A-Za-z0-9_-]{40,}"),
        new Rule("Google API key", "\\bAIza[0-9A-Za-z_-]{35}\\b"),
        new Rule("Slack token", "\\bxox[abposr]-[A-Za-z0-9-]{10,}"),
        new Rule("Slack webhook", "https://hooks\\.slack\\.com/services/T[A-Za-z0-9]+/B[A-Za-z0-9]+/[A-Za-z0-9]{20,}"),
        new Rule("Stripe secret key", "\\b(?:sk|rk)_live_[A-Za-z0-9]{20,}\\b"),
        new Rule("npm token", "\\bnpm_[A-Za-z0-9]{36}\\b"),
        new Rule("Hugging Face token", "\\bhf_[A-Za-z0-9]{34,}\\b"),
        new Rule("SendGrid API key", "\\bSG\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}\\b"),
        new Rule("JSON web token", "\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
        new Rule("password in a connection string",
            Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s:/@'\"]+:([^\\s@/'\"]{4,})@[^\\s'\"]+"), 1),
        new Rule("hard-coded password",
            Pattern.compile("(?i)(?<![A-Za-z0-9])(?:password|passwd|pwd|secret|api_?key|access_?token|auth_?token|client_?secret)"
                + "[\"']?\\s*[:=]\\s*[\"']([^\"'\\s]{8,})[\"']"), 1)
    );

    /** Values that stand in for a secret rather than being one. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
        "(?i)^(?:x+|\\*+|\\.+|<[^>]*>|\\$\\{[^}]*}|\\{\\{[^}]*}}|%[^%]*%|\\$[A-Z_][A-Z0-9_]*"
            + "|.*(?:example|placeholder|changeme|change_me|your[_-]?|dummy|redacted|xxxx|test|sample|fake).*)$");

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    public static String mask(String value) {
        if (value.length() <= 8) return "*".repeat(value.length());
        return value.substring(0, 4) + "*".repeat(Math.min(value.length() - 6, 20)) + value.substring(value.length() - 2);
    }

    /** Secrets on one line of code, at most one per rule. */
    public static List<Hit> scanLine(String text) {
        List<Hit> found = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            if (!m.find()) continue;
            String value = m.group(rule.group());
            if (rule.group() > 0 && PLACEHOLDER.matcher(value).matches()) continue;
            found.add(new Hit(rule.kind(), rule.kind().equals("private key") ? m.group() : mask(value)));
        }
        return found;
    }

    public static List<Finding> scanText(String text, int firstLine) {
        List<Finding> findings = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Hit hit : scanLine(lines[i])) findings.add(new Finding(null, firstLine + i, hit.kind(), hit.preview()));
        }
        return findings;
    }

    /** Secrets on the lines a unified diff adds, with each line's number in the new file. */
    public static List<Finding> scanDiff(String diff) {
        List<Finding> findings = new ArrayList<>();
        String file = null;
        int newLine = 0;
        boolean inHunk = false;

        for (String line : diff.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("diff --git")) { inHunk = false; file = null; continue; }
            if (!inHunk && line.startsWith("+++ ")) {
                String target = line.substring(4).replaceFirst("^b/", "");
                file = target.equals("/dev/null") ? null : target;
                continue;
            }
            Matcher hunk = HUNK.matcher(line);
            if (hunk.find()) { newLine = Integer.parseInt(hunk.group(1)); inHunk = true; continue; }
            if (!inHunk) continue;

            if (line.startsWith("+")) {
                for (Hit hit : scanLine(line.substring(1))) findings.add(new Finding(file, newLine, hit.kind(), hit.preview()));
                newLine++;
            } else if (line.startsWith(" ")) {
                newLine++;
            }
        }
        return findings;
    }

    /** One sentence for a finding, the same wherever it is shown. */
    public static String describe(Finding finding) {
        return "Possible " + finding.kind() + " in plain text (" + finding.preview() + "). "
            + "Move it out of the code, into an environment variable or a secret store, and revoke it if it has been shared.";
    }

    /** As review findings, so they show alongside the model's. */
    public static List<CodeReview.Finding> asReviewFindings(List<Finding> found) {
        return found.stream()
            .map(f -> new CodeReview.Finding(f.file(), f.line(), CodeReview.Severity.ERROR, describe(f)))
            .toList();
    }
}
