package com.llmcopilot.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs git in the project directory and hands back what it printed. */
public final class Git {

    private Git() {}

    /** Stdout of {@code git <args>}; throws with git's own message when it fails. */
    public static String run(String dir, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process proc = new ProcessBuilder(command).directory(new File(dir)).start();
        // Drained in parallel, so a large diff cannot fill the pipe and stall git.
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> {
            try { return new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (IOException e) { return ""; }
        });
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("git " + args[0] + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (proc.exitValue() != 0) throw new IOException(err.join().trim());
        return out;
    }

    /** Like {@link #run} but "" instead of an exception. */
    public static String quiet(String dir, String... args) {
        try { return run(dir, args); } catch (IOException e) { return ""; }
    }

    /** Staged and unstaged changes against HEAD, or the working tree diff in a repository with no commits. */
    public static String uncommittedDiff(String dir) {
        String diff = quiet(dir, "diff", "HEAD", "--no-color", "-U3");
        return diff.isEmpty() ? quiet(dir, "diff", "--no-color", "-U3") : diff;
    }

    /** The branch a pull request from here would most likely target. */
    public static String baseBranch(String dir) {
        String head = quiet(dir, "symbolic-ref", "--short", "refs/remotes/origin/HEAD").trim();
        if (!head.isEmpty()) return head;
        for (String candidate : new String[] { "origin/main", "origin/master", "main", "master" }) {
            try { run(dir, "rev-parse", "--verify", "--quiet", candidate); return candidate; }
            catch (IOException ignored) { }
        }
        return "HEAD~1";
    }
}
