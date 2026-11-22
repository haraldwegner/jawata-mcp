package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jawata.mcp.execution.AcpBrainSession;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, Stage 23, D54 (a): the principal's row names the model that is answering. His words: "The model is not shown. You should be able
 * to show models and switch". What the row names is read from the command line the principal's brain was started with, because that is what is
 * running; a model the registry names for a brain is only what that brain would start with, and a row that showed it while another ran would
 * name the wrong one.
 */
class PrincipalModelOptionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REGISTRY = "{\"cursor\":{\"command\":[\"agent\",\"acp\"]},"
        + "\"claude-code\":{\"command\":[\"claude-agent-acp\"],\"vendor\":\"anthropic\",\"model\":\"sonnet\"}}";

    /** A brain that runs as {@code brain} on {@code model} (none for the brain's own default). */
    private static AgentBrain running(String brain, Optional<String> model) {
        return new AgentBrain() {
            @Override
            public Optional<String> currentBrain() {
                return Optional.of(brain);
            }

            @Override
            public Optional<String> currentModel() {
                return model;
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                chunks.accept("ok");
            }
        };
    }

    /** The principal's brain option as the agent shows it to the chat in the answer to {@code session/new}. */
    private static JsonNode principalOption(AgentBrain brain) {
        List<JsonNode> received = new ArrayList<>();
        AcpServer server = new AcpServer(brain, List::of, message -> received.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.offerBrains(() -> REGISTRY);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));
        JsonNode result = received.stream().filter(m -> m.has("id") && m.has("result")).map(m -> m.get("result")).findFirst().orElseThrow();
        for (JsonNode option : result.path("configOptions")) {
            if (AcpServer.PRINCIPAL_BRAIN.equals(option.path("id").asText())) {
                return option;
            }
        }
        throw new AssertionError("no principal brain option in " + result);
    }

    /** The principal's brain option in the answer to {@code session/new} among {@code received}. */
    private static JsonNode brainOptionIn(List<JsonNode> received) {
        JsonNode result = received.stream().filter(m -> m.has("id") && m.has("result")).map(m -> m.get("result")).findFirst().orElseThrow();
        for (JsonNode option : result.path("configOptions")) {
            if (AcpServer.PRINCIPAL_BRAIN.equals(option.path("id").asText())) {
                return option;
            }
        }
        throw new AssertionError("no principal brain option in " + result);
    }

    /** The rows of {@code option}, in the order the chat shows them. */
    private static List<JsonNode> rowsOf(JsonNode option) {
        List<JsonNode> rows = new ArrayList<>();
        option.path("options").forEach(rows::add);
        return rows;
    }

    /** The rows of {@code option} that belong to the agent {@code agent}. */
    private static List<JsonNode> rowsOfAgent(JsonNode option, String agent) {
        return rowsOf(option).stream().filter(row -> agent.equals(row.path(AcpServer.AGENT_FIELD).asText())).toList();
    }

    private static String nameOf(JsonNode option, String value) {
        for (JsonNode offered : option.path("options")) {
            if (value.equals(offered.path("value").asText())) {
                return offered.path("name").asText();
            }
        }
        throw new AssertionError("no value " + value + " in " + option);
    }

    @Test
    @DisplayName("the brain the principal runs on is named with the model it was started with")
    void theRunningBrainIsNamedWithItsModel() {
        JsonNode option = principalOption(running("cursor", Optional.of("cursor-grok-4.5-high-fast")));

        assertEquals("cursor", option.path("currentValue").asText());
        assertEquals("cursor · cursor-grok-4.5-high-fast", nameOf(option, "cursor"));
    }

    @Test
    @DisplayName("a brain started with no model is named by its own name only, and the registry's model is not claimed for it")
    void aBrainWithoutAModelIsNotGivenOne() {
        JsonNode option = principalOption(running("cursor", Optional.empty()));

        assertEquals("cursor", nameOf(option, "cursor"));
    }

    @Test
    @DisplayName("the model that is running is shown instead of the registry's, and the other brains keep the registry's")
    void theRunningModelWinsOverTheRegistrysAndOthersKeepTheRegistrys() {
        JsonNode option = principalOption(running("claude-code", Optional.of("opus")));

        assertEquals("claude-code · opus", nameOf(option, "claude-code"));
        assertEquals("cursor", nameOf(option, "cursor"), "no model is claimed for a brain that is not running");
    }

    @Test
    @DisplayName("a running brain the registry does not list is still named with its model")
    void aRunningBrainOutsideTheRegistryIsNamedWithItsModel() {
        JsonNode option = principalOption(running("codex", Optional.of("gpt-5")));

        assertNotNull(option);
        assertEquals("codex · gpt-5", nameOf(option, "codex"));
    }

    /** A brain on {@code brain} that offers {@code models} (value, name) and is on the first; it records what it is asked to do. */
    private static final class Offering implements AgentBrain {
        final String brain;
        final List<org.jawata.mcp.execution.AcpBrainSession.Model> models;
        final List<String> asked = new ArrayList<>();
        String onValue;

        Offering(String brain, String... valueAndName) {
            this.brain = brain;
            List<org.jawata.mcp.execution.AcpBrainSession.Model> list = new ArrayList<>();
            for (int at = 0; at < valueAndName.length; at += 2) {
                list.add(new org.jawata.mcp.execution.AcpBrainSession.Model(valueAndName[at], valueAndName[at + 1]));
            }
            this.models = list;
            this.onValue = list.get(0).value();
        }

        @Override
        public Optional<String> currentBrain() {
            return Optional.of(brain);
        }

        @Override
        public Optional<String> currentModelValue() {
            return Optional.of(onValue);
        }

        @Override
        public Optional<String> currentModel() {
            return models.stream().filter(m -> m.value().equals(onValue)).map(org.jawata.mcp.execution.AcpBrainSession.Model::name).findFirst();
        }

        @Override
        public List<org.jawata.mcp.execution.AcpBrainSession.Model> models() {
            return models;
        }

        @Override
        public boolean useModel(String model) {
            asked.add("model " + model);
            if (models.stream().noneMatch(m -> m.value().equals(model))) {
                return false;
            }
            onValue = model;
            return true;
        }

        @Override
        public boolean useBrain(String registry, String name) {
            asked.add("brain " + name);
            return true;
        }

        @Override
        public void answer(String prompt, Consumer<String> chunks) {
            chunks.accept("ok");
        }
    }

    /** The agent's answer to the person choosing {@code value} for the principal's brain option. */
    private static JsonNode choose(AgentBrain brain, String value, List<JsonNode> received) {
        AcpServer server = new AcpServer(brain, List::of, message -> received.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.offerBrains(() -> REGISTRY);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));
        String session = received.stream().filter(m -> m.has("result") && m.path("result").has("sessionId")).findFirst().orElseThrow()
            .path("result").path("sessionId").asText();
        received.clear();
        server.receive(JsonRpcMessage.request(2, "session/set_config_option", JSON.createObjectNode()
            .put("sessionId", session).put("configId", AcpServer.PRINCIPAL_BRAIN).put("value", value)));
        return received.stream().filter(m -> m.has("id") && (m.has("result") || m.has("error"))).findFirst().orElseThrow();
    }

    /** The agent's answer to the person choosing {@code value}, with {@code offer} installed as what lists the models of an agent that is not running. */
    private static JsonNode choose(AgentBrain brain, String value, List<JsonNode> received, Function<String, List<AcpBrainSession.Model>> offer) {
        AcpServer server = new AcpServer(brain, List::of, message -> received.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.offerBrains(() -> REGISTRY);
        server.offerModelChoices(offer);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));
        String session = received.stream().filter(m -> m.has("result") && m.path("result").has("sessionId")).findFirst().orElseThrow()
            .path("result").path("sessionId").asText();
        received.clear();
        server.receive(JsonRpcMessage.request(2, "session/set_config_option", JSON.createObjectNode()
            .put("sessionId", session).put("configId", AcpServer.PRINCIPAL_BRAIN).put("value", value)));
        return received.stream().filter(m -> m.has("id") && (m.has("result") || m.has("error"))).findFirst().orElseThrow();
    }

    /** What an agent that is not running offers in the tests of its row: three models whose names no list in the source holds. */
    private static List<AcpBrainSession.Model> claudeModelsOffered() {
        return List.of(new AcpBrainSession.Model("opus[1m]", "Opus 5.5 Test"), new AcpBrainSession.Model("fable", "Fable Test"),
            new AcpBrainSession.Model("sonnet[effort=high,fast=true]", "Sonnet Test"));
    }

    /**
     * The principal's brain option of the first {@code config_option_update} among {@code received} that holds exactly {@code rows} rows of
     * {@code agent}, none of them the agent's single registry row (the bare agent name as value), so it is the rows the background read found;
     * waited for at most 5 seconds because that read sends it from another thread; fails with every message received.
     */
    private static JsonNode awaitBrainOptionWith(List<JsonNode> received, String agent, int rows) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            for (JsonNode message : received) {
                JsonNode update = message.path("params").path("update");
                if (!"session/update".equals(message.path("method").asText()) || !"config_option_update".equals(update.path("sessionUpdate").asText())) {
                    continue;
                }
                for (JsonNode option : update.path("configOptions")) {
                    if (AcpServer.PRINCIPAL_BRAIN.equals(option.path("id").asText()) && rowsOfAgent(option, agent).size() == rows
                        && rowsOfAgent(option, agent).stream().noneMatch(row -> agent.equals(row.path("value").asText()))) {
                        return option;
                    }
                }
            }
            if (System.nanoTime() > until) {
                throw new AssertionError("no config_option_update with " + rows + " row(s) of " + agent + " within 5 s: " + received);
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("the menu is built and answered while an agent's models are still being read: the read blocks, session/new returns, and the rows arrive in an update")
    void menuBuildReturnsWhileASessionStartIsBlocked() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger answered = new AtomicInteger();
        Function<String, List<AcpBrainSession.Model>> offer = agent -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            answered.incrementAndGet();
            return "claude-code".equals(agent) ? claudeModelsOffered() : List.of();
        };
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        AcpServer server = new AcpServer(new Offering("cursor", "default[]", "Auto", "grok-4.5", "grok-4.5"), List::of,
            message -> received.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.offerBrains(() -> REGISTRY);
        server.offerModelChoices(offer);
        ExecutorService opener = Executors.newSingleThreadExecutor(job -> {
            Thread thread = new Thread(job, "test-session-new");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> opening = opener.submit(() -> server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace"))));
            try {
                opening.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException blocked) {
                throw new AssertionError("session/new did not return within 5 s while the read of an agent's models was blocked: the menu build waits on it", blocked);
            }
            assertEquals(0, answered.get(), "the read had not answered when session/new returned");
            JsonNode option = brainOptionIn(received);
            assertEquals(1, rowsOfAgent(option, "claude-code").size(), "one row for the agent that is not running");
            assertEquals("claude-code", rowsOfAgent(option, "claude-code").get(0).path("value").asText());
            assertEquals("claude-code · sonnet", nameOf(option, "claude-code"));
            assertEquals(2, rowsOfAgent(option, "cursor").size(), "beside the running brain's rows");
        } finally {
            release.countDown();
            opener.shutdownNow();
        }

        JsonNode told = awaitBrainOptionWith(received, "claude-code", 3);
        assertEquals("claude-code · Opus 5.5 Test (1m)", nameOf(told, "claude-code|opus[1m]"));
        server.closed();
    }

    /** A server over {@code brain} whose messages go to {@code received} (thread-safe, because a background read sends from another thread). */
    private static AcpServer serverOver(AgentBrain brain, Function<String, List<AcpBrainSession.Model>> offer, List<JsonNode> received) {
        AcpServer server = new AcpServer(brain, List::of, message -> received.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.offerBrains(() -> REGISTRY);
        server.offerModelChoices(offer);
        return server;
    }

    /** The brain option of the answer to request {@code id} among {@code received}, which holds the options as they stand. */
    private static JsonNode brainOptionAnsweredTo(List<JsonNode> received, int id) {
        JsonNode answer = received.stream().filter(m -> m.has("id") && m.path("id").asInt() == id && m.has("result")).findFirst().orElseThrow();
        for (JsonNode option : answer.path("result").path("configOptions")) {
            if (AcpServer.PRINCIPAL_BRAIN.equals(option.path("id").asText())) {
                return option;
            }
        }
        throw new AssertionError("no principal brain option in " + answer);
    }

    /** What a later build shows: the brain option in the answer to the person switching autonomy on in the session {@code server} opened. */
    private static JsonNode brainOptionOfALaterBuild(AcpServer server, List<JsonNode> received) {
        String session = received.stream().filter(m -> m.has("result") && m.path("result").has("sessionId")).findFirst().orElseThrow()
            .path("result").path("sessionId").asText();
        server.receive(JsonRpcMessage.request(7, "session/set_config_option", JSON.createObjectNode()
            .put("sessionId", session).put("configId", AcpServer.AUTONOMY).put("value", "on")));
        return brainOptionAnsweredTo(received, 7);
    }

    /**
     * The principal's brain option of the first update that holds {@code rows} rows of {@code agent}, after {@code session/new} on a server
     * with {@code offer} installed: the rows of an agent that is not running arrive in the background, not in the answer.
     */
    private static JsonNode principalOptionOfTheUpdate(AgentBrain brain, Function<String, List<AcpBrainSession.Model>> offer, String agent, int rows)
        throws InterruptedException {
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        AcpServer server = serverOver(brain, offer, received);
        try {
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));
            return awaitBrainOptionWith(received, agent, rows);
        } finally {
            server.closed();
        }
    }

    @Test
    @DisplayName("the models of an agent that is not running are rows read from its own session in the background, once, and sent to the chat when they arrive, with fast, effort and context on the row")
    void modelsOfAnAgentThatIsNotRunning() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        Function<String, List<AcpBrainSession.Model>> offer = agent -> {
            asked.add(agent);
            return "claude-code".equals(agent) ? claudeModelsOffered() : List.of();
        };
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        AcpServer server = serverOver(new Offering("cursor", "default[]", "Auto", "grok-4.5", "grok-4.5"), offer, received);
        assertEquals(List.of(), asked, "nothing is read before the menu is opened");

        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));
        JsonNode first = brainOptionIn(received);
        assertEquals(1, rowsOfAgent(first, "claude-code").size(), "the first build has the agent's one row");
        assertEquals("claude-code", rowsOfAgent(first, "claude-code").get(0).path("value").asText());

        JsonNode option = awaitBrainOptionWith(received, "claude-code", 3);
        assertEquals(3, rowsOfAgent(option, "claude-code").size(), "claude-code is not the only Claude row");
        assertFalse(rowsOf(option).stream().anyMatch(row -> "claude-code".equals(row.path("value").asText())), "no bare claude-code row");
        assertEquals("claude-code · Sonnet Test (effort=high, fast=true)", nameOf(option, "claude-code|sonnet[effort=high,fast=true]"));
        assertEquals("claude-code · Opus 5.5 Test (1m)", nameOf(option, "claude-code|opus[1m]"));
        assertEquals("claude-code · Fable Test", nameOf(option, "claude-code|fable"));
        for (AcpBrainSession.Model model : claudeModelsOffered()) {
            assertFalse(model.value().equals(nameOf(option, "claude-code|" + model.value())), "the name is not the value");
        }

        assertEquals(List.of("claude-code"), asked, "asked once, for the agent that is not running, and the running one never");
        JsonNode later = brainOptionOfALaterBuild(server, received);
        assertEquals(3, rowsOfAgent(later, "claude-code").size(), "a later build shows the answer that was held");
        assertEquals(List.of("claude-code"), asked, "a rebuild of the menu asks nobody");
        server.closed();

        Offering brain = new Offering("cursor", "default[]", "Auto", "grok-4.5", "grok-4.5");
        choose(brain, "claude-code|sonnet[effort=high,fast=true]", new CopyOnWriteArrayList<>(), offer);
        assertEquals(List.of("brain claude-code", "model sonnet[effort=high,fast=true]"), brain.asked);
    }

    /**
     * An agent whose models cannot be read keeps its single row: {@code read} is what the background read does once it is asked; the test waits
     * until it was asked and then checks the answer and a later build both still show the single row beside the running brain's rows.
     */
    private static void theSingleRowStaysWhen(Function<String, List<AcpBrainSession.Model>> read) throws InterruptedException {
        CountDownLatch asked = new CountDownLatch(1);
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        AcpServer server = serverOver(new Offering("cursor", "default[]", "Auto"), agent -> {
            asked.countDown();
            return read.apply(agent);
        }, received);
        try {
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));
            JsonNode first = brainOptionIn(received);
            assertEquals("claude-code · sonnet", nameOf(first, "claude-code"));
            assertEquals("cursor · Auto", nameOf(first, "cursor|default[]"));
            assertEquals(true, asked.await(5, TimeUnit.SECONDS), "the models of the agent that is not running were never asked for: " + received);

            JsonNode later = brainOptionOfALaterBuild(server, received);
            assertEquals("claude-code · sonnet", nameOf(later, "claude-code"));
            assertEquals("cursor · Auto", nameOf(later, "cursor|default[]"));
            assertEquals(1, rowsOfAgent(later, "claude-code").size());
        } finally {
            server.closed();
        }
    }

    @Test
    @DisplayName("an agent whose session cannot be read keeps its single row, and the menu still builds with the running brain's rows")
    void aThrowingOfferKeepsTheSingleRow() throws Exception {
        theSingleRowStaysWhen(agent -> {
            throw new IllegalStateException("no session for " + agent);
        });
    }

    @Test
    @DisplayName("an agent that offers no models keeps its single row")
    void anEmptyOfferKeepsTheSingleRow() throws Exception {
        theSingleRowStaysWhen(agent -> List.of());
    }

    @Test
    @DisplayName("a session/new that arrives after the server is closed does not throw: the background read of the models is not started")
    void aMenuReadAfterTheServerIsClosedDoesNotThrow() {
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        AcpServer server = serverOver(new Offering("cursor", "default[]", "Auto"), agent -> claudeModelsOffered(), received);
        server.closed();

        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));
    }

    @Test
    @DisplayName("the running brain's rows keep their value and name, and every row names its agent")
    void theRunningBrainsRowsKeepTheirNamesAndEveryRowNamesItsAgent() throws Exception {
        JsonNode option = principalOptionOfTheUpdate(new Offering("cursor", "default[]", "Auto", "grok-4.5", "grok-4.5"), agent -> claudeModelsOffered(),
            "claude-code", 3);

        assertEquals("cursor · Auto", nameOf(option, "cursor|default[]"));
        assertEquals("cursor · grok-4.5", nameOf(option, "cursor|grok-4.5"));
        assertEquals(2, rowsOfAgent(option, "cursor").size());
        assertEquals(3, rowsOfAgent(option, "claude-code").size());
        assertEquals(5, rowsOf(option).size());
        assertEquals("agent", AcpServer.AGENT_FIELD);
        for (JsonNode row : rowsOf(option)) {
            assertEquals(true, row.has(AcpServer.AGENT_FIELD), "a row without an agent: " + row);
        }
    }

    /** A brain on {@code cursor} offering {@code count} models, so with the single row of claude-code the menu has {@code count + 1} rows. */
    private static Offering cursorOffering(int count) {
        String[] valueAndName = new String[count * 2];
        for (int at = 0; at < count; at++) {
            valueAndName[at * 2] = "model-" + at;
            valueAndName[at * 2 + 1] = "Model " + at;
        }
        return new Offering("cursor", valueAndName);
    }

    @Test
    @DisplayName("a menu of 12 rows or fewer stays a flat list, and one of 13 is grouped by agent, with every row still naming its agent")
    void aLongMenuIsGroupedByAgent() {
        assertEquals(12, AcpServer.LONG_MENU_ROWS);
        assertEquals("groupBy", AcpServer.GROUP_BY_FIELD);

        JsonNode twelve = principalOption(cursorOffering(11));
        assertEquals(12, rowsOf(twelve).size());
        assertFalse(twelve.has(AcpServer.GROUP_BY_FIELD), "12 rows is flat: " + twelve);

        JsonNode thirteen = principalOption(cursorOffering(12));
        assertEquals(13, rowsOf(thirteen).size());
        assertEquals("agent", thirteen.path(AcpServer.GROUP_BY_FIELD).asText());
        for (JsonNode row : rowsOf(thirteen)) {
            assertEquals(true, row.has(AcpServer.AGENT_FIELD), "a row without an agent: " + row);
        }
    }

    @Test
    @DisplayName("the host is told the options again once the brain's session is ready, because that is when its model becomes known")
    void theHostIsToldWhenTheBrainIsReady() {
        List<JsonNode> received = new ArrayList<>();
        AcpServer server = new AcpServer(new Offering("cursor", "default[]", "Auto", "grok-4.5", "grok-4.5"), List::of,
            message -> received.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.offerBrains(() -> REGISTRY);

        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));

        JsonNode told = received.stream().filter(m -> "session/update".equals(m.path("method").asText())
            && "config_option_update".equals(m.path("params").path("update").path("sessionUpdate").asText())).findFirst().orElse(null);
        assertNotNull(told, "no config_option_update was sent: " + received);
        boolean namesModel = false;
        for (JsonNode option : told.path("params").path("update").path("configOptions")) {
            if (AcpServer.PRINCIPAL_BRAIN.equals(option.path("id").asText())) {
                namesModel = "cursor|default[]".equals(option.path("currentValue").asText());
            }
        }
        assertEquals(true, namesModel, told.toString());
    }

    /** A brain whose session opens with its first answer: it names no model before that, and its own models and the one it is on from then. */
    private static final class OpensWithItsFirstAnswer implements AgentBrain {
        boolean open;

        @Override
        public Optional<String> currentBrain() {
            return Optional.of("claude-code");
        }

        @Override
        public Optional<String> currentModelValue() {
            return open ? Optional.of("fable") : Optional.empty();
        }

        @Override
        public Optional<String> currentModel() {
            return open ? Optional.of("Fable 5.2") : Optional.empty();
        }

        @Override
        public List<org.jawata.mcp.execution.AcpBrainSession.Model> models() {
            return open ? List.of(new org.jawata.mcp.execution.AcpBrainSession.Model("opus", "Opus 5.5"),
                new org.jawata.mcp.execution.AcpBrainSession.Model("fable", "Fable 5.2")) : List.of();
        }

        @Override
        public void answer(String prompt, Consumer<String> chunks) {
            open = true;
            chunks.accept("ok");
        }
    }

    /** The current value of the principal's brain option in each options update among {@code received}, in the order they were sent. */
    private static List<String> brainRowsTold(List<JsonNode> received) {
        List<String> told = new ArrayList<>();
        for (JsonNode message : received) {
            JsonNode update = message.path("params").path("update");
            if (!"session/update".equals(message.path("method").asText()) || !"config_option_update".equals(update.path("sessionUpdate").asText())) {
                continue;
            }
            for (JsonNode option : update.path("configOptions")) {
                if (AcpServer.PRINCIPAL_BRAIN.equals(option.path("id").asText())) {
                    told.add(option.path("currentValue").asText());
                }
            }
        }
        return told;
    }

    @Test
    @DisplayName("a brain whose session opens during a turn: the host is told its own models and the one it is on in that turn, and not again while nothing changes")
    void theHostIsToldTheBrainsOwnModelsWhenItsSessionOpensInATurn() {
        List<JsonNode> received = new ArrayList<>();
        AcpServer server = new AcpServer(new OpensWithItsFirstAnswer(), List::of, message -> received.add(JSON.valueToTree(message)),
            TurnBoundary.open(), AgentRecorder.off(), null);
        server.offerBrains(() -> REGISTRY);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/workspace")));
        String session = received.stream().filter(m -> m.has("result") && m.path("result").has("sessionId")).findFirst().orElseThrow()
            .path("result").path("sessionId").asText();
        assertEquals(List.of("claude-code"), brainRowsTold(received), "CONTROL: before its session is open the brain is named alone");
        received.clear();

        ObjectNode first = JSON.createObjectNode().put("sessionId", session);
        first.putArray("prompt").addObject().put("type", "text").put("text", "hello");
        server.receive(JsonRpcMessage.request(2, "session/prompt", first));

        assertEquals(List.of("claude-code|fable"), brainRowsTold(received), "the row was not corrected in the turn that opened the brain's session: " + received);
        received.clear();

        ObjectNode second = JSON.createObjectNode().put("sessionId", session);
        second.putArray("prompt").addObject().put("type", "text").put("text", "again");
        server.receive(JsonRpcMessage.request(3, "session/prompt", second));

        assertEquals(List.of(), brainRowsTold(received), "nothing changed, so the host is not told again");
    }

    @Test
    @DisplayName("a brain that offers models has each as a choice, and so does an agent that is not running, read from its own session")
    void everyModelTheBrainOffersIsAChoice() throws Exception {
        JsonNode option = principalOptionOfTheUpdate(new Offering("cursor", "default[]", "Auto", "grok-4.5[effort=high]", "grok-4.5", "composer-2.5", "composer-2.5"),
            agent -> "claude-code".equals(agent) ? List.of(new AcpBrainSession.Model("haiku", "Haiku Existing Test")) : List.of(), "claude-code", 1);
        assertEquals("claude-code|haiku", rowsOfAgent(option, "claude-code").get(0).path("value").asText(), "the update, not the registry's single row");

        assertEquals("cursor|default[]", option.path("currentValue").asText(), "the model it is on");
        assertEquals("cursor · Auto", nameOf(option, "cursor|default[]"));
        assertEquals("cursor · grok-4.5", nameOf(option, "cursor|grok-4.5[effort=high]"));
        assertEquals("cursor · composer-2.5", nameOf(option, "cursor|composer-2.5"));
        assertEquals("claude-code · Haiku Existing Test", nameOf(option, "claude-code|haiku"), "the other agent's rows come from its own offer");
        assertEquals(1, rowsOfAgent(option, "claude-code").size(), "and the registry's single row is not kept beside them");
    }

    @Test
    @DisplayName("choosing a model of the running brain moves its session and does not start the brain again")
    void aPickOfAModelOfTheRunningBrainMovesTheSession() {
        Offering brain = new Offering("cursor", "default[]", "Auto", "grok-4.5[effort=high]", "grok-4.5");

        JsonNode answer = choose(brain, "cursor|grok-4.5[effort=high]", new ArrayList<>());

        assertEquals(List.of("model grok-4.5[effort=high]"), brain.asked, "the model was set and the brain was not replaced");
        JsonNode option = null;
        for (JsonNode candidate : answer.path("result").path("configOptions")) {
            if (AcpServer.PRINCIPAL_BRAIN.equals(candidate.path("id").asText())) {
                option = candidate;
            }
        }
        assertNotNull(option, answer.toString());
        assertEquals("cursor|grok-4.5[effort=high]", option.path("currentValue").asText(), "the answer shows the model it is now on");
    }

    @Test
    @DisplayName("choosing a model the brain does not offer is refused with the brain and the model named, and nothing else changes")
    void aModelTheBrainDoesNotOfferIsRefused() {
        Offering brain = new Offering("cursor", "default[]", "Auto");

        JsonNode answer = choose(brain, "cursor|no-such-model", new ArrayList<>());

        assertEquals("cursor does not offer the model no-such-model", answer.path("error").path("message").asText());
        assertEquals("default[]", brain.onValue);
    }

    @Test
    @DisplayName("choosing another brain starts that brain, and a model chosen with it is asked of it afterwards")
    void aPickOfAnotherBrainAndModelStartsTheBrainThenAsksForTheModel() {
        Offering brain = new Offering("cursor", "default[]", "Auto");

        JsonNode answer = choose(brain, "claude-code|opus", new ArrayList<>());

        assertNotNull(answer.path("result"), answer.toString());
        assertEquals(List.of("brain claude-code", "model opus"), brain.asked);
    }

    @Test
    @DisplayName("the offer the agent face installs lists nothing for an engine without settings, and what the registry's agent lists for one with a registry")
    void agentFaceOpenInstallsTheModelOffer() throws Exception {
        assertEquals(List.of(), AgentFace.modelsOfferedBy(engineHolding(null), "claude-code"), "an engine without settings offers no model");

        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("jawata-model-offer-");
        try {
            java.nio.file.Files.write(dir.resolve("FakeAcpAgent.java"), standInAgentSource());
            ObjectNode registry = JSON.createObjectNode();
            ObjectNode entry = registry.putObject("claude-code");
            entry.putArray("command").add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .add(dir.resolve("FakeAcpAgent.java").toString());
            entry.putObject("env").put("FAKE_ACP_STATE", dir.toString()).put("FAKE_ACP_MODELS", "opus=Opus 5.5,haiku=Haiku 5.5");
            String registryText = registry.toString();

            List<AcpBrainSession.Model> direct = AcpBrainSession.modelsOfferedIn(registryText, "claude-code");
            assertFalse(direct.isEmpty(), "CONTROL: the stand-in agent listed no model, so the comparison would prove nothing");

            assertEquals(direct, AgentFace.modelsOfferedBy(engineHolding(registryText), "claude-code"),
                "the offer did not answer what the registry's agent lists");
        } finally {
            try (var walk = java.nio.file.Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * The text of the stand-in ACP agent: from the test resources where the runner has them on its class path, and otherwise from the
     * {@code test-resources} folder found above the working directory or above where this class was loaded from (the project model of a
     * workspace run does not list that folder as a source root).
     */
    private static byte[] standInAgentSource() throws Exception {
        try (java.io.InputStream in = PrincipalModelOptionTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        List<java.nio.file.Path> starts = List.of(java.nio.file.Path.of("").toAbsolutePath(),
            java.nio.file.Path.of(PrincipalModelOptionTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath());
        for (java.nio.file.Path start : starts) {
            for (java.nio.file.Path up = start; up != null; up = up.getParent()) {
                for (String root : List.of("test-resources", "org.jawata.mcp.tests/test-resources")) {
                    java.nio.file.Path found = up.resolve(root).resolve("acp").resolve("FakeAcpAgent.java.txt");
                    if (java.nio.file.Files.isRegularFile(found)) {
                        return java.nio.file.Files.readAllBytes(found);
                    }
                }
            }
        }
        throw new AssertionError("the stand-in agent is neither among the test resources nor in a test-resources folder above " + starts);
    }

    /** An engine whose settings hold {@code registry} as the brains registry, or whose settings are absent when {@code registry} is null. */
    private static org.jawata.mcp.engine.EngineApi engineHolding(String registry) {
        return new org.jawata.mcp.engine.EngineApi() {
            @Override
            public org.jawata.mcp.engine.ToolCalls tools() {
                return null;
            }

            @Override
            public org.jawata.mcp.engine.EngineState state() {
                return null;
            }

            @Override
            public org.jawata.spi.host.PersonChannel person() {
                return null;
            }

            @Override
            public org.jawata.spi.host.EngineSettings settings() {
                return registry == null ? null
                    : key -> org.jawata.mcp.execution.Hirer.BRAINS_SETTING.equals(key) ? Optional.of(registry) : Optional.empty();
            }

            @Override
            public org.jawata.spi.host.HostCapabilities host() {
                return null;
            }

            @Override
            public Optional<org.jawata.spi.host.McpEndpoint> mcp() {
                return Optional.empty();
            }
        };
    }
}
