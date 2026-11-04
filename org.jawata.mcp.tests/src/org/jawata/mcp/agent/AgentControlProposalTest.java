package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
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
            this(answerJson, status, AgentRecorder.off());
        }

        Connection(String answerJson, String status, AgentRecorder recorder) {
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
            server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), recorder, null, null);
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
    @DisplayName("the request carries the change as text when the hire's answer held it, and only the file names when it held none")
    void theRequestCarriesTheDiffWhenThereIsOne() {
        String withDiff = "{\"data\":{\"cleanExit\":true,\"answer\":\"done\",\"changedFiles\":[\"src/A.java\"],\"diff\":\"Against HEAD\\n+int two;\"}}";
        Connection shown = new Connection(withDiff, "completed");
        Connection bare = new Connection("{\"data\":{\"cleanExit\":true,\"answer\":\"done\",\"changedFiles\":[\"src/A.java\"]}}", "completed");

        JsonNode shownContent = shown.questions().get(0).path("params").path("toolCall").path("content");
        JsonNode bareContent = bare.questions().get(0).path("params").path("toolCall").path("content");

        assertEquals(2, shownContent.size(), shownContent.toString());
        assertEquals("src/A.java", shownContent.get(0).path("content").path("text").asText());
        assertTrue(shownContent.get(1).path("content").path("text").asText().contains("+int two;"), shownContent.toString());
        assertEquals(1, bareContent.size(), "a hire whose answer held no diff must not be shown one: " + bareContent);
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

    /** A recorder over a stand-in engine that keeps every call the agent makes to it, so a test reads what was written to the lane. */
    private static final class Lane {
        final List<ObjectNode> written = new ArrayList<>();
        final AgentRecorder recorder = new AgentRecorder(new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                written.add(arguments.deepCopy());
                return ToolResponse.success(java.util.Map.of());
            }

            @Override
            public List<java.util.Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        }, AgentRecorder.Depth.ALL);

        /** The turns written for the person's verdict on a proposal, whatever the key of the other turns. */
        List<ObjectNode> verdicts() {
            return written.stream().filter(args -> args.path("key").asText().startsWith("verdict_")).toList();
        }
    }

    @Test
    @DisplayName("D29: the person's yes or no on a proposal is recorded in the transcript lane under the hire's id, and a cancelled question records none")
    void theVerdictIsRecordedInTheLane() {
        Lane accepted = new Lane();
        Connection yes = new Connection(CHANGED, "completed", accepted.recorder);
        yes.answer(yes.questions().get(0), yes.selected("accept"), Channel.HOST);
        Lane rejected = new Lane();
        Connection no = new Connection(CHANGED, "completed", rejected.recorder);
        no.answer(no.questions().get(0), no.selected("reject"), Channel.HOST);
        Lane cancelled = new Lane();
        Connection nothing = new Connection(CHANGED, "completed", cancelled.recorder);
        nothing.answer(nothing.questions().get(0), JSON.createObjectNode().put("outcome", "cancelled"), Channel.HOST);

        assertEquals(1, accepted.verdicts().size(), accepted.written.toString());
        assertEquals("verdict_t1", accepted.verdicts().get(0).path("key").asText());
        assertTrue(accepted.verdicts().get(0).path("his").asText().contains("accepted"), accepted.verdicts().toString());
        assertTrue(rejected.verdicts().get(0).path("his").asText().contains("rejected"), rejected.verdicts().toString());
        assertEquals(0, cancelled.verdicts().size(), "a question that chose nothing was recorded as a verdict: " + cancelled.verdicts());
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
