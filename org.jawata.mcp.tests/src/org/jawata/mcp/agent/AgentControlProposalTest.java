package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Sprint 28h, S9, contract R7: a seat's proposal reaches the host as {@code session/request_permission}. When a hire has ended well and changed
 * files, the person is asked once whether to accept it; the answer is kept on the hire's entry and shown to the host, and the agent changes no
 * file on it. An answer that chose nothing, a hire that changed nothing and a hire that failed ask or record nothing.
 */
class AgentControlProposalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An agent whose brain runs one hire (planId t1, seat developer) that ends with the given answer, and the messages it sent the host. */
    private static final class Connection {
        final List<JsonNode> said = new ArrayList<>();
        final AcpServer server;

        Connection(String answerJson, String status) {
            AgentBrain brain = new AgentBrain() {
                @Override
                public void answer(String prompt, Consumer<String> chunks) {
                    answer(prompt, chunks, call -> { });
                }

                @Override
                public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                    ObjectNode started = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "t1").put("status", "pending")
                        .put("title", "mcp__jawata-javata-dev__hire");
                    started.putObject("rawInput").put("hirer", "chief-engineer").put("seat", "developer").put("brief", "build it");
                    toolCalls.accept(started);
                    ObjectNode ended = JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "t1").put("status", status)
                        .put("title", "mcp__jawata-javata-dev__hire");
                    ended.put("rawOutput", answerJson);
                    toolCalls.accept(ended);
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

        List<JsonNode> questions() {
            return said.stream().filter(m -> "session/request_permission".equals(m.path("method").asText())).toList();
        }

        void answer(JsonNode question, ObjectNode outcome, Channel from) {
            ObjectNode result = JSON.createObjectNode();
            result.set("outcome", outcome);
            server.receive(JsonRpcMessage.successResponse(question.path("id").asText(), result), from);
        }

        ObjectNode selected(String optionId) {
            return JSON.createObjectNode().put("outcome", "selected").put("optionId", optionId);
        }

        /** The hire entry on the last update of the hire's tool call, as the host last saw it. */
        JsonNode lastEntry() {
            JsonNode entry = null;
            for (JsonNode m : said) {
                JsonNode update = m.path("params").path("update");
                if ("t1".equals(update.path("toolCallId").asText()) && update.path("_meta").has("jawata/hire")) {
                    entry = update.path("_meta").path("jawata/hire");
                }
            }
            return entry;
        }
    }

    private static final String CHANGED = "{\"data\":{\"cleanExit\":true,\"answer\":\"done\",\"changedFiles\":[\"src/A.java\",\"src/B.java\"]}}";

    @Test
    @DisplayName("a hire that ended well and changed files is put to the person once, as a permission request listing the files, with accept and reject")
    void aFinishedProposalIsPutToThePersonOnce() {
        Connection connection = new Connection(CHANGED, "completed");

        assertEquals(1, connection.questions().size(), connection.said.toString());
        JsonNode params = connection.questions().get(0).path("params");
        assertEquals("t1", params.path("toolCall").path("toolCallId").asText());
        assertTrue(params.path("toolCall").path("title").asText().contains("developer"), params.toString());
        assertTrue(params.path("toolCall").toString().contains("src/A.java") && params.path("toolCall").toString().contains("src/B.java"), params.toString());
        assertEquals("accept", params.path("options").get(0).path("optionId").asText());
        assertEquals("reject", params.path("options").get(1).path("optionId").asText());
        assertEquals("developer", params.path("_meta").path("jawata/hire").path("seat").asText());
    }

    @Test
    @DisplayName("the person's yes is kept on the hire's entry and shown to the host as an update of the hire's call")
    void anAcceptIsKeptAndShown() {
        Connection connection = new Connection(CHANGED, "completed");

        connection.answer(connection.questions().get(0), connection.selected("accept"), Channel.HOST);

        assertEquals("accepted", connection.lastEntry().path("verdict").asText(), connection.said.toString());
    }

    @Test
    @DisplayName("the person's no is kept as rejected, and nothing is asked a second time")
    void aRejectIsKept() {
        Connection connection = new Connection(CHANGED, "completed");

        connection.answer(connection.questions().get(0), connection.selected("reject"), Channel.HOST);

        assertEquals("rejected", connection.lastEntry().path("verdict").asText());
        assertEquals(1, connection.questions().size());
    }

    @Test
    @DisplayName("an answer that chose nothing, or one that came from a hired agent, leaves no verdict")
    void noChoiceIsNoVerdict() {
        Connection cancelled = new Connection(CHANGED, "completed");
        cancelled.answer(cancelled.questions().get(0), JSON.createObjectNode().put("outcome", "cancelled"), Channel.HOST);
        Connection forged = new Connection(CHANGED, "completed");
        forged.answer(forged.questions().get(0), forged.selected("accept"), Channel.BRAIN);

        assertFalse(cancelled.lastEntry().has("verdict"), cancelled.lastEntry().toString());
        assertFalse(forged.lastEntry().has("verdict"), "a hired agent's message was taken for the person's yes: " + forged.lastEntry());
    }

    @Test
    @DisplayName("a hire that changed nothing, a hire that failed, and an answer to a question never asked put nothing to the person")
    void nothingToAskIsNotAsked() {
        Connection none = new Connection("{\"data\":{\"cleanExit\":true,\"answer\":\"read only\",\"changedFiles\":[]}}", "completed");
        Connection failed = new Connection("{\"data\":{\"cleanExit\":false,\"timedOut\":true,\"changedFiles\":[\"src/A.java\"]}}", "completed");
        none.server.receive(JsonRpcMessage.successResponse("jawata-proposal-99", JSON.createObjectNode()), Channel.HOST);

        assertEquals(0, none.questions().size());
        assertEquals(0, failed.questions().size());
    }
}
