package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.TranscriptStore;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S9 (D31, D4): what the agent says is written to the engine's transcript lane, and a later connection lists and reloads
 * the session from it. The lane here is the real one (an in-memory store behind the real {@code experience} tool), reached the way
 * the agent reaches it, through the tool surface.
 */
class AgentHistoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private H2ExperienceStore store;

    @BeforeEach
    void openTheLane() {
        store = H2ExperienceStore.openMemory();
    }

    @AfterEach
    void closeTheLane() {
        store.close();
    }

    /** The engine's tool surface as the agent sees it: only {@code experience}, backed by the real transcript lane. */
    private ToolCalls tools() {
        ExperienceTool experience = new ExperienceTool(() -> null, store);
        experience.setTranscriptStore(new TranscriptStore(store));
        return new ToolCalls() {
            @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                return "experience".equals(name) ? experience.execute(arguments) : ToolResponse.error("NO_SUCH_TOOL", name, "none");
            }
            @Override public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
    }

    /** One connection of the agent whose brain answers {@code says}, recording at {@code depth}. */
    private final class Connection {
        final List<JsonNode> said = new ArrayList<>();
        final AcpServer server;
        String session;

        Connection(String says, AgentRecorder.Depth depth) {
            AgentBrain brain = (prompt, chunks) -> chunks.accept(says);
            server = new AcpServer(brain, List::of, m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(),
                new AgentRecorder(tools(), depth));
        }

        JsonNode call(int id, String method, ObjectNode params) {
            said.clear();
            server.receive(JsonRpcMessage.request(id, method, params));
            return said.stream().filter(m -> m.has("id") && m.get("id").asInt() == id).findFirst().orElseThrow();
        }

        void open() {
            session = call(1, "session/new", JSON.createObjectNode().put("cwd", "/w")).path("result").path("sessionId").asText();
        }

        void prompt(String text) {
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", text);
            call(9, "session/prompt", params);
        }

        List<String> updates() {
            List<String> kinds = new ArrayList<>();
            for (JsonNode m : said) {
                JsonNode update = m.path("params").path("update");
                if (update.has("sessionUpdate")) {
                    kinds.add(update.path("sessionUpdate").asText() + ":" + update.path("content").path("text").asText());
                }
            }
            return kinds;
        }
    }

    @Test
    @DisplayName("a later connection lists the session by its first words and reloads it as it was said, then takes prompts under the old id")
    void aLaterConnectionListsAndReloadsTheSession() {
        Connection first = new Connection("pong", AgentRecorder.Depth.ALL);
        first.open();
        first.prompt("Reply with exactly the one word: pong");

        Connection later = new Connection("second answer", AgentRecorder.Depth.ALL);
        JsonNode listed = later.call(2, "session/list", JSON.createObjectNode());
        assertEquals(1, listed.path("result").path("sessions").size());
        assertEquals(first.session, listed.path("result").path("sessions").get(0).path("sessionId").asText());
        assertEquals("Reply with exactly the one word: pong", listed.path("result").path("sessions").get(0).path("title").asText());

        ObjectNode load = JSON.createObjectNode().put("sessionId", first.session).put("cwd", "/w");
        JsonNode loaded = later.call(3, "session/load", load);

        assertEquals(first.session, loaded.path("result").path("sessionId").asText());
        assertEquals(List.of("user_message_chunk:Reply with exactly the one word: pong", "agent_message_chunk:pong"), later.updates(),
            "the earlier turn is replayed in the order it was said, before the load is answered");

        later.session = first.session;
        later.prompt("and once more");
        JsonNode again = later.call(4, "session/list", JSON.createObjectNode());
        assertEquals(1, again.path("result").path("sessions").size(), "the reloaded session continues; it is not a second one");
    }

    @Test
    @DisplayName("with recording off nothing is written; with depth principal the principal's own conversation still is")
    void theDepthSettingDecidesWhatIsWritten() {
        Connection off = new Connection("pong", AgentRecorder.Depth.OFF);
        off.open();
        off.prompt("hello");
        assertEquals(0, off.call(2, "session/list", JSON.createObjectNode()).path("result").path("sessions").size(),
            "recording is off, so the lane holds nothing");

        Connection principal = new Connection("pong", AgentRecorder.Depth.PRINCIPAL);
        principal.open();
        principal.prompt("hello");
        assertEquals(1, principal.call(3, "session/list", JSON.createObjectNode()).path("result").path("sessions").size());

        assertTrue(new AgentRecorder(null, AgentRecorder.Depth.PRINCIPAL).records(AgentRecorder.Level.PRINCIPAL));
        assertTrue(!new AgentRecorder(null, AgentRecorder.Depth.PRINCIPAL).records(AgentRecorder.Level.HIRED),
            "CONTROL: the principal depth leaves a hired agent's conversation out");
        assertEquals(AgentRecorder.Depth.ALL, AgentRecorder.Depth.of(null), "no setting is the default, everything");
        assertEquals(AgentRecorder.Depth.OFF, AgentRecorder.Depth.of("off"));
    }

    @Test
    @DisplayName("a session the recording does not hold cannot be loaded, and says so")
    void anUnknownSessionIsRefused() {
        Connection later = new Connection("pong", AgentRecorder.Depth.ALL);

        JsonNode refused = later.call(2, "session/load", JSON.createObjectNode().put("sessionId", "never-recorded"));

        assertTrue(refused.has("error"), refused.toString());
        assertTrue(refused.path("error").path("message").asText().contains("never-recorded"));
    }
}
