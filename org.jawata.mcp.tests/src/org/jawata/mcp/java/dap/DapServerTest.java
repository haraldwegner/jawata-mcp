package org.jawata.mcp.java.dap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        final List<JsonNode> calls = new ArrayList<>();
        final List<Boolean> personDebugging = new ArrayList<>();
        final List<CallerIdentity> callers = new ArrayList<>();
        Function<JsonNode, ToolResponse> answer = arguments -> ToolResponse.success(Map.of("sessionId", "s-1"));
        boolean toolMissing;

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
            return answer.apply(arguments);
        }

        @Override
        public List<Map<String, Object>> getToolDefinitions() {
            return List.of();
        }
    }

    private final PersonDebugTurn turn = new PersonDebugTurn();
    private final FakeTools tools = new FakeTools(turn);
    private final List<JsonNode> said = new ArrayList<>();
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
