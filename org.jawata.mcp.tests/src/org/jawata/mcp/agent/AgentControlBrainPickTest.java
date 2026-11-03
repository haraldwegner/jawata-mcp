package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.jawata.mcp.execution.SeatBrainPicks;
import org.jawata.mcp.execution.SeatHire;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S9 (his ruling on the Agent Control panel, 2026-10-31): each agent row's brain is a control whose pick overwrites that agent's
 * brain. The pick sets the brain and model of the agent's seat for its next hire, because a process already started cannot change its brain;
 * the answer is the agent's hire entry as it will be. The method name is provisional until the chat's recording R12 carries it.
 */
class AgentControlBrainPickTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * An agent that has seen one hire (planId t1, of the given seat) go by, with a connection on which picks are made. Each test names its
     * own seat, because the picks are one store for the whole process and a pick one test made must not be seen by another.
     */
    private static final class Connection {
        final List<JsonNode> said = new ArrayList<>();
        final AcpServer server;

        Connection(String seat) {
            AgentBrain brain = new AgentBrain() {
                @Override
                public void answer(String prompt, Consumer<String> chunks) {
                    answer(prompt, chunks, call -> { });
                }

                @Override
                public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                    ObjectNode hire = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "t1").put("status", "pending")
                        .put("title", "mcp__jawata-javata-dev__hire");
                    hire.putObject("rawInput").put("hirer", "pm").put("seat", seat).put("brief", "build the stage");
                    toolCalls.accept(hire);
                    chunks.accept("ok");
                }
            };
            server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null, null);
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", "go");
            server.receive(JsonRpcMessage.request(2, "session/prompt", params));
        }

        JsonNode pick(String planId, String brain, String model) {
            ObjectNode params = JSON.createObjectNode();
            if (planId != null) {
                params.put("planId", planId);
            }
            if (brain != null) {
                params.put("brain", brain);
            }
            if (model != null) {
                params.put("model", model);
            }
            server.receive(JsonRpcMessage.request(9, "_jawata/hire/brain", params));
            return said.stream().filter(m -> m.path("id").asInt() == 9).findFirst().orElseThrow();
        }
    }

    @Test
    @DisplayName("a pick for a brain that takes a model answers the hire entry with that brain and model, and the seat's next hire will use them")
    void aPickWithAModelIsAnsweredAndRemembered() {
        JsonNode answer = new Connection("pick-seat-with-model").pick("t1", "claude-code", " opus-x ");

        assertNull(answer.get("error"), answer.toString());
        JsonNode entry = answer.path("result");
        assertEquals("t1", entry.path("planId").asText());
        assertEquals("pick-seat-with-model", entry.path("seat").asText());
        assertEquals("claude-code", entry.path("brain").asText());
        assertEquals("opus-x", entry.path("model").asText());
        assertEquals(Optional.of(new SeatBrainPicks.Pick(SeatHire.Brain.CLAUDE_CODE, "opus-x")), SeatBrainPicks.shared().forSeat("pick-seat-with-model"));
    }

    @Test
    @DisplayName("a model named for a brain whose command line takes none is neither applied nor echoed, so the panel is never shown a model nobody will use")
    void aModelForABrainThatTakesNoneIsNotEchoed() {
        JsonNode entry = new Connection("pick-seat-without-model").pick("t1", "codex", "gpt-5").path("result");

        assertEquals("codex", entry.path("brain").asText());
        assertFalse(entry.has("model"), "a model that will not be used was shown: " + entry);
        assertEquals(Optional.of(new SeatBrainPicks.Pick(SeatHire.Brain.CODEX, "")), SeatBrainPicks.shared().forSeat("pick-seat-without-model"));
    }

    @Test
    @DisplayName("a hire the agent never saw, no brain, a brain this product has no dialect for, and the script brain are bad requests, and nothing is remembered")
    void badRequestsAreRefusedAndRememberNothing() {
        Connection connection = new Connection("pick-seat-refused");
        String[][] bad = {{"nope", "codex"}, {null, "codex"}, {"t1", null}, {"t1", "no-such-brain"}, {"t1", "script"}};

        for (String[] request : bad) {
            JsonNode answer = connection.pick(request[0], request[1], null);
            assertNotNull(answer.get("error"), String.join("/", String.valueOf(request[0]), String.valueOf(request[1])) + ": " + answer);
            assertEquals(-32602, answer.path("error").path("code").asInt(), answer.toString());
        }
        assertTrue(SeatBrainPicks.shared().forSeat("pick-seat-refused").isEmpty(), "a refused request was remembered");
    }
}
