package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Sprint 28h, S12 (contract R9 thinking, R10 plan update): what a brain streams as its thinking and its plan reaches the host as the same
 * {@code session/update}, in the order it was streamed and among the answer's text, and a brain that streams neither sends the host neither.
 */
class AgentThinkingForwardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The session updates the host receives for one prompt to {@code brain}, in arrival order. */
    private static List<JsonNode> updatesFor(AgentBrain brain) {
        List<JsonNode> said = new ArrayList<>();
        AcpServer server = new AcpServer(brain, List::of, m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(), AgentRecorder.off(), null, null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "go");
        said.clear();

        server.receive(JsonRpcMessage.request(2, "session/prompt", params));

        return said.stream().map(m -> m.path("params").path("update")).filter(u -> u.has("sessionUpdate")).toList();
    }

    @Test
    @DisplayName("the face sends the host the brain's thinking and its plan as they were streamed, in order, among the text of the answer")
    void theHostReceivesTheThinkingAndThePlan() {
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                answer(prompt, chunks, null, null);
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls, Consumer<JsonNode> thinking) {
                ObjectNode thought = JSON.createObjectNode().put("sessionUpdate", "agent_thought_chunk");
                thought.putObject("content").put("type", "text").put("text", "Checking divisibility");
                thinking.accept(thought);
                ObjectNode plan = JSON.createObjectNode().put("sessionUpdate", "plan");
                plan.putArray("entries").addObject().put("content", "try 7").put("priority", "medium").put("status", "pending");
                thinking.accept(plan);
                chunks.accept("ok");
            }
        };

        List<JsonNode> updates = updatesFor(brain);

        assertEquals(List.of("agent_thought_chunk", "plan", "agent_message_chunk"), updates.stream().map(u -> u.path("sessionUpdate").asText()).toList());
        assertEquals("Checking divisibility", updates.get(0).path("content").path("text").asText(), "the thought's text was not carried");
        assertEquals("try 7", updates.get(1).path("entries").get(0).path("content").asText(), "the plan's entry was not carried");
    }

    @Test
    @DisplayName("CONTROL: a brain that streams no thinking sends the host no thinking and no plan, only its text")
    void aBrainWithoutThinkingSendsTheHostNone() {
        List<JsonNode> updates = updatesFor((prompt, chunks) -> chunks.accept("ok"));

        assertEquals(List.of("agent_message_chunk"), updates.stream().map(u -> u.path("sessionUpdate").asText()).toList());
    }
}
