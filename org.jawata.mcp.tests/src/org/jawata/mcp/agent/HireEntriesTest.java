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

    private final List<Thread> registered = new ArrayList<>();
    private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

    @org.junit.jupiter.api.AfterEach
    void endTheRegisteredHires() throws InterruptedException {
        release.countDown();
        for (Thread thread : registered) {
            thread.join(5_000);
        }
    }

    /** A hire the engine's registry holds as running, asked for by this caller and with this plan id of its own: who hired it is read from this, not from a seat name. */
    private void issued(String seat, String brief, String callerId, String planId) throws InterruptedException {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (org.jawata.mcp.execution.HireRegistry.Registration ignored = org.jawata.mcp.execution.HireRegistry.shared().begin(seat, brief, callerId)) {
                org.jawata.mcp.execution.HireRegistry.shared().planOpened(planId);
                started.countDown();
                release.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        registered.add(thread);
        thread.start();
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "the hire did not register as running");
    }

    @Test
    @DisplayName("28h D57 (i), 28h-C27-9: a hire call the engine refused (no plan opened) leaves no card at all and is reported as refused, so the chat takes back the card it drew from the call's input; a running hire's card stays")
    void aRefusedHireCallLeavesNoWorkingCard() {
        watch.observe(withInput(hireCall("tool_call", "r1", "pending"), "main", "pm", "plan it"));
        java.util.Optional<HireEntry> refused = watch.observe(answered(hireCall("tool_call_update", "r1", "completed"),
            "{\"success\":false,\"error\":{\"code\":\"ACCESS_DENIED\",\"message\":\"the working directory is outside the workspace\"}}"));
        boolean toldRefused = watch.takeRefused("r1");
        boolean toldTwice = watch.takeRefused("r1");
        watch.observe(withInput(hireCall("tool_call", "r2", "pending"), "main", "pm", "plan it"));
        HireEntry running = watch.observe(answered(hireCall("tool_call_update", "r2", "completed"),
            "{\"success\":true,\"data\":{\"hireId\":\"plan-1\",\"name\":\"Rafael\",\"state\":\"running\",\"hirer\":\"main\",\"hired\":\"pm\"}}")).orElseThrow();

        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertTrue(refused.isEmpty(), "a refused hire call named a seat and was still shown as a card: " + refused),
            () -> assertTrue(toldRefused, "the refusal was not reported, so the chat keeps the card it drew from the call's input"),
            () -> assertTrue(!toldTwice, "a refusal is reported once"),
            () -> assertTrue(watch.entry("r1").isEmpty(), "a refused call's card is still listed"),
            () -> assertTrue(!watch.takeRefused("r2"), "THE CONTROL: a hire that opened its plan was reported as refused"),
            () -> assertEquals(HireEntry.RUNNING, running.state(), "THE CONTROL: a hire that opened its plan runs until the engine says it ended"));
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
    @DisplayName("28h Stage 27 (z14) rule 2: while a hire runs, its card shows the brain and model the Seats page stores for the seat, above the seat file's own; with nothing stored it shows the seat file's")
    void aRunningHiresCardShowsTheSeatsPagesChoice(@TempDir java.nio.file.Path dir) throws Exception {
        HireWatch stored = new HireWatch(now::get);
        stored.useSeatBrains(() -> "pm=claude-code/Haiku 5.5");
        HireWatch none = new HireWatch(now::get);
        java.nio.file.Path file = Files.writeString(dir.resolve("pm.md"), "---\nname: pm\nbrain: cursor\nmodel: gpt-5\n---\n\nbody\n");
        ObjectNode onThePage = withInput(hireCall("tool_call", "s1", "pending"), "main", "pm", "plan it");
        ((ObjectNode) onThePage.get("rawInput")).put("seatFile", file.toString());
        ObjectNode withNothing = withInput(hireCall("tool_call", "s2", "pending"), "main", "pm", "plan it");
        ((ObjectNode) withNothing.get("rawInput")).put("seatFile", file.toString());

        HireEntry shown = stored.observe(onThePage).orElseThrow();
        assertEquals(Optional.of("Haiku 5.5"), shown.model(), "the seat file's model was shown above the Seats page's");
        assertEquals(Optional.of("claude-code"), shown.brain(), "the seat file's brain was shown above the Seats page's, or under another name than the menu and the ended card use");
        assertEquals(Optional.of("gpt-5"), none.observe(withNothing).orElseThrow().model(), "CONTROL: with nothing stored the seat file's model is the card's");
    }

    @Test
    @DisplayName("S17: a hire as Cursor's agent streams it is seen — the call is unnamed at first, a later update names it \"jawata: hire\" with the arguments under rawInput.args, and the echo that ends the call holds no data and does not end the card")
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
        // The echo holds no data, and the engine kept no answer for this hire: it is still running, and the engine's own event ends the card.
        assertEquals(HireEntry.RUNNING, ended.state());
    }

    @Test
    @DisplayName("S17: when the brain's echo of a hire carries no answer, the entry takes the answer the engine kept — a refusal is failed, and tokens and cost come through")
    void aHireWithNoEchoedAnswerTakesTheEnginesOwn() throws Exception {
        ObjectNode refused = JSON.createObjectNode();
        refused.put("cleanExit", false).put("error", "steps cannot be empty");
        org.jawata.mcp.execution.HireRegistry.shared().record("echo-less-seat", "refused brief", refused);
        ObjectNode spent = JSON.createObjectNode();
        spent.put("cleanExit", true).put("answer", "ok").put("costUsd", 0.04).putObject("tokens").put("input", 100).put("output", 20);
        org.jawata.mcp.execution.HireRegistry.shared().record("echo-less-seat", "spent brief", spent);

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
        // 28h-C27-5: the call as first streamed names nobody, so there is no card yet; the card comes with the update that names the seat
        assertTrue(watch.observe(hireCall("tool_call", "t1", "pending")).isEmpty(), "a call naming nobody was shown as a card");
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

        try (org.jawata.mcp.execution.HireRegistry.Registration running =
                 org.jawata.mcp.execution.HireRegistry.shared().begin("developer", "build the stage", "")) {
            org.jawata.mcp.execution.HireRegistry.shared().noteToolCall("");
            org.jawata.mcp.execution.HireRegistry.shared().noteUsage(java.util.OptionalLong.of(4_000), java.util.OptionalDouble.of(0.25));
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
    @DisplayName("Stage 27 (o): an ended hire's card carries how its run used jawata, as the answer reported it, and none when the answer reported none")
    void anEndedHiresCardCarriesHowItUsedJawata() {
        watch.observe(withInput(hireCall("tool_call", "t1", "pending"), "main", "developer", "build the stage"));
        watch.observe(withInput(hireCall("tool_call", "t2", "pending"), "main", "ux", "look at the screen"));

        HireEntry used = watch.observe(answered(hireCall("tool_call_update", "t1", "completed"),
            "{\"data\":{\"cleanExit\":true,\"toolUse\":{\"observed\":true,\"jawata\":9,\"other\":2,"
                + "\"shellOnJava\":[{\"command\":\"grep x A.java\",\"declared\":\"no symbol tool reads a log line\"}]}}}")).orElseThrow();
        HireEntry bare = watch.observe(answered(hireCall("tool_call_update", "t2", "completed"),
            "{\"data\":{\"cleanExit\":true}}")).orElseThrow();

        com.fasterxml.jackson.databind.JsonNode use = used.toMeta().path("toolUse");
        assertEquals(9, use.path("jawata").asInt(-1), "THE CLAIM: the figures were not on the wire: " + used.toMeta());
        assertEquals("no symbol tool reads a log line", use.path("shellOnJava").get(0).path("declared").asText());
        assertFalse(bare.toMeta().has("toolUse"), "CONTROL: an answer that reported none was given a figure");
    }

    @Test
    @DisplayName("28h D57 (i): a running hire's transcript shows the calls its brain has made so far, by their titles, after its brief")
    void aRunningHiresTranscriptShowsItsCallsSoFar() {
        watch.observe(withInput(hireCall("tool_call", "t1", "pending"), "main", "developer", "build the stage"));
        try (org.jawata.mcp.execution.HireRegistry.Registration running =
                 org.jawata.mcp.execution.HireRegistry.shared().begin("developer", "build the stage", "")) {
            org.jawata.mcp.execution.HireRegistry.shared().noteToolCall("Read Foo.java");
            org.jawata.mcp.execution.HireRegistry.shared().noteToolCall("Edit Foo.java");

            List<HireWatch.Said> turns = watch.transcript("t1").orElseThrow();

            assertEquals(List.of("user", "tool", "tool"), turns.stream().map(HireWatch.Said::role).toList(), turns.toString());
            assertEquals(List.of("Read Foo.java", "Edit Foo.java"), turns.subList(1, 3).stream().map(HireWatch.Said::text).toList());
            assertEquals(2, watch.entry("t1").orElseThrow().toolSteps(), "each call is still counted");
        }
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

        // 28h D57 (i): a call that came back failed was refused, no hire exists for it, so it leaves no card
        watch.observe(withInput(hireCall("tool_call", "t3", "pending"), "main", "profiler", "measure"));
        assertTrue(watch.observe(hireCall("tool_call_update", "t3", "failed")).isEmpty(), "a refused call kept a card");
        assertTrue(watch.entry("t3").isEmpty(), "a refused call's card is still listed");
        // 28h-C27-5: a call that names no seat, as a brain streams it before its arguments come (or when they never do), has no agent to show
        assertTrue(watch.observe(hireCall("tool_call", "t4", "pending")).isEmpty(), "a call naming nobody was shown as a card");
        assertTrue(watch.observe(hireCall("tool_call_update", "t4", "failed")).isEmpty());
        // seen live 2026-11-19: the same call that names nobody, ended as a completed call whose answer is the engine's refusal
        watch.observe(hireCall("tool_call", "t5", "pending"));
        assertTrue(watch.observe(answered(hireCall("tool_call_update", "t5", "completed"), "{\"success\":false,\"error\":\"steps cannot be empty\"}")).isEmpty(),
            "a refused call naming nobody left a failed card");
        assertTrue(watch.entries().stream().noneMatch(entry -> entry.seat().isEmpty()), "a card with no seat is listed: " + watch.entries());
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
    void theHierarchyIsRead() throws Exception {
        issued("pm", "run the sprint", "entries-chat-1", "entries-plan-pm1");
        issued("chief-engineer", "build", "entries-plan-pm1", "entries-plan-ce1");
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
        AcpServer server = new AcpServer(brain, List::of, m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(), AgentRecorder.off(), null);
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
    @DisplayName("28h-C27-9: on the running server a hire call the engine refused reaches the host as a failed call with no hire in it, so the chat takes back the card it drew from the call's input")
    void theServerSaysARefusedHireCallFailedAndStampsNoCard() {
        List<JsonNode> said = new ArrayList<>();
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                toolCalls.accept(withInput(hireCall("tool_call", "n1", "pending"), "main", "architect", "review the design"));
                toolCalls.accept(answered(hireCall("tool_call_update", "n1", "completed"),
                    "{\"success\":false,\"error\":{\"code\":\"INVALID_PARAMETER\",\"message\":\"a hire with no gates proves nothing was checked\"}}"));
                chunks.accept("ok");
            }
        };
        AcpServer server = new AcpServer(brain, List::of, m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "go");
        said.clear();

        server.receive(JsonRpcMessage.request(2, "session/prompt", params));

        JsonNode last = said.stream().map(m -> m.path("params").path("update"))
            .filter(u -> "n1".equals(u.path("toolCallId").asText()) && "tool_call_update".equals(u.path("sessionUpdate").asText()))
            .reduce((first, second) -> second).orElseThrow();
        assertEquals("failed", last.path("status").asText(), "the refused call was forwarded as it came: " + last);
        assertFalse(last.path("_meta").has("jawata/hire"), "the refused call was stamped with a hire card: " + last);
    }

    @Test
    @DisplayName("a hire's level is how many hires sit above it: a root is 0, the chief engineer it hires 1, the developer under that 2; and its title names the seat, the brain and the model where known")
    void theLevelAndTheTitleAreRead() throws Exception {
        issued("pm", "run the sprint", "entries-chat-2", "entries-plan-pm2");
        issued("chief-engineer", "build", "entries-plan-pm2", "entries-plan-ce2");
        issued("developer", "edit", "entries-plan-ce2", "entries-plan-d2");
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

    @Test
    @DisplayName("C24 audit, as D57 (i) changed it: a call the engine refused leaves no card, and the same call asked again and accepted gets its own card, which the engine's events then follow")
    void theEventsOfAnAcceptedRetryDoNotLandOnTheRefusedCallsCard() {
        watch.observe(withInput(hireCall("tool_call", "refused1", "pending"), "main", "retry-seat", "retry brief for the audit"));
        assertTrue(watch.observe(hireCall("tool_call_update", "refused1", "failed")).isEmpty());
        watch.observe(withInput(hireCall("tool_call", "retry2", "pending"), "main", "retry-seat", "retry brief for the audit"));

        java.util.List<org.jawata.mcp.execution.HireRegistry.Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable unsubscribe = org.jawata.mcp.execution.HireRegistry.shared().subscribe(events::add);
        try (org.jawata.mcp.execution.HireRegistry.Registration running =
                 org.jawata.mcp.execution.HireRegistry.shared().begin("retry-seat", "retry brief for the audit", "audit-chat")) {
            org.jawata.mcp.execution.HireRegistry.shared().planOpened("audit-plan-retry");
            org.jawata.mcp.execution.HireRegistry.shared().record("retry-seat", "retry brief for the audit",
                JSON.createObjectNode().put("plan", "audit-plan-retry").put("name", "Ida").put("cleanExit", true).put("answer", "ok"));
        } finally {
            unsubscribe.run();
        }
        // The engine's two events, started and finished, in the order it told them.
        events.forEach(watch::engineEvent);

        assertTrue(watch.entry("refused1").isEmpty(), "the refused call has a card, or the retry's events made one for it");
        assertEquals(HireEntry.DONE, watch.entry("retry2").orElseThrow().state(), "the retry's card did not follow the engine to its end");
    }

    @Test
    @DisplayName("S24 (found live with Cursor): an echo that says only success does not end the card of a hire the engine still runs, and ends it once the engine has kept the hire's answer")
    void anEchoOfOnlySuccessLeavesARunningHireRunning() {
        watch.observe(withInput(hireCall("tool_call", "echo1", "pending"), "main", "echo-seat", "echo brief that no hire has answered"));

        HireEntry afterTheEcho = watch.observe(answered(hireCall("tool_call_update", "echo1", "completed"), "{\"success\":true}")).orElseThrow();
        assertEquals(HireEntry.RUNNING, afterTheEcho.state(), "the echo ended a card whose hire is still running");

        // THE CONTROL: the same echo once the engine holds the hire's answer ends the card, so the branch above is not just a card that never ends.
        org.jawata.mcp.execution.HireRegistry.shared().record("echo-seat", "echo brief that no hire has answered",
            JSON.createObjectNode().put("cleanExit", true).put("answer", "kept"));
        HireEntry afterTheAnswer = watch.observe(answered(hireCall("tool_call_update", "echo1", "completed"), "{\"success\":true}")).orElseThrow();
        assertEquals(HireEntry.DONE, afterTheAnswer.state());
    }

    @Test
    @DisplayName("28h z39: a hire entry built with a refusal carries it on the wire under refusal, one built without carries no such key, and the refused state is the text refused")
    void aRefusalIsOnTheWireOnlyWhenTheEntryHasOne() {
        HireEntry refused = new HireEntry("z1", "test-manager", Optional.empty(), Optional.empty(), HireEntry.REFUSED, 0, 0,
            java.util.OptionalDouble.empty(), "run the suite", Optional.empty(), Optional.empty(), Optional.empty(), 0, 0,
            java.util.OptionalLong.empty(), Optional.empty(), Optional.empty(), Optional.empty(), java.util.OptionalInt.empty(),
            Optional.empty(), Optional.of("hire the test-manager through the pm"));
        HireEntry plain = new HireEntry("z2", "test-manager", Optional.empty(), Optional.empty(), HireEntry.RUNNING, 0, 0,
            java.util.OptionalDouble.empty(), "run the suite", Optional.empty(), Optional.empty(), Optional.empty(), 0, 0,
            java.util.OptionalLong.empty(), Optional.empty(), Optional.empty(), Optional.empty(), java.util.OptionalInt.empty(),
            Optional.empty());

        assertEquals("hire the test-manager through the pm", refused.toMeta().path("refusal").asText(), "the refusal was not on the wire: " + refused.toMeta());
        assertEquals(Optional.of("hire the test-manager through the pm"), refused.refusal());
        assertFalse(plain.toMeta().has("refusal"), "CONTROL: an entry with no refusal was given one: " + plain.toMeta());
        assertTrue(plain.refusal().isEmpty());
        assertEquals("refused", HireEntry.REFUSED);
    }

    /** A seat file with this text, in a temp folder that goes when the test run ends. */
    private static java.nio.file.Path seatFile(String text) throws java.io.IOException {
        java.nio.file.Path dir = Files.createTempDirectory("hire-entries-seat");
        java.nio.file.Path file = Files.writeString(dir.resolve("test-manager.md"), text);
        file.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        return file;
    }

    /** The call a brain streams for a hire the engine answered with this refusal (no plan opened, success false). */
    private static ObjectNode refusedCall(String id, String brief, String seatFile) {
        ObjectNode call = withInput(hireCall("tool_call", id, "pending"), "main", "test-manager", brief);
        ((ObjectNode) call.get("rawInput")).put("seatFile", seatFile);
        return call;
    }

    private static ObjectNode refusalAnswer(String id) {
        return answered(hireCall("tool_call_update", id, "completed"),
            "{\"success\":false,\"error\":{\"code\":\"INVALID_PARAMETER\",\"message\":\"a test-manager is hired through the pm\"}}");
    }

    /** The answer the engine keeps for a hire it refused before any brain started. */
    private static void theEngineKeepsARefusal(String brief) {
        ObjectNode kept = JSON.createObjectNode();
        kept.put("cleanExit", false).put("refusedByEngine", true).put("error", "a test-manager is hired through the pm").put("hireThrough", "pm");
        org.jawata.mcp.execution.HireRegistry.shared().record("test-manager", brief, kept);
    }

    /** What the host is told, last, of the call with this id when a brain streams these updates through the running server. */
    private static JsonNode lastForwarded(String id, ObjectNode... calls) {
        List<JsonNode> said = new ArrayList<>();
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                for (ObjectNode call : calls) {
                    toolCalls.accept(call);
                }
                chunks.accept("ok");
            }
        };
        AcpServer server = new AcpServer(brain, List::of, m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "go");
        said.clear();

        server.receive(JsonRpcMessage.request(2, "session/prompt", params));

        return said.stream().map(m -> m.path("params").path("update"))
            .filter(u -> id.equals(u.path("toolCallId").asText()) && !u.path("sessionUpdate").asText().isEmpty())
            .reduce((first, second) -> second).orElseThrow();
    }

    @Test
    @DisplayName("28h z39: the card of a hire the engine refused before any brain started is refused, names no brain and no model though the seat file names them, and says to hire through the pm")
    void aHireTheEngineRefusedIsACardNamingWhyAndWhomToHireThrough() throws Exception {
        String brief = "run the whole suite, refused card";
        java.nio.file.Path file = seatFile("---\nname: test-manager\nbrain: cursor\nmodel: gpt-5\n---\n\nbody\n");
        watch.observe(refusedCall("z1", brief, file.toString()));
        assertEquals(Optional.of("cursor"), watch.entry("z1").orElseThrow().brain(), "CONTROL: the seat file names a brain, so an empty one below is the refusal's doing");
        theEngineKeepsARefusal(brief);

        HireEntry card = watch.observe(refusalAnswer("z1")).orElseThrow();

        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertEquals(HireEntry.REFUSED, card.state()),
            () -> assertTrue(card.brain().isEmpty(), "a refused hire was shown with a brain: " + card.brain()),
            () -> assertTrue(card.model().isEmpty(), "a refused hire was shown with a model: " + card.model()),
            () -> assertTrue(card.refusal().orElseThrow().contains("hire it through pm"), "the refusal does not name the seat to hire through: " + card.refusal()),
            () -> assertTrue(card.toMeta().path("refusal").asText().contains("a test-manager is hired through the pm"), "the engine's reason is not on the wire: " + card.toMeta()));
    }

    @Test
    @DisplayName("28h z39: on the running server a refused hire reaches the host as a completed call, not a failed one, with the state refused and the reason on its card")
    void aRefusedHireIsForwardedAsACompletedCallNotAFailedOne() throws Exception {
        String brief = "run the whole suite, refused on the wire";
        java.nio.file.Path file = seatFile("---\nname: test-manager\nbrain: cursor\nmodel: gpt-5\n---\n\nbody\n");
        theEngineKeepsARefusal(brief);

        JsonNode last = lastForwarded("z2", refusedCall("z2", brief, file.toString()), refusalAnswer("z2"));

        assertEquals("completed", last.path("status").asText(), "a refused hire was forwarded as a failed call: " + last);
        assertEquals("refused", last.path("_meta").path("jawata/hire").path("state").asText(), last.toString());
        assertTrue(last.path("_meta").path("jawata/hire").path("refusal").asText().contains("hire it through pm"), last.toString());
    }

    @Test
    @DisplayName("28h z39, CONTROL: a hire whose brain started and failed is still failed, with its brain on the card, and the host is told the call failed")
    void aHireWhoseBrainStartedAndFailedIsStillFailedOnTheCardAndTheWire() throws Exception {
        String brief = "run the whole suite, brain failed";
        java.nio.file.Path file = seatFile("---\nname: test-manager\nbrain: cursor\n---\n\nbody\n");
        ObjectNode failedAnswer = answered(hireCall("tool_call_update", "z3", "completed"),
            "{\"data\":{\"cleanExit\":false,\"brain\":{\"brain\":\"claude-code\",\"vendor\":\"anthropic\",\"model\":\"sonnet\"}}}");

        JsonNode last = lastForwarded("z3", refusedCall("z3", brief, file.toString()), failedAnswer);

        assertEquals("failed", last.path("status").asText(), last.toString());
        assertEquals("failed", last.path("_meta").path("jawata/hire").path("state").asText(), last.toString());
        assertEquals("claude-code", last.path("_meta").path("jawata/hire").path("brain").asText(), "the failed hire lost its brain: " + last);
        assertFalse(last.path("_meta").path("jawata/hire").has("refusal"), "a failed brain was given a refusal: " + last);
    }
}
