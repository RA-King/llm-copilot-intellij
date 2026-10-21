package com.llmcopilot.agent;

import com.llmcopilot.services.LLMClient.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The agent loop, with the model and the tools passed in. Nothing here knows
 * about the IDE, which is what lets the loop be tested with a scripted model
 * and fake tools. Mirrors agentSession.ts.
 */
public final class AgentSession {

    private AgentSession() {}

    /** {@code output} goes back to the model; {@code summary} is the one line the user sees. */
    public record Outcome(boolean ok, String output, String summary) {}

    public interface Tools {
        Outcome run(AgentProtocol.ToolCall call, BooleanSupplier cancelled) throws Exception;
    }

    public interface Model {
        /** Streams one reply; returns all of it. */
        String reply(List<ChatMessage> messages, Consumer<String> onDelta, BooleanSupplier cancelled) throws Exception;
    }

    public interface Observer {
        void turnStarted(int step);
        /** Visible text of the turn so far, already stripped of the call block. */
        void turnText(String text);
        void toolStarted(AgentProtocol.ToolCall call);
        void toolFinished(AgentProtocol.ToolCall call, Outcome outcome);
    }

    public enum Status { DONE, STOPPED, STEP_LIMIT, ERROR }

    public record Result(Status status, String summary, int steps) {}

    public record Options(int maxSteps, int historyBudget, String environment) {}

    public static Result run(String task, Model model, Tools tools, Observer observer,
                             Options options, BooleanSupplier cancelled) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", AgentProtocol.systemPrompt(options.environment())));
        messages.add(new ChatMessage("user", task));
        int malformed = 0;

        for (int step = 1; step <= options.maxSteps(); step++) {
            if (cancelled.getAsBoolean()) return new Result(Status.STOPPED, "", step - 1);
            observer.turnStarted(step);

            StringBuilder streamed = new StringBuilder();
            String reply;
            try {
                reply = model.reply(AgentProtocol.compact(messages, options.historyBudget()), delta -> {
                    streamed.append(delta);
                    observer.turnText(AgentProtocol.visibleText(streamed.toString()));
                }, cancelled);
            } catch (Exception e) {
                if (cancelled.getAsBoolean()) return new Result(Status.STOPPED, "", step);
                return new Result(Status.ERROR, e.getMessage() != null ? e.getMessage() : e.toString(), step);
            }
            if (cancelled.getAsBoolean()) return new Result(Status.STOPPED, AgentProtocol.visibleText(reply), step);

            AgentProtocol.Reply parsed = AgentProtocol.parse(reply);
            observer.turnText(parsed.text());
            messages.add(new ChatMessage("assistant", reply));

            if (parsed.error() != null) {
                // A garbled call gets two more chances before the run gives up on it.
                if (++malformed > 2) return new Result(Status.ERROR, parsed.error(), step);
                messages.add(new ChatMessage("user", parsed.error()));
                continue;
            }
            if (parsed.call() == null) return new Result(Status.DONE, parsed.text(), step);

            malformed = 0;
            AgentProtocol.ToolCall call = parsed.call();
            observer.toolStarted(call);
            Outcome outcome;
            try {
                outcome = tools.run(call, cancelled);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                outcome = new Outcome(false, "Error: " + msg, call.name() + " failed: " + msg);
            }
            observer.toolFinished(call, outcome);
            if (cancelled.getAsBoolean()) return new Result(Status.STOPPED, "", step);

            messages.add(new ChatMessage("user", "Result of " + call.name() + (outcome.ok() ? "" : " (failed)") + ":\n"
                + AgentProtocol.truncate(outcome.output(), 8000)));
            messages = AgentProtocol.compact(messages, options.historyBudget());
        }

        return new Result(Status.STEP_LIMIT, "Stopped after " + options.maxSteps()
            + " steps without finishing. Raise the agent step limit in the settings, or give it a narrower task.",
            options.maxSteps());
    }
}
