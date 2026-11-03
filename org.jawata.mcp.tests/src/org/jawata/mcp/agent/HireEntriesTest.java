package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S9 (E3, D46): a call to the engine's {@code hire} tool is a hire, and the agent shows it to the host as an entry carrying the
 * seat, the task, the state, the time it ran, what it spent and who hired it. The entry is read from the call and from the hire's own
 * answer, never estimated.
 */
class HireEntriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicLong now = new AtomicLong(1_000);
    private final HireWatch watch = new HireWatch(now::get);

    private static ObjectNode hireCall(String kind, String id, String status) {
        ObjectNode update = JSON.createObjectNode().put("sessionUpdate", kind).put("toolCallId", id).put("status", status);
        update.put("title", "mcp__jawata-javata-dev__hire");
        return update;
    }

    private static ObjectNode withInput(ObjectNode update, String hirer, String seat, String brief) {
        update.putObject("rawInput").put("hirer", hirer).put("seat", seat).put("brief", brief);
        return update;
    }

    private static ObjectNode answered(ObjectNode update, String answerJson) {
        update.put("status", "completed");
        update.put("rawOutput", answerJson);
        return update;
    }

    @Test
    @DisplayName("a hire is read from its call: running with the seat and the brief, then done with the time it ran, the tokens it spent and the cost it sent")
    void aHireIsReadFromItsCallAndItsAnswer() {
        HireEntry started = watch.observe(hireCall("tool_call", "t1", "pending")).orElseThrow();
        assertEquals(HireEntry.RUNNING, started.state());
        HireEntry named = watch.observe(withInput(hireCall("tool_call_update", "t1", "in_progress"), "pm", "chief-engineer", "build the stage")).orElseThrow();
        assertEquals("chief-engineer", named.seat());
        assertEquals("build the stage", named.task());

        now.addAndGet(4_000);
        HireEntry done = watch.observe(answered(hireCall("tool_call_update", "t1", "completed"),
            "{\"success\":true,\"data\":{\"cleanExit\":true,\"timedOut\":false,\"tokens\":{\"input\":100,\"cached\":0,\"output\":50,\"reasoning\":10},\"costUsd\":0.5}}")).orElseThrow();

        assertEquals(HireEntry.DONE, done.state());
        assertEquals(4_000, done.elapsedMs());
        assertEquals(160, done.tokens());
        assertEquals(0.5, done.costUsd().getAsDouble());
        now.addAndGet(9_000);
        assertEquals(4_000, watch.entry("t1").orElseThrow().elapsedMs(), "a finished hire's time kept running");
    }

    @Test
    @DisplayName("a cost the brain did not send is absent and not 0, and a hire that timed out or whose call failed is failed")
    void aMissingCostIsAbsentAndAFailureIsFailed() {
        watch.observe(withInput(hireCall("tool_call", "t1", "pending"), "main", "architect", "review"));
        HireEntry noCost = watch.observe(answered(hireCall("tool_call_update", "t1", "completed"),
            "{\"data\":{\"cleanExit\":true,\"tokens\":\"NOT REPORTED BY THIS BRAIN\",\"costUsd\":\"NOT PUBLISHED BY THIS BRAIN\"}}")).orElseThrow();
        assertTrue(noCost.costUsd().isEmpty());
        assertFalse(noCost.toMeta().has("costUsd"), "a missing cost was sent on the wire: " + noCost.toMeta());
        assertEquals(0, noCost.tokens());

        watch.observe(withInput(hireCall("tool_call", "t2", "pending"), "main", "ux", "look"));
        HireEntry timedOut = watch.observe(answered(hireCall("tool_call_update", "t2", "completed"),
            "{\"data\":{\"cleanExit\":false,\"timedOut\":true}}")).orElseThrow();
        assertEquals(HireEntry.FAILED, timedOut.state());

        watch.observe(withInput(hireCall("tool_call", "t3", "pending"), "main", "profiler", "measure"));
        assertEquals(HireEntry.FAILED, watch.observe(hireCall("tool_call_update", "t3", "failed")).orElseThrow().state());
    }

    @Test
    @DisplayName("the brain's own session id is on a hire's entry once the hire has answered, and absent while it runs and when the brain gave none")
    void theBrainsSessionIdIsReadFromTheAnswer() {
        watch.observe(withInput(hireCall("tool_call", "t1", "pending"), "main", "architect", "review"));
        HireEntry running = watch.entry("t1").orElseThrow();
        HireEntry done = watch.observe(answered(hireCall("tool_call_update", "t1", "completed"),
            "{\"data\":{\"cleanExit\":true,\"brainSessionId\":\"sess-42\"}}")).orElseThrow();

        watch.observe(withInput(hireCall("tool_call", "t2", "pending"), "main", "ux", "look"));
        HireEntry none = watch.observe(answered(hireCall("tool_call_update", "t2", "completed"),
            "{\"data\":{\"cleanExit\":true}}")).orElseThrow();

        assertEquals(Optional.empty(), running.brainSessionId());
        assertEquals(Optional.of("sess-42"), done.brainSessionId());
        assertEquals("sess-42", done.toMeta().path("brainSessionId").asText());
        assertEquals(Optional.empty(), none.brainSessionId());
        assertFalse(none.toMeta().has("brainSessionId"), "a missing session id was sent on the wire: " + none.toMeta());
    }

    @Test
    @DisplayName("a hire made by a hired seat is a child of that seat's running hire, a hire made by main is a root, and a call that is not a hire is not read")
    void theHierarchyIsRead() {
        watch.observe(withInput(hireCall("tool_call", "pm1", "pending"), "main", "pm", "run the sprint"));
        HireEntry child = watch.observe(withInput(hireCall("tool_call", "ce1", "pending"), "pm", "chief-engineer", "build")).orElseThrow();
        HireEntry root = watch.entry("pm1").orElseThrow();

        assertEquals(Optional.of("pm1"), child.parentPlanId());
        assertEquals(Optional.empty(), root.parentPlanId());
        assertFalse(root.toMeta().has("parentPlanId"));

        ObjectNode other = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "x1").put("title", "Read a.txt");
        assertTrue(watch.observe(other).isEmpty());
        assertEquals(2, watch.entries().size());
    }

    @Test
    @DisplayName("on the running server a hire call reaches the host stamped with its entry, and a call that is not a hire reaches it plain")
    void theServerStampsTheEntryOnTheForwardedCall() {
        List<JsonNode> said = new ArrayList<>();
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                toolCalls.accept(withInput(hireCall("tool_call", "t1", "pending"), "main", "architect", "review the design"));
                toolCalls.accept(JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "r1").put("title", "Read a.txt"));
                chunks.accept("ok");
            }
        };
        AcpServer server = new AcpServer(brain, List::of, m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(), AgentRecorder.off(), null, null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "go");
        said.clear();

        server.receive(JsonRpcMessage.request(2, "session/prompt", params));

        List<JsonNode> calls = said.stream().map(m -> m.path("params").path("update"))
            .filter(u -> "tool_call".equals(u.path("sessionUpdate").asText())).toList();
        assertEquals(2, calls.size());
        JsonNode hire = calls.get(0).path("_meta").path("jawata/hire");
        assertEquals("architect", hire.path("seat").asText());
        assertEquals("review the design", hire.path("task").asText());
        assertEquals("running", hire.path("state").asText());
        assertFalse(calls.get(1).has("_meta"), "a call that is not a hire was stamped: " + calls.get(1));
    }
}
