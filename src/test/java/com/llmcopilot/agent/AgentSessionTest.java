package com.llmcopilot.agent;

import com.google.gson.JsonObject;
import com.llmcopilot.agent.AgentSession.Outcome;
import com.llmcopilot.agent.AgentSession.Result;
import com.llmcopilot.agent.AgentSession.Status;
import com.llmcopilot.services.LLMClient.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the agent loop with a scripted model and fake tools. Mirrors agentSession.test.ts. */
class AgentSessionTest {

    /** A model that replies from a script, recording what it was sent. */
    private static final class Scripted implements AgentSession.Model {
        final Deque<String> replies;
        final List<List<ChatMessage>> seen = new ArrayList<>();

        Scripted(String... replies) { this.replies = new ArrayDeque<>(List.of(replies)); }

        @Override public String reply(List<ChatMessage> messages, java.util.function.Consumer<String> onDelta,
                                      java.util.function.BooleanSupplier cancelled) throws Exception {
            seen.add(new ArrayList<>(messages));
            if (replies.isEmpty()) throw new Exception("script ran out");
            String next = replies.removeFirst();
            onDelta.accept(next);
            return next;
        }

        String lastSent(int turn) {
            List<ChatMessage> messages = seen.get(turn);
            return messages.get(messages.size() - 1).content();
        }
    }

    private static final class Fake implements AgentSession.Tools {
        final List<String> calls = new ArrayList<>();
        final Function<AgentProtocol.ToolCall, Outcome> handler;

        Fake(Function<AgentProtocol.ToolCall, Outcome> handler) { this.handler = handler; }

        @Override public Outcome run(AgentProtocol.ToolCall call, java.util.function.BooleanSupplier cancelled) {
            calls.add(call.name());
            return handler.apply(call);
        }
    }

    private static final class Recorder implements AgentSession.Observer {
        final List<String> events = new ArrayList<>();
        @Override public void turnStarted(int step) { events.add("turn " + step); }
        @Override public void turnText(String text) { }
        @Override public void toolStarted(AgentProtocol.ToolCall call) { events.add("start " + call.name()); }
        @Override public void toolFinished(AgentProtocol.ToolCall call, Outcome outcome) {
            events.add((outcome.ok() ? "ok " : "failed ") + call.name());
        }
    }

    private static final AgentSession.Options OPTIONS = new AgentSession.Options(10, 100_000, "env");

    private static String call(String name, String json) {
        return "Doing it.\n<tool name=\"" + name + "\">\n" + json + "\n</tool>";
    }

    private static Fake ok() { return new Fake(c -> new Outcome(true, "", "")); }

    @Test void callsToolsUntilTheModelAnswersWithoutOne() {
        Scripted model = new Scripted(
            call("read_file", "{\"path\": \"a.ts\"}"),
            call("edit_file", "{\"path\": \"a.ts\", \"search\": \"x\", \"replace\": \"y\"}"),
            "Changed x to y in a.ts.");
        Fake tools = new Fake(c -> new Outcome(true, "did " + c.name(), c.name()));
        Recorder observer = new Recorder();

        Result result = AgentSession.run("rename x", model, tools, observer, OPTIONS, () -> false);

        assertEquals(new Result(Status.DONE, "Changed x to y in a.ts.", 3), result);
        assertEquals(List.of("read_file", "edit_file"), tools.calls);
        assertEquals(List.of("turn 1", "start read_file", "ok read_file", "turn 2", "start edit_file", "ok edit_file", "turn 3"),
            observer.events);
        assertEquals("Result of read_file:\ndid read_file", model.lastSent(1));
        assertEquals("rename x", model.seen.get(0).get(1).content());
    }

    @Test void marksFailedResultsAndKeepsGoing() {
        Scripted model = new Scripted(call("edit_file", "{\"path\": \"a.ts\"}"), "Could not edit.");
        AgentSession.run("t", model, new Fake(c -> new Outcome(false, "not found", "failed")), new Recorder(), OPTIONS, () -> false);
        assertEquals("Result of edit_file (failed):\nnot found", model.lastSent(1));
    }

    @Test void turnsAToolThatThrowsIntoAFailedResult() {
        Scripted model = new Scripted(call("read_file", "{}"), "ok");
        AgentSession.Tools throwing = (c, cancelled) -> { throw new IllegalArgumentException("read_file needs \"path\""); };
        AgentSession.run("t", model, throwing, new Recorder(), OPTIONS, () -> false);
        assertTrue(model.lastSent(1).contains("Error: read_file needs \"path\""));
    }

    @Test void asksAgainAfterAGarbledCallAndGivesUpAfterThreeInARow() {
        String garbled = "<tool name=\"read_file\">{nope</tool>";
        Scripted retried = new Scripted(garbled, call("list_files", "{}"), "done");
        assertEquals(Status.DONE, AgentSession.run("t", retried, ok(), new Recorder(), OPTIONS, () -> false).status());
        assertTrue(retried.lastSent(1).contains("not valid JSON"));

        Scripted hopeless = new Scripted(garbled, garbled, garbled);
        assertEquals(Status.ERROR, AgentSession.run("t", hopeless, ok(), new Recorder(), OPTIONS, () -> false).status());
    }

    @Test void stopsAtTheStepLimit() {
        Scripted model = new Scripted(call("list_files", "{}"), call("list_files", "{}"), call("list_files", "{}"));
        Result result = AgentSession.run("t", model, ok(), new Recorder(), new AgentSession.Options(3, 100_000, "env"), () -> false);
        assertEquals(Status.STEP_LIMIT, result.status());
        assertEquals(3, result.steps());
    }

    @Test void stopsWhenCancelledDuringATool() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Scripted model = new Scripted(call("run_command", "{\"command\": \"gradle test\"}"), "never reached");
        Fake tools = new Fake(c -> { cancelled.set(true); return new Outcome(false, "", ""); });
        assertEquals(Status.STOPPED, AgentSession.run("t", model, tools, new Recorder(), OPTIONS, cancelled::get).status());
        assertEquals(1, model.seen.size());
    }

    @Test void reportsAModelFailureAsAnError() {
        assertEquals(new Result(Status.ERROR, "script ran out", 1),
            AgentSession.run("t", new Scripted(), ok(), new Recorder(), OPTIONS, () -> false));
    }

    @Test void argumentsArriveAsAJsonObject() {
        Scripted model = new Scripted(call("read_file", "{\"path\": \"a.ts\"}"), "done");
        List<JsonObject> args = new ArrayList<>();
        AgentSession.run("t", model, (c, cancelled) -> { args.add(c.args()); return new Outcome(true, "", ""); },
            new Recorder(), OPTIONS, () -> false);
        assertEquals("a.ts", args.get(0).get("path").getAsString());
    }
}
