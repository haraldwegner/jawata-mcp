package org.jawata.mcp.java.dap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.spi.host.PersonDebugTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28i, Stage 25, step A2 (ARCHITECTURE-28i-s25.md): the person's debugger talks the debug adapter protocol, and the server turns what it
 * says into calls of the engine's {@code debug} tool as an editor caller. A fake tool registry stands in for the tool, so what is pinned here is
 * the translation: which call each request makes, what the person's turn is while it runs, and what the editor is told.
 */
class DapServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The debug tool as the server sees it: it records each call with the turn state and the caller at that moment, and answers by action. */
    private static final class FakeTools implements ToolCalls {

        final PersonDebugTurn turn;
        final List<JsonNode> calls = new CopyOnWriteArrayList<>();
        final List<Boolean> personDebugging = new CopyOnWriteArrayList<>();
        final List<CallerIdentity> callers = new CopyOnWriteArrayList<>();
        Function<JsonNode, ToolResponse> answer = arguments -> ToolResponse.success(Map.of("sessionId", "s-1"));
        boolean toolMissing;
        final Map<String, Function<JsonNode, ToolResponse>> byAction = new HashMap<>();

        FakeTools(PersonDebugTurn turn) {
            this.turn = turn;
        }

        @Override
        public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) throws ToolNotFoundException {
            if (toolMissing) {
                throw new ToolNotFoundException("no tool named " + name);
            }
            assertEquals("debug", name);
            calls.add(arguments);
            personDebugging.add(turn.personIsDebugging());
            callers.add(caller);
            Function<JsonNode, ToolResponse> own = byAction.get(arguments.path("action").asText());
            return (own != null ? own : answer).apply(arguments);
        }

        @Override
        public List<Map<String, Object>> getToolDefinitions() {
            return List.of();
        }
    }

    private final PersonDebugTurn turn = new PersonDebugTurn();
    private final FakeTools tools = new FakeTools(turn);
    private final List<JsonNode> said = new CopyOnWriteArrayList<>();
    private final BlockingQueue<ToolResponse> hits = new LinkedBlockingQueue<>();
    private final DapServer server = new DapServer(tools, CallerIdentity.editor("dap-test"), message -> said.add(message.getParams()), turn);
    private int seq;

    private void send(String command, ObjectNode arguments) {
        ObjectNode request = JSON.createObjectNode().put("seq", ++seq).put("type", "request").put("command", command);
        request.set("arguments", arguments == null ? JSON.createObjectNode() : arguments);
        server.receive(JsonRpcMessage.notification("dap", request));
    }

    private JsonNode responseTo(int requestSeq) {
        return said.stream().filter(m -> "response".equals(m.path("type").asText()) && m.path("request_seq").asInt() == requestSeq)
            .findFirst().orElseThrow(() -> new AssertionError("no response to request " + requestSeq + " in " + said));
    }

    private boolean saidEvent(String event) {
        return said.stream().anyMatch(m -> "event".equals(m.path("type").asText()) && event.equals(m.path("event").asText()));
    }

    @Test
    @DisplayName("initialize answers what the adapter can do and then says it is ready for the breakpoints")
    void initializeAnswersAndAnnouncesItself() {
        send("initialize", JSON.createObjectNode().put("adapterID", "jawata"));

        JsonNode response = responseTo(1);
        assertTrue(response.path("success").asBoolean());
        assertTrue(response.path("body").path("supportsConfigurationDoneRequest").asBoolean());
        assertTrue(saidEvent("initialized"));
        assertEquals(0, tools.calls.size(), "initialize asks the engine nothing");
    }

    @Test
    @DisplayName("launch makes it the person's turn first, then asks the debug tool to launch as an editor, and tells the editor the engine's session")
    void launchBeginsThePersonsTurnAndLaunches() {
        ObjectNode arguments = JSON.createObjectNode().put("mainClass", "p.Main").put("classpath", "/w/bin").put("cwd", "/w");
        arguments.putArray("args").add("one");

        send("launch", arguments);

        assertTrue(responseTo(1).path("success").asBoolean());
        JsonNode call = tools.calls.get(0);
        assertEquals("launch", call.path("action").asText());
        assertEquals("p.Main", call.path("mainClass").asText());
        assertEquals("/w/bin", call.path("classpath").asText());
        assertEquals("/w", call.path("workingDirectory").asText());
        assertEquals("one", call.path("args").path(0).asText());
        assertTrue(tools.personDebugging.get(0), "the turn was already the person's when the tool was asked");
        assertEquals(CallerIdentity.Origin.EDITOR, tools.callers.get(0).origin());
        JsonNode session = said.stream().filter(m -> "jawata/session".equals(m.path("event").asText())).findFirst().orElseThrow();
        assertEquals("s-1", session.path("body").path("sessionId").asText());
    }

    @Test
    @DisplayName("configurationDone starts the held program through the debug tool's resume")
    void configurationDoneResumes() {
        send("launch", JSON.createObjectNode().put("mainClass", "p.Main").put("classpath", "/w/bin"));

        send("configurationDone", null);

        JsonNode resume = tools.calls.get(1);
        assertEquals("resume", resume.path("action").asText());
        assertEquals("s-1", resume.path("sessionId").asText());
        assertTrue(responseTo(2).path("success").asBoolean());
    }

    @Test
    @DisplayName("disconnect detaches the session and gives the turn back")
    void disconnectDetachesAndEndsThePersonsTurn() {
        send("launch", JSON.createObjectNode().put("mainClass", "p.Main").put("classpath", "/w/bin"));

        send("disconnect", null);

        JsonNode detach = tools.calls.get(1);
        assertEquals("detach", detach.path("action").asText());
        assertEquals("s-1", detach.path("sessionId").asText());
        assertFalse(turn.personIsDebugging(), "the turn is the agent's again");
        assertTrue(responseTo(2).path("success").asBoolean());
    }

    @Test
    @DisplayName("a launch the tool refuses is answered with its reason and does not leave the turn with the person")
    void aRefusedLaunchGivesTheTurnBack() {
        tools.answer = arguments -> ToolResponse.error("INVALID_PARAMETER", "launch needs a classpath.", "give one");

        send("launch", JSON.createObjectNode().put("mainClass", "p.Main"));

        JsonNode response = responseTo(1);
        assertFalse(response.path("success").asBoolean());
        assertTrue(response.path("message").asText().contains("launch needs a classpath."), response.toString());
        assertFalse(turn.personIsDebugging());
    }

    @Test
    @DisplayName("a registry without the debug tool refuses the launch with the reason and does not leave the turn with the person")
    void aMissingDebugToolGivesTheTurnBack() {
        tools.toolMissing = true;

        send("launch", JSON.createObjectNode().put("mainClass", "p.Main").put("classpath", "/w/bin"));

        JsonNode response = responseTo(1);
        assertFalse(response.path("success").asBoolean());
        assertTrue(response.path("message").asText().contains("debug tool is not available"), response.toString());
        assertFalse(turn.personIsDebugging());
    }

    /** Makes the fake's wait answer with the queued hits, one per call, and with "nothing yet" when none is queued. */
    private void armWait() {
        tools.byAction.put("wait", arguments -> {
            try {
                ToolResponse next = hits.poll(30, TimeUnit.MILLISECONDS);
                return next != null ? next : ToolResponse.success(Map.of("hit", false, "targetAlive", true));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResponse.success(Map.of("hit", false, "targetAlive", true));
            }
        });
    }

    private static ToolResponse aHit(long threadId) {
        return ToolResponse.success(Map.of("hit", true, "threadId", threadId, "class", "p.Main", "method", "main", "line", 7, "kind", "line"));
    }

    private boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 250 && !condition.getAsBoolean(); i++) {
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /** Launches the program as request number one, so the requests a test sends next are numbered on from it. */
    private void launched() {
        send("launch", JSON.createObjectNode().put("mainClass", "p.Main").put("classpath", "/w/bin"));
    }

    @Test
    @DisplayName("setBreakpoints arms each line of the file as a line breakpoint in its class and replaces the ones it set for that file before")
    void setBreakpointsArmsLinesAndReplacesTheEarlierOnes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("p/Main.java");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, "package p;\n\npublic class Main {\n}\n");
        java.util.concurrent.atomic.AtomicInteger armed = new java.util.concurrent.atomic.AtomicInteger();
        tools.byAction.put("breakpoint_set", arguments -> ToolResponse.success(
            Map.of("breakpointId", "bp-" + armed.incrementAndGet(), "bound", true, "line", arguments.path("line").asInt())));
        tools.byAction.put("breakpoint_clear", arguments -> ToolResponse.success(Map.of("cleared", true)));
        launched();
        ObjectNode arguments = JSON.createObjectNode();
        arguments.putObject("source").put("path", file.toString());
        arguments.putArray("breakpoints").addObject().put("line", 7);
        arguments.withArray("breakpoints").addObject().put("line", 9);

        send("setBreakpoints", arguments);

        JsonNode first = tools.calls.get(1);
        assertEquals("breakpoint_set", first.path("action").asText());
        assertEquals("line", first.path("kind").asText());
        assertEquals("p.Main", first.path("className").asText());
        assertEquals(7, first.path("line").asInt());
        assertEquals(9, tools.calls.get(2).path("line").asInt());
        JsonNode body = responseTo(2).path("body").path("breakpoints");
        assertEquals(2, body.size());
        assertTrue(body.path(0).path("verified").asBoolean());
        assertEquals(7, body.path(0).path("line").asInt());

        ObjectNode again = JSON.createObjectNode();
        again.putObject("source").put("path", file.toString());
        again.putArray("breakpoints").addObject().put("line", 11);
        send("setBreakpoints", again);

        List<String> actions = tools.calls.stream().map(c -> c.path("action").asText() + ":" + c.path("breakpointId").asText()).toList();
        assertTrue(actions.contains("breakpoint_clear:bp-1") && actions.contains("breakpoint_clear:bp-2"), actions.toString());
        assertEquals(11, tools.calls.get(tools.calls.size() - 1).path("line").asInt());
    }

    @Test
    @DisplayName("a hit of the program is told to the editor as a stopped event naming the thread and why it stopped")
    void aHitIsToldAsAStoppedEvent() throws Exception {
        armWait();
        launched();
        send("configurationDone", null);

        hits.add(aHit(42));

        assertTrue(waitUntil(() -> saidEvent("stopped")), said.toString());
        JsonNode stopped = said.stream().filter(m -> "stopped".equals(m.path("event").asText())).findFirst().orElseThrow();
        assertEquals(42, stopped.path("body").path("threadId").asInt());
        assertEquals("breakpoint", stopped.path("body").path("reason").asText());
        send("disconnect", null);
    }

    @Test
    @DisplayName("continue resumes the thread through the debug tool, and next, stepIn and stepOut step it by their modes; the stop that follows a step says so")
    void continueAndTheStepsReachTheTool() throws Exception {
        armWait();
        launched();
        send("configurationDone", null);

        send("continue", JSON.createObjectNode().put("threadId", 42));
        send("next", JSON.createObjectNode().put("threadId", 42));
        send("stepIn", JSON.createObjectNode().put("threadId", 42));
        send("stepOut", JSON.createObjectNode().put("threadId", 42));

        List<String> made = tools.calls.stream().map(c -> c.path("action").asText() + ":" + c.path("mode").asText() + ":" + c.path("threadId").asText()).toList();
        assertTrue(made.contains("resume::42"), made.toString());
        assertTrue(made.contains("step:over:42") && made.contains("step:in:42") && made.contains("step:out:42"), made.toString());
        assertFalse(responseTo(3).path("body").path("allThreadsContinued").asBoolean(true), "only the one thread was resumed");
        hits.add(aHit(42));
        assertTrue(waitUntil(() -> saidEvent("stopped")), said.toString());
        JsonNode stopped = said.stream().filter(m -> "stopped".equals(m.path("event").asText())).findFirst().orElseThrow();
        assertEquals("step", stopped.path("body").path("reason").asText());
        send("disconnect", null);
    }

    @Test
    @DisplayName("threads and stackTrace answer from the debug tool's threads and snapshot, with frame ids the editor can hand back")
    void threadsAndTheStackAreAnswered() {
        tools.byAction.put("threads", arguments -> ToolResponse.success(Map.of("threads",
            List.of(Map.of("threadId", 42L, "name", "main", "state", "RUNNING", "suspended", true)))));
        tools.byAction.put("snapshot", arguments -> ToolResponse.success(Map.of("threadId", 42L, "stackDepth", 2, "stack", List.of(
            Map.of("class", "p.Main", "method", "run", "line", 7, "source", "Main.java", "frameIndex", 0),
            Map.of("class", "p.Main", "method", "main", "line", 3, "source", "Main.java", "frameIndex", 1)))));
        launched();

        send("threads", null);
        send("stackTrace", JSON.createObjectNode().put("threadId", 42));

        JsonNode thread = responseTo(2).path("body").path("threads").path(0);
        assertEquals(42, thread.path("id").asInt());
        assertEquals("main", thread.path("name").asText());
        JsonNode frames = responseTo(3).path("body").path("stackFrames");
        assertEquals(2, frames.size());
        assertEquals("p.Main.run", frames.path(0).path("name").asText());
        assertEquals(7, frames.path(0).path("line").asInt());
        assertEquals("Main.java", frames.path(0).path("source").path("name").asText());
        assertEquals(2, responseTo(3).path("body").path("totalFrames").asInt());
        assertTrue(frames.path(0).path("id").asInt() != frames.path(1).path("id").asInt(), "frame ids must tell the frames apart");
    }

    @Test
    @DisplayName("when the program ends the editor is told it terminated, once")
    void theEndOfTheProgramIsToldOnce() throws Exception {
        armWait();
        launched();
        send("configurationDone", null);

        hits.add(ToolResponse.success(Map.of("hit", false, "targetAlive", false)));

        assertTrue(waitUntil(() -> saidEvent("terminated")), said.toString());
        Thread.sleep(150);
        assertEquals(1, said.stream().filter(m -> "terminated".equals(m.path("event").asText())).count());
        send("disconnect", null);
    }

    @Test
    @DisplayName("CONTROL — a request the server does not serve yet is answered as unsupported, never ignored")
    void anUnservedRequestIsAnsweredAsUnsupported() {
        send("evaluate", null);

        JsonNode response = responseTo(1);
        assertFalse(response.path("success").asBoolean());
        assertTrue(response.path("message").asText().contains("evaluate"), response.toString());
    }

    @Test
    @DisplayName("every message the server sends has a sequence number that rises")
    void sequenceNumbersRise() {
        send("initialize", null);
        send("launch", JSON.createObjectNode().put("mainClass", "p.Main").put("classpath", "/w/bin"));

        int previous = 0;
        for (JsonNode message : said) {
            int seq = message.path("seq").asInt();
            assertTrue(seq > previous, "sequence numbers do not rise in " + said);
            previous = seq;
        }
    }
}
