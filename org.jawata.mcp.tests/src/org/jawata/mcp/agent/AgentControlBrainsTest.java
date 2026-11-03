package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S9 (his ruling on the Agent Control panel, 2026-10-31): the agent serves the brains the studio offers, each with its model, so
 * the panel can show a brain control per agent. The method name is provisional until the chat's recording R12 carries it.
 */
class AgentControlBrainsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REGISTRY = "{\"claude-code\":{\"command\":[\"claude-agent-acp\"],\"vendor\":\"anthropic\",\"model\":\"opus\"},"
        + "\"codex\":{\"command\":[\"codex-acp\"],\"vendor\":\"openai\",\"model\":\"gpt-5\"},"
        + "\"bare\":{\"command\":[\"bare-acp\"]}}";

    /** The answer to {@code _jawata/brains} from an agent whose registry setting reads {@code registry} (null: the registry is never offered). */
    private static JsonNode brainsAnswer(String registry) {
        List<JsonNode> said = new ArrayList<>();
        AcpServer server = new AcpServer((prompt, chunks) -> chunks.accept("x"), List::of, message -> said.add(JSON.valueToTree(message)),
            TurnBoundary.open(), AgentRecorder.off(), null, null);
        if (registry != null) {
            server.offerBrains(() -> registry);
        }
        server.receive(JsonRpcMessage.request(7, "_jawata/brains", JSON.createObjectNode()));
        return said.stream().filter(m -> m.path("id").asInt() == 7).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the brains the registry offers are served in its order, each with its vendor and its model")
    void theBrainsAreServedWithTheirModels() {
        JsonNode answer = brainsAnswer(REGISTRY);

        assertNull(answer.get("error"), answer.toString());
        JsonNode brains = answer.path("result").path("brains");
        assertEquals(3, brains.size(), brains.toString());
        assertEquals("claude-code", brains.get(0).path("brain").asText());
        assertEquals("anthropic", brains.get(0).path("vendor").asText());
        assertEquals("opus", brains.get(0).path("model").asText());
        assertEquals("codex", brains.get(1).path("brain").asText());
        assertEquals("gpt-5", brains.get(1).path("model").asText());
    }

    @Test
    @DisplayName("a brain the registry names without a vendor or model is served with them blank, never invented")
    void aBrainWithoutAModelIsServedBlank() {
        JsonNode bare = brainsAnswer(REGISTRY).path("result").path("brains").get(2);

        assertEquals("bare", bare.path("brain").asText());
        assertEquals("", bare.path("vendor").asText());
        assertEquals("", bare.path("model").asText());
    }

    @Test
    @DisplayName("the control: with no registry offered, or text that is not a registry, the answer is an empty list and not an error")
    void noRegistryIsAnEmptyListNotAnError() {
        for (String registry : new String[] {null, "", "not json", "[1,2]"}) {
            JsonNode answer = brainsAnswer(registry);
            assertNull(answer.get("error"), "registry " + registry + ": " + answer);
            assertTrue(answer.path("result").path("brains").isArray(), "registry " + registry + ": " + answer);
            assertEquals(0, answer.path("result").path("brains").size(), "registry " + registry + ": " + answer);
        }
    }
}
