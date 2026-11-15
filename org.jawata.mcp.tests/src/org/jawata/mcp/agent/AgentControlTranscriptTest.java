package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S9 (his ruling on the Agent Control panel, 2026-10-31): unfolding one agent in the panel returns what was said to it and what it
 * answered. The transcript is built from the hire's own brief and its answer, both of which the agent sees as the hire call goes by; the
 * method name is provisional until the chat's recording R12 carries it.
 */
class AgentControlTranscriptTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode hireCall(String kind, String status) {
        return JSON.createObjectNode().put("sessionUpdate", kind).put("toolCallId", "t1").put("status", status)
            .put("title", "mcp__jawata-javata-dev__hire");
    }

    /** An agent whose brain issues one hire, with the given updates after it, and a connection on which {@code _jawata/hire/transcript} is asked. */
    private static final class Connection {
        final List<JsonNode> said = new ArrayList<>();
        final AcpServer server;

        Connection(List<ObjectNode> hireUpdates) {
            AgentBrain brain = new AgentBrain() {
                @Override
                public void answer(String prompt, Consumer<String> chunks) {
                    answer(prompt, chunks, call -> { });
                }

                @Override
                public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                    hireUpdates.forEach(toolCalls);
                    chunks.accept("ok");
                }
            };
            server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", "go");
            server.receive(JsonRpcMessage.request(2, "session/prompt", params));
        }

        JsonNode transcriptOf(String planId) {
            ObjectNode params = JSON.createObjectNode();
            if (planId != null) {
                params.put("planId", planId);
            }
            server.receive(JsonRpcMessage.request(9, "_jawata/hire/transcript", params));
            return said.stream().filter(m -> m.path("id").asInt() == 9).findFirst().orElseThrow();
        }
    }

    private static ObjectNode started() {
        ObjectNode update = hireCall("tool_call", "pending");
        update.putObject("rawInput").put("hirer", "pm").put("seat", "chief-engineer").put("brief", "build the stage");
        return update;
    }

    private static ObjectNode finished(String answerJson) {
        return hireCall("tool_call_update", "completed").put("rawOutput", answerJson);
    }

    @Test
    @DisplayName("a finished hire's transcript is what its issuer asked and what it answered, each with who said it, in that order")
    void aFinishedHireReturnsTheBriefAndTheAnswer() {
        Connection connection = new Connection(List.of(started(),
            finished("{\"data\":{\"cleanExit\":true,\"answer\":\"done: the stage is built\"}}")));

        JsonNode answer = connection.transcriptOf("t1");

        assertNull(answer.get("error"), answer.toString());
        assertEquals("t1", answer.path("result").path("planId").asText());
        JsonNode turns = answer.path("result").path("turns");
        assertEquals(2, turns.size(), turns.toString());
        assertEquals("pm", turns.get(0).path("from").asText());
        assertEquals("build the stage", turns.get(0).path("text").asText());
        assertEquals("chief-engineer", turns.get(1).path("from").asText());
        assertEquals("done: the stage is built", turns.get(1).path("text").asText());
    }

    @Test
    @DisplayName("a hire still running has said nothing yet: its transcript is the brief alone, never an invented answer")
    void aRunningHireReturnsOnlyTheBrief() {
        Connection connection = new Connection(List.of(started()));

        JsonNode turns = connection.transcriptOf("t1").path("result").path("turns");

        assertEquals(1, turns.size(), turns.toString());
        assertEquals("build the stage", turns.get(0).path("text").asText());
    }

    @Test
    @DisplayName("a finished hire whose answer holds no text has no answer turn, so a failure is not shown as an empty reply")
    void anAnswerWithoutTextAddsNoTurn() {
        Connection connection = new Connection(List.of(started(), finished("{\"data\":{\"cleanExit\":false,\"timedOut\":true}}")));

        JsonNode turns = connection.transcriptOf("t1").path("result").path("turns");

        assertEquals(1, turns.size(), turns.toString());
    }

    @Test
    @DisplayName("a hire the agent never saw, or no plan id at all, is refused as a bad request and not answered with an empty transcript")
    void anUnknownHireIsRefused() {
        Connection connection = new Connection(List.of(started()));

        for (String planId : new String[] {"nope", null}) {
            JsonNode answer = connection.transcriptOf(planId);
            assertNotNull(answer.get("error"), "planId " + planId + ": " + answer);
            assertEquals(-32602, answer.path("error").path("code").asInt(), "planId " + planId + ": " + answer);
        }
    }
}
