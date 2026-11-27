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
        assertEquals(List.of("user_message_chunk:Reply with exactly the one word: pong", "agent_message_chunk:pong", "available_commands_update:",
            "config_option_update:"), later.updates(),
            "the earlier turn is replayed in the order it was said, before the load is answered, and the session's commands (D57 (h)) and options (28h z82) follow");

        later.session = first.session;
        later.prompt("and once more");
        JsonNode again = later.call(4, "session/list", JSON.createObjectNode());
        assertEquals(1, again.path("result").path("sessions").size(), "the reloaded session continues; it is not a second one");
    }

    @Test
    @DisplayName("28h D57 (h): a session opened again on a later connection, as the chat does after a restart or from History, is sent the same commands as a new one")
    void aReloadedSessionIsSentTheCommandsToo() {
        Connection first = new Connection("pong", AgentRecorder.Depth.ALL);
        first.open();
        first.prompt("Reply with exactly the one word: pong");
        List<JsonNode> said = new ArrayList<>();
        AcpServer later = new AcpServer((prompt, chunks) -> chunks.accept("x"), () -> List.of(new AdvertisedCommand("refactor", "Reviews a design.", "seat")),
            m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(), new AgentRecorder(tools(), AgentRecorder.Depth.ALL), null);

        later.receive(JsonRpcMessage.request(3, "session/load", JSON.createObjectNode().put("sessionId", first.session).put("cwd", "/w")));

        List<String> names = new ArrayList<>();
        for (JsonNode message : said) {
            JsonNode update = message.path("params").path("update");
            if ("available_commands_update".equals(update.path("sessionUpdate").asText()) && first.session.equals(message.path("params").path("sessionId").asText())) {
                update.path("availableCommands").forEach(c -> names.add(c.path("name").asText()));
            }
        }
        assertEquals(List.of("refactor"), names, "the session opened again was sent no command list, so its / list is empty: " + said);
    }

    /** What the conversation was told over two prompts when the store answers success with this outcome for each turn. */
    private static List<String> toldWhenTheStoreAnswers(String outcome) {
        ToolCalls lane = new ToolCalls() {
            @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                return ToolResponse.success(Map.of("appended", outcome, "reason", "disk full"));
            }
            @Override public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
        List<JsonNode> said = new ArrayList<>();
        AcpServer server = new AcpServer((prompt, chunks) -> chunks.accept("pong"), List::of, m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(),
            new AgentRecorder(lane, AgentRecorder.Depth.ALL), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String session = said.stream().filter(m -> m.path("id").asInt() == 1).findFirst().orElseThrow().path("result").path("sessionId").asText();
        for (int id = 2; id <= 3; id++) {
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", "hello");
            server.receive(JsonRpcMessage.request(id, "session/prompt", params));
        }
        return said.stream().map(m -> m.path("params").path("update").path("content").path("text").asText("")).filter(t -> t.contains("not saved"))
            .toList();
    }

    @Test
    @DisplayName("28h S19-13: a turn the store did not keep is said in the conversation once, with the reason, though the store answered success")
    void aTurnTheStoreDidNotKeepIsSaidInTheConversation() {
        List<String> failed = toldWhenTheStoreAnswers("failed");
        List<String> off = toldWhenTheStoreAnswers("disabled");

        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertEquals(1, failed.size(), "a failed save is said once over two turns, not " + failed),
            () -> assertTrue(String.join("", failed).contains("disk full"), "the store's reason is given: " + failed),
            () -> assertTrue(String.join("", off).contains("switched off"), "a switched off record is said: " + off),
            () -> assertTrue(toldWhenTheStoreAnswers("stored").isEmpty(), "CONTROL: a saved turn was reported as not saved"));
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

    @Test
    @DisplayName("28h z82: a chat loaded from history starts the brain ahead and then tells the host the options again, before anything is sent")
    void aLoadedSessionStartsTheBrainAndTellsTheOptions() {
        Connection first = new Connection("answered", AgentRecorder.Depth.ALL);
        first.open();
        first.prompt("hello");

        java.util.concurrent.atomic.AtomicInteger warmed = new java.util.concurrent.atomic.AtomicInteger();
        Connection later = new Connection(new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                chunks.accept("unused");
            }

            @Override
            public void warm(Runnable whenReady) {
                warmed.incrementAndGet();
                whenReady.run();
            }
        }, AgentRecorder.Depth.ALL);
        later.call(3, "session/load", JSON.createObjectNode().put("sessionId", first.session).put("cwd", "/w"));

        assertEquals(1, warmed.get(), "the load did not start the brain");
        assertTrue(later.updates().stream().anyMatch(update -> update.startsWith("config_option_update")),
            "the host was not told the options once the brain was ready: " + later.said);
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

    @Test
    @DisplayName("28h-C27-7: a hire that the engine ended after its turn had ended is replayed in the state it ended in, not as failed with the time the turn saw")
    void aHireThatEndedAfterItsTurnReplaysAsDone() {
        // a brief of its own: the engine keeps the answer it is given by seat and brief, and another test reads the one of "Reply with the one word ok."
        String brief = "28h C27-7: a hire that ends after the turn that started it";
        AgentBrain hiring = new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks, java.util.function.Consumer<JsonNode> toolCalls) {
                ObjectNode start = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "h1")
                    .put("title", "mcp__jawata-javata-dev__hire").put("status", "pending");
                start.putObject("rawInput").put("hirer", "main").put("seat", "pm").put("brief", brief);
                toolCalls.accept(start);
                chunks.accept("done");
            }
        };
        Connection first = new Connection(hiring, AgentRecorder.Depth.ALL);
        first.open();
        first.prompt("hire a pm");
        // the turn is over and the hire is out; the engine ends it later, as it does a hire that answered at once and went on in the background
        try (org.jawata.mcp.execution.HireRegistry.Registration running =
                 org.jawata.mcp.execution.HireRegistry.shared().begin("pm", brief, first.session)) {
            org.jawata.mcp.execution.HireRegistry.shared().planOpened("history-ended-plan");
            org.jawata.mcp.execution.HireRegistry.shared().record("pm", brief,
                JSON.createObjectNode().put("plan", "history-ended-plan").put("name", "Ada").put("cleanExit", true).put("answer", "ok"));
        }

        Connection later = new Connection("unused", AgentRecorder.Depth.ALL);
        later.call(3, "session/load", JSON.createObjectNode().put("sessionId", first.session).put("cwd", "/w"));
        List<JsonNode> cards = cardsReplayed(later);

        assertEquals("done", cards.get(cards.size() - 1).path("_meta").path("jawata/hire").path("state").asText(),
            "the reloaded card does not say how the hire ended: " + cards);
        // the record of how it ended is the card and nothing said by anyone: no line of it comes back as the person's words or the agent's
        assertTrue(later.said.stream().map(m -> m.path("params").path("update"))
                .filter(u -> u.path("sessionUpdate").asText().endsWith("_message_chunk"))
                .noneMatch(u -> u.path("content").path("text").asText().contains("ended")),
            "the reloaded session shows the engine's record line as something said: " + later.said);
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

    /** One captured turn in the lane under the client's name, as the studio's hook writes it. */
    private void captured(String client, String session, String his, String agent) throws Exception {
        ObjectNode args = JSON.createObjectNode().put("kind", "transcript_append").put("session", session).put("key", "t1").put("client", client)
            .put("capture", "turn").put("his", his).put("agent", agent);
        tools().callTool("experience", args, CallerIdentity.supplied("test"));
    }

    private static List<String> idsOf(JsonNode listed) {
        List<String> ids = new ArrayList<>();
        listed.path("result").path("sessions").forEach(s -> ids.add(s.path("sessionId").asText()));
        return ids;
    }

    @Test
    @DisplayName("his ruling 2026-11-18: History lists the person's conversations from every client, each with its client, and leaves out the machine-made ones")
    void theListHoldsEveryClientsSessionsExceptTheMachineMadeOnes() throws Exception {
        captured("claude-code", "claude-1", "Fix the build", "done");
        captured("cursor", "cursor-1", "Rename the class", "done");
        captured("claude-code-machine", "machine-1", "The brief of a hired seat", "done");
        captured("cursor-machine", "machine-2", "Another brief", "done");
        // sessions written before the mark existed: the principal's and a hired seat's own prompt as their first words
        captured("claude-code", "legacy-1", "You are jawata, the principal agent the person is talking to in this conversation.", "ok");
        captured("claude-code", "legacy-2", "You are the architect seat: the adversarial counterweight.", "ok");
        Connection first = new Connection("pong", AgentRecorder.Depth.ALL);
        first.open();
        first.prompt("Reply with exactly the one word: pong");

        JsonNode listed = new Connection("x", AgentRecorder.Depth.ALL).call(2, "session/list", JSON.createObjectNode());

        List<String> ids = idsOf(listed);
        assertEquals(3, ids.size(), "the person's three conversations only: " + ids);
        assertTrue(ids.containsAll(List.of("claude-1", "cursor-1", first.session)), ids.toString());
        Map<String, String> clients = new java.util.HashMap<>();
        listed.path("result").path("sessions").forEach(s -> clients.put(s.path("sessionId").asText(), s.path("_meta").path("jawata/client").asText()));
        assertEquals("claude-code", clients.get("claude-1"));
        assertEquals("cursor", clients.get("cursor-1"));
        assertEquals("jawata-agent", clients.get(first.session), "the chat's own sessions are named too");
        Map<String, String> names = new java.util.HashMap<>();
        listed.path("result").path("sessions").forEach(s -> names.put(s.path("sessionId").asText(), s.path("_meta").path("jawata/clientName").asText()));
        assertEquals("Claude Code", names.get("claude-1"), "each row also carries the name a person knows the client by");
        assertEquals("Cursor", names.get("cursor-1"));
        assertEquals("jawata", names.get(first.session));
    }

    @Test
    @DisplayName("his ruling 2026-11-18: the list comes in pages of ten, newest first, each with a cursor for the next ten, and the page size is a setting")
    void theListComesInPagesWithACursor() throws Exception {
        for (int i = 1; i <= 25; i++) {
            captured("claude-code", String.format("s%02d", i), "message " + i, "ok");
            Thread.sleep(2); // each session has its own last-activity time, so the order is the order they were written in
        }
        Connection reader = new Connection("x", AgentRecorder.Depth.ALL);

        JsonNode first = reader.call(2, "session/list", JSON.createObjectNode());
        assertEquals(10, idsOf(first).size());
        assertEquals("s25", idsOf(first).get(0), "newest first");
        assertEquals("s16", idsOf(first).get(9));
        String cursor = first.path("result").path("nextCursor").asText("");
        assertTrue(!cursor.isEmpty(), "there are more, so there is a cursor: " + first);

        JsonNode second = reader.call(3, "session/list", JSON.createObjectNode().put("cursor", cursor));
        assertEquals("s15", idsOf(second).get(0));
        assertEquals("s06", idsOf(second).get(9));

        JsonNode third = reader.call(4, "session/list", JSON.createObjectNode().put("cursor", second.path("result").path("nextCursor").asText()));
        assertEquals(List.of("s05", "s04", "s03", "s02", "s01"), idsOf(third));
        assertTrue(third.path("result").path("nextCursor").isMissingNode() || third.path("result").path("nextCursor").isNull(),
            "the last page has no cursor: " + third);

        reader.server.pageOf(3);
        assertEquals(3, idsOf(reader.call(5, "session/list", JSON.createObjectNode())).size(), "the page size is the setting's");
    }
}
