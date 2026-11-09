package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jawata.mcp.execution.FinishedHires;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    @DisplayName("the entry carries the slash command the hire's seat file names, on the entry and on the wire, and nothing when the file names none")
    void theSeatCommandIsReadFromTheSeatFile(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path named = Files.writeString(dir.resolve("named.md"), "---\nname: architect\ncommand: refactor\n---\n\nbody\n");
        java.nio.file.Path plain = Files.writeString(dir.resolve("plain.md"), "---\nname: architect\n---\n\nbody\n");
        ObjectNode withCommand = withInput(hireCall("tool_call", "t1", "pending"), "main", "architect", "review");
        ((ObjectNode) withCommand.get("rawInput")).put("seatFile", named.toString());
        ObjectNode without = withInput(hireCall("tool_call", "t2", "pending"), "main", "architect", "review");
        ((ObjectNode) without.get("rawInput")).put("seatFile", plain.toString());

        HireEntry shown = watch.observe(withCommand).orElseThrow();
        HireEntry bare = watch.observe(without).orElseThrow();

        assertEquals(Optional.of("/refactor"), shown.seatCommand());
        assertEquals("/refactor", shown.toMeta().path("seatCommand").asText());
        assertTrue(bare.seatCommand().isEmpty());
        assertFalse(bare.toMeta().has("seatCommand"), "a seat with no command was shown a placeholder");
    }

    @Test
    @DisplayName("the entry's model is the one the hire tool says ran, and the tool's wording for 'no model named' is not shown as a model")
    void theModelIsTheOneTheHireToolSaysRan(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path plain = Files.writeString(dir.resolve("plain.md"), "---\nname: pm\nbrain: cursor\n---\n\nbody\n");
        ObjectNode call = withInput(hireCall("tool_call", "m1", "pending"), "main", "pm", "plan it");
        ((ObjectNode) call.get("rawInput")).put("seatFile", plain.toString());
        assertTrue(watch.observe(call).orElseThrow().model().isEmpty(), "the seat file names none and nothing else was asked, so none is known yet");

        HireEntry done = watch.observe(answered(hireCall("tool_call_update", "m1", "completed"),
            "{\"cleanExit\":true,\"brain\":{\"brain\":\"cursor\",\"vendor\":\"Cursor\",\"model\":\"composer-2\"}}")).orElseThrow();
        assertEquals(Optional.of("composer-2"), done.model());
        assertEquals("composer-2", done.toMeta().path("model").asText());

        ObjectNode second = withInput(hireCall("tool_call", "m2", "pending"), "main", "pm", "again");
        ((ObjectNode) second.get("rawInput")).put("seatFile", plain.toString());
        watch.observe(second);
        HireEntry unnamed = watch.observe(answered(hireCall("tool_call_update", "m2", "completed"),
            "{\"cleanExit\":true,\"brain\":{\"brain\":\"cursor\",\"vendor\":\"Cursor\",\"model\":\"THE BRAIN'S OWN DEFAULT\"}}")).orElseThrow();
        assertTrue(unnamed.model().isEmpty(), "a sentence saying no model was named was shown as a model");
        assertFalse(unnamed.toMeta().has("model"));
    }

    @Test
    @DisplayName("D53 (i): after a Replace the card and its line name the brain that answered, not the one the seat file named")
    void theCardNamesTheBrainThatAnswered(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path plain = Files.writeString(dir.resolve("plain.md"), "---\nname: pm\nbrain: cursor\n---\n\nbody\n");
        ObjectNode call = withInput(hireCall("tool_call", "b1", "pending"), "main", "pm", "plan it");
        ((ObjectNode) call.get("rawInput")).put("seatFile", plain.toString());
        assertEquals("cursor", watch.observe(call).orElseThrow().toMeta().path("brain").asText());

        HireEntry done = watch.observe(answered(hireCall("tool_call_update", "b1", "completed"),
            "{\"cleanExit\":true,\"brain\":{\"brain\":\"claude-code\",\"vendor\":\"anthropic\",\"model\":\"THE BRAIN'S OWN DEFAULT\"}}")).orElseThrow();

        assertEquals("claude-code", done.toMeta().path("brain").asText());
        assertTrue(done.title().contains("claude-code"), done.title());
        assertFalse(done.title().contains("cursor"), done.title());
    }

    @Test
    @DisplayName("the card carries the engine's plan id from the hire's answer, the key the hire's journal row is written under, and nothing before the answer or when the answer names none")
    void theCardCarriesTheEnginePlanTheJournalIsKeyedBy() {
        HireEntry running = watch.observe(withInput(hireCall("tool_call", "e1", "pending"), "chief-engineer", "worker", "do it")).orElseThrow();
        assertTrue(running.enginePlan().isEmpty(), "no answer has named the plan yet");
        assertFalse(running.toMeta().has("enginePlan"));

        HireEntry done = watch.observe(answered(hireCall("tool_call_update", "e1", "completed"), "{\"cleanExit\":true,\"plan\":\"plan-eee\"}")).orElseThrow();
        assertEquals(Optional.of("plan-eee"), done.enginePlan());
        assertEquals("plan-eee", done.toMeta().path("enginePlan").asText());
        assertEquals("e1", done.planId(), "the card's own id stays the brain's tool call");

        watch.observe(withInput(hireCall("tool_call", "e2", "pending"), "chief-engineer", "worker", "again"));
        HireEntry unnamed = watch.observe(answered(hireCall("tool_call_update", "e2", "completed"), "{\"cleanExit\":true}")).orElseThrow();
        assertTrue(unnamed.enginePlan().isEmpty());
        assertFalse(unnamed.toMeta().has("enginePlan"), "an answer that names no plan was shown a placeholder");
    }

    @Test
    @DisplayName("a hire made to fix an earlier one marks the earlier one accepted-with-fix, found by the plan id the earlier hire's answer named")
    void aFixMarksTheEarlierHireAcceptedWithFix() {
        watch.observe(withInput(hireCall("tool_call", "f1", "pending"), "chief-engineer", "worker", "do it"));
        watch.observe(answered(hireCall("tool_call_update", "f1", "completed"), "{\"cleanExit\":true,\"plan\":\"plan-aaa\"}"));
        assertTrue(watch.entry("f1").orElseThrow().verdict().isEmpty(), "nobody has answered the first hire yet");

        ObjectNode fix = withInput(hireCall("tool_call", "f2", "pending"), "chief-engineer", "worker", "correct the two names");
        ((ObjectNode) fix.get("rawInput")).put("fixes", "plan-aaa");
        watch.observe(fix);

        assertEquals(Optional.of("accepted-with-fix"), watch.entry("f1").orElseThrow().verdict());
        assertTrue(watch.entry("f2").orElseThrow().verdict().isEmpty(), "the fix itself has no verdict yet");

        ObjectNode strayFix = withInput(hireCall("tool_call", "f3", "pending"), "chief-engineer", "worker", "fix something unknown");
        ((ObjectNode) strayFix.get("rawInput")).put("fixes", "plan-never-seen");
        watch.observe(strayFix);
        assertEquals(Optional.of("accepted-with-fix"), watch.entry("f1").orElseThrow().verdict(), "a plan id nobody knows marks nothing");
    }

    @Test
    @DisplayName("while a hire runs, a seat file that names no model shows the model the registry setting offers its brain, and a model the seat file names wins over it")
    void theRegistryModelFillsWhatTheSeatFileLeavesOut(@TempDir java.nio.file.Path dir) throws Exception {
        String registry = "{\"cursor\":{\"command\":[\"agent\",\"acp\"],\"vendor\":\"Cursor\",\"model\":\"composer-2\"}}";
        HireWatch registered = new HireWatch(now::get, () -> registry);
        java.nio.file.Path plain = Files.writeString(dir.resolve("plain.md"), "---\nname: pm\nbrain: cursor\n---\n\nbody\n");
        java.nio.file.Path named = Files.writeString(dir.resolve("named.md"), "---\nname: pm\nbrain: cursor\nmodel: gpt-5\n---\n\nbody\n");
        ObjectNode fromRegistry = withInput(hireCall("tool_call", "r1", "pending"), "main", "pm", "plan it");
        ((ObjectNode) fromRegistry.get("rawInput")).put("seatFile", plain.toString());
        ObjectNode fromFile = withInput(hireCall("tool_call", "r2", "pending"), "main", "pm", "plan it again");
        ((ObjectNode) fromFile.get("rawInput")).put("seatFile", named.toString());

        assertEquals(Optional.of("composer-2"), registered.observe(fromRegistry).orElseThrow().model());
        assertEquals(Optional.of("gpt-5"), registered.observe(fromFile).orElseThrow().model());
    }

    @Test
    @DisplayName("S17: a hire as Cursor's agent streams it is seen — the call is unnamed at first, a later update names it \"jawata: hire\" with the arguments under rawInput.args, and it ends with an answer that holds no data")
    void aHireAsCursorStreamsItIsSeen() throws Exception {
        ObjectNode first = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "c1").put("title", "MCP: tool").put("kind", "other").put("status", "pending");
        first.putObject("rawInput");
        ObjectNode named = JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "c1").put("title", "jawata: hire");
        ObjectNode wrapped = named.putObject("rawInput").put("providerIdentifier", "jawata").put("toolName", "hire");
        wrapped.putObject("args").put("hirer", "main").put("seat", "pm").put("brief", "Reply with the one word ok.");
        ObjectNode other = JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "c2").put("title", "jawata: experience");
        other.putObject("rawInput").put("providerIdentifier", "jawata").put("toolName", "experience").putObject("args").put("kind", "recall");
        ObjectNode done = JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "c1").put("status", "completed");
        done.putObject("rawOutput").put("success", true);

        assertTrue(watch.observe(first).isEmpty(), "a call with no name yet is not known to be a hire");
        HireEntry seen = watch.observe(named).orElseThrow();
        assertTrue(watch.observe(other).isEmpty(), "another tool of the same server is not a hire");
        HireEntry ended = watch.observe(done).orElseThrow();

        assertEquals("pm", seen.seat());
        assertEquals("Reply with the one word ok.", seen.task());
        assertEquals(HireEntry.RUNNING, seen.state());
        assertEquals(HireEntry.DONE, ended.state());
    }

    @Test
    @DisplayName("S17: when the brain's echo of a hire carries no answer, the entry takes the answer the engine kept — a refusal is failed, and tokens and cost come through")
    void aHireWithNoEchoedAnswerTakesTheEnginesOwn() throws Exception {
        ObjectNode refused = JSON.createObjectNode();
        refused.put("cleanExit", false).put("error", "steps cannot be empty");
        FinishedHires.shared().record("echo-less-seat", "refused brief", refused);
        ObjectNode spent = JSON.createObjectNode();
        spent.put("cleanExit", true).put("answer", "ok").put("costUsd", 0.04).putObject("tokens").put("input", 100).put("output", 20);
        FinishedHires.shared().record("echo-less-seat", "spent brief", spent);

        HireEntry failed = echoOnlySuccess("e1", "refused brief");
        HireEntry done = echoOnlySuccess("e2", "spent brief");

        assertEquals(HireEntry.FAILED, failed.state());
        assertEquals(HireEntry.DONE, done.state());
        assertEquals(120, done.tokens());
        assertEquals(0.04, done.costUsd().getAsDouble());
    }

    /** A hire that ends with the echo Cursor sends, {@code {"success": true}}, and nothing else. */
    private HireEntry echoOnlySuccess(String id, String brief) {
        ObjectNode start = JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", id).put("title", "jawata: hire");
        ObjectNode wrapped = start.putObject("rawInput").put("toolName", "hire");
        wrapped.putObject("args").put("hirer", "main").put("seat", "echo-less-seat").put("brief", brief);
        watch.observe(start);
        ObjectNode end = JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", id).put("status", "completed");
        end.putObject("rawOutput").put("success", true);
        return watch.observe(end).orElseThrow();
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
    @DisplayName("S9: a hire that is still running reports the time since it began, read at each request, and stops counting when it ends")
    void aRunningHiresTimeKeepsRunningUntilItEnds() {
        watch.observe(withInput(hireCall("tool_call", "t1", "pending"), "main", "chief-engineer", "build the stage"));

        now.addAndGet(3_000);
        assertEquals(3_000, watch.entry("t1").orElseThrow().elapsedMs(), "a running hire's time did not move with the clock");
        now.addAndGet(2_000);
        assertEquals(5_000, watch.entry("t1").orElseThrow().elapsedMs(), "the second reading did not include the time since the first");

        HireEntry done = watch.observe(answered(hireCall("tool_call_update", "t1", "completed"), "{\"success\":true,\"data\":{\"cleanExit\":true}}")).orElseThrow();
        now.addAndGet(7_000);
        assertEquals(5_000, done.elapsedMs());
        assertEquals(5_000, watch.entry("t1").orElseThrow().elapsedMs(), "control: an ended hire's time kept running");
    }

    @Test
    @DisplayName("S9: a running hire's entry carries the tool calls, the context size and the cost its brain has reported so far; the answer's own figures replace them when it ends")
    void aRunningHiresEntryCarriesWhatItsBrainHasReportedSoFar() {
        watch.observe(withInput(hireCall("tool_call", "t1", "pending"), "main", "developer", "build the stage"));
        watch.observe(withInput(hireCall("tool_call", "t2", "pending"), "main", "ux", "look at the screen"));
        HireEntry idle = watch.entry("t1").orElseThrow();

        try (org.jawata.mcp.execution.RunningHires.Registration running =
                 org.jawata.mcp.execution.RunningHires.shared().begin("developer", "build the stage")) {
            org.jawata.mcp.execution.RunningHires.shared().noteToolStep();
            org.jawata.mcp.execution.RunningHires.shared().noteUsage(java.util.OptionalLong.of(4_000), java.util.OptionalDouble.of(0.25));
            HireEntry working = watch.entry("t1").orElseThrow();

            assertEquals(1, working.toolSteps());
            assertEquals(0.25, working.costUsd().getAsDouble());
            assertEquals(4_000, working.contextTokens().getAsLong());
            assertEquals(4_000, working.toMeta().path("contextTokens").asLong(), "the figure was not on the wire");
            assertEquals(0, watch.entry("t2").orElseThrow().toolSteps(), "CONTROL: another seat's hire was given this hire's figures");
            assertFalse(watch.entry("t2").orElseThrow().toMeta().has("contextTokens"), "CONTROL: an unreported context size was sent");
        }
        assertEquals(0, idle.toolSteps(), "CONTROL: a hire nobody registered as running reported tool calls");
        assertTrue(idle.costUsd().isEmpty());

        HireEntry done = watch.observe(answered(hireCall("tool_call_update", "t1", "completed"),
            "{\"data\":{\"cleanExit\":true,\"toolSteps\":7,\"costUsd\":0.5}}")).orElseThrow();
        assertEquals(7, done.toolSteps(), "the answer's own count did not replace the running figure");
        assertEquals(0.5, done.costUsd().getAsDouble());
        assertTrue(done.contextTokens().isEmpty(), "a finished hire kept a context size from while it ran");
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
        // E3: a client with no jawata view shows the hire as one execute line naming the seat and its state; the plain call keeps its own title.
        assertEquals("execute", calls.get(0).path("kind").asText());
        assertEquals("architect — running", calls.get(0).path("title").asText());
        assertEquals("Read a.txt", calls.get(1).path("title").asText());
        assertFalse(calls.get(1).has("kind"), "a call that is not a hire was given a kind: " + calls.get(1));
    }

    @Test
    @DisplayName("a hire's level is how many hires sit above it: a root is 0, the chief engineer it hires 1, the developer under that 2; and its title names the seat, the brain and the model where known")
    void theLevelAndTheTitleAreRead() {
        watch.observe(withInput(hireCall("tool_call", "pm1", "pending"), "main", "pm", "run the sprint"));
        watch.observe(withInput(hireCall("tool_call", "ce1", "pending"), "pm", "chief-engineer", "build"));
        HireEntry developer = watch.observe(withInput(hireCall("tool_call", "d1", "pending"), "chief-engineer", "developer", "edit")).orElseThrow();

        assertEquals(0, watch.entry("pm1").orElseThrow().level());
        assertEquals(1, watch.entry("ce1").orElseThrow().level());
        assertEquals(2, developer.level());
        assertEquals(2, developer.toMeta().path("level").asInt());
        HireEntry named = new HireEntry("p", "test-writer", Optional.of("claude-code"), Optional.of("sonnet"), HireEntry.RUNNING, 0, 0,
            java.util.OptionalDouble.empty(), "t", Optional.empty());
        assertEquals("test-writer · claude-code · sonnet — running", named.title());
    }
}
