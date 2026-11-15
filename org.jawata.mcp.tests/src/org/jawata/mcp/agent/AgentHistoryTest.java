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
            this((prompt, chunks) -> chunks.accept(says), depth);
        }

        Connection(AgentBrain brain, AgentRecorder.Depth depth) {
            server = new AcpServer(brain, List::of, m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(),
                new AgentRecorder(tools(), depth), null);
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

    /** A brain that reads one file in the middle of its answer, streaming the call in the two pieces a real brain streams it in. */
    private static AgentBrain readingBrain() {
        return new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks, java.util.function.Consumer<JsonNode> toolCalls) {
                toolCalls.accept(JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "t1")
                    .put("title", "Read a.txt").put("status", "pending"));
                toolCalls.accept(JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "t1")
                    .put("status", "completed"));
                chunks.accept("it says one");
            }
        };
    }

    @Test
    @DisplayName("a brain's tool calls reach the host as they happen, and a later connection reloading the session sees each call as one finished line")
    void aBrainsToolCallsReachTheHostAndReplayAsOneLine() {
        Connection first = new Connection(readingBrain(), AgentRecorder.Depth.ALL);
        first.open();
        first.prompt("read a.txt");

        assertEquals(List.of("tool_call", "tool_call_update", "agent_message_chunk"), first.said.stream()
            .map(m -> m.path("params").path("update").path("sessionUpdate").asText()).filter(s -> !s.isEmpty()).toList(),
            "the host did not see the tool call before the answer");

        Connection later = new Connection("unused", AgentRecorder.Depth.ALL);
        later.call(3, "session/load", JSON.createObjectNode().put("sessionId", first.session).put("cwd", "/w"));
        List<String> titles = later.said.stream().map(m -> m.path("params").path("update"))
            .filter(u -> "tool_call".equals(u.path("sessionUpdate").asText())).map(u -> u.path("title").asText()).toList();
        assertEquals(List.of("Read a.txt — completed"), titles, "the reloaded session did not replay the call as one line: " + later.said);
    }

    /** A brain that makes one hire as a real brain streams it: the call with its arguments, then (when {@code finished}) its completion carrying the hire tool's answer. */
    private static AgentBrain hiringBrain(boolean finished) {
        return new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks, java.util.function.Consumer<JsonNode> toolCalls) {
                ObjectNode start = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "h1")
                    .put("title", "mcp__jawata-javata-dev__hire").put("status", "pending");
                start.putObject("rawInput").put("hirer", "main").put("seat", "pm").put("brief", "Reply with the one word ok.");
                toolCalls.accept(start);
                if (finished) {
                    ObjectNode end = JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "h1").put("status", "completed");
                    end.put("rawOutput", "{\"cleanExit\":true,\"answer\":\"SECRET ANSWER TEXT\",\"tokens\":{\"spent\":1234},\"costUsd\":0.05,"
                        + "\"brain\":{\"brain\":\"claude-code\",\"model\":\"sonnet\"}}");
                    toolCalls.accept(end);
                }
                chunks.accept("done");
            }
        };
    }

    private static List<JsonNode> cardsReplayed(Connection later) {
        return later.said.stream().map(m -> m.path("params").path("update"))
            .filter(u -> u.path("_meta").has("jawata/hire")).toList();
    }

    @Test
    @DisplayName("a hire's card comes back when an old session is loaded, with its figures and state, and never carries the hire's answer text")
    void aHireCardComesBackWhenAnOldSessionIsLoaded() {
        Connection first = new Connection(hiringBrain(true), AgentRecorder.Depth.ALL);
        first.open();
        first.prompt("hire a pm");

        Connection later = new Connection("unused", AgentRecorder.Depth.ALL);
        later.call(3, "session/load", JSON.createObjectNode().put("sessionId", first.session).put("cwd", "/w"));
        List<JsonNode> cards = cardsReplayed(later);

        assertEquals(1, cards.size(), "one card for one hire: " + later.said);
        JsonNode card = cards.get(0).path("_meta").path("jawata/hire");
        assertEquals("tool_call", cards.get(0).path("sessionUpdate").asText());
        assertEquals("pm", card.path("seat").asText());
        assertEquals("done", card.path("state").asText());
        assertEquals("sonnet", card.path("model").asText(), "the model the hire ran, as the card showed it");
        assertEquals(1234, card.path("tokens").asLong());
        assertEquals(0.05, card.path("costUsd").asDouble(), 1e-9);
        assertTrue(!later.said.toString().contains("SECRET ANSWER TEXT"),
            "the lane holds what was said and the card, never the hire's answer: " + later.said);
    }

    @Test
    @DisplayName("a hire still running when the session was saved is replayed as failed, with the time it had, never as running forever")
    void aHireSavedWhileRunningReplaysAsFailed() {
        Connection first = new Connection(hiringBrain(false), AgentRecorder.Depth.ALL);
        first.open();
        first.prompt("hire a pm");

        Connection later = new Connection("unused", AgentRecorder.Depth.ALL);
        later.call(3, "session/load", JSON.createObjectNode().put("sessionId", first.session).put("cwd", "/w"));
        List<JsonNode> cards = cardsReplayed(later);

        assertEquals(1, cards.size(), later.said.toString());
        assertEquals("failed", cards.get(0).path("_meta").path("jawata/hire").path("state").asText());
        assertEquals("failed", cards.get(0).path("status").asText());
        assertTrue(cards.get(0).path("_meta").path("jawata/hire").has("elapsedMs"), "the time it ran is kept");
    }

    /** A brain that hires once in its first turn and, in its second, hires again naming the first hire's plan in {@code fixes}. */
    private static AgentBrain fixingBrain() {
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();
        return new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks, java.util.function.Consumer<JsonNode> toolCalls) {
                int turn = turns.incrementAndGet();
                ObjectNode start = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "h" + turn)
                    .put("title", "mcp__jawata-javata-dev__hire").put("status", "pending");
                ObjectNode input = start.putObject("rawInput").put("hirer", "chief-engineer").put("seat", "worker").put("brief", "turn " + turn);
                if (turn == 2) {
                    input.put("fixes", "plan-aaa");
                }
                toolCalls.accept(start);
                if (turn == 1) {
                    toolCalls.accept(JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "h1").put("status", "completed")
                        .put("rawOutput", "{\"cleanExit\":true,\"plan\":\"plan-aaa\"}"));
                }
                chunks.accept("done");
            }
        };
    }

    @Test
    @DisplayName("a hire that names fixes marks the earlier hire accepted-with-fix on the host's card at once and on the saved chat, in a later turn")
    void aFixShowsTheEarlierHireAcceptedWithFixLiveAndAfterALoad() {
        Connection first = new Connection(fixingBrain(), AgentRecorder.Depth.ALL);
        first.open();
        first.prompt("hire a worker");
        first.prompt("hire a worker to fix it");

        List<String> liveVerdicts = first.said.stream().map(m -> m.path("params").path("update"))
            .filter(u -> "h1".equals(u.path("toolCallId").asText()) && u.path("_meta").path("jawata/hire").has("verdict"))
            .map(u -> u.path("_meta").path("jawata/hire").path("verdict").asText()).toList();
        assertTrue(liveVerdicts.contains("accepted-with-fix"), "the host was not shown the earlier hire's new verdict: " + first.said);

        Connection later = new Connection("unused", AgentRecorder.Depth.ALL);
        later.call(3, "session/load", JSON.createObjectNode().put("sessionId", first.session).put("cwd", "/w"));
        JsonNode lastOfFirst = cardsReplayed(later).stream().filter(u -> "h1".equals(u.path("toolCallId").asText()))
            .reduce((earlier, next) -> next).orElseThrow();
        assertEquals("accepted-with-fix", lastOfFirst.path("_meta").path("jawata/hire").path("verdict").asText(),
            "the saved chat lost the verdict the fix gave: " + later.said);
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
