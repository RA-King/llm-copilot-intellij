package com.llmcopilot.errors;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.llmcopilot.settings.LLMCopilotSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Watches everything the IDE runs or debugs and keeps the output of anything
 * that ends badly.
 *
 * <p>A non-zero exit code is taken as a failure outright. A clean exit is only
 * kept when the output reads like one anyway, which is how test runners and
 * linters that swallow their status behave.
 *
 * <p>Registered as a project listener on the execution topic, so it costs
 * nothing until something is actually run.
 */
public final class RunOutputListener implements ExecutionListener {

    /** How much output is held per process before the front is dropped. */
    private static final int MAX_BUFFERED_CHARS = 60_000;

    @Override
    public void processStarted(@NotNull String executorId,
                               @NotNull ExecutionEnvironment env,
                               @NotNull ProcessHandler handler) {
        if (!LLMCopilotSettings.getInstance().isErrorAssistEnabled()) return;

        Project project = env.getProject();
        String source = DefaultDebugExecutor.EXECUTOR_ID.equals(executorId)
            ? CapturedError.DEBUG
            : CapturedError.RUN;
        String origin = env.getRunProfile().getName();

        StringBuilder buffer = new StringBuilder();

        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (outputType == ProcessOutputTypes.SYSTEM) return;
                synchronized (buffer) {
                    buffer.append(event.getText());
                    if (buffer.length() > MAX_BUFFERED_CHARS) {
                        buffer.delete(0, buffer.length() - MAX_BUFFERED_CHARS);
                    }
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                if (project.isDisposed()) return;
                if (!LLMCopilotSettings.getInstance().isErrorAssistEnabled()) return;

                String output;
                synchronized (buffer) {
                    output = buffer.toString();
                }
                if (output.isBlank()) return;

                int exitCode = event.getExitCode();
                if (exitCode == 0 && !ErrorParser.looksLikeError(output)) return;

                CapturedError captured = ErrorCaptureService.getInstance(project)
                    .record(source, origin, output, exitCode);
                if (captured != null) ErrorAssistant.offer(project, captured);
            }
        });
    }
}
