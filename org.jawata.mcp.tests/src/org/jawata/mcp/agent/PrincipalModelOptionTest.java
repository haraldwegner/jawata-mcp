package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    @Test
    @DisplayName("a brain that offers models has each as a choice, named with the brain, and the one it is on is the current value")
    void everyModelTheBrainOffersIsAChoice() {
        JsonNode option = principalOption(new Offering("cursor", "default[]", "Auto", "grok-4.5[effort=high]", "grok-4.5", "composer-2.5", "composer-2.5"));

        assertEquals("cursor|default[]", option.path("currentValue").asText(), "the model it is on");
        assertEquals("cursor · Auto", nameOf(option, "cursor|default[]"));
        assertEquals("cursor · grok-4.5", nameOf(option, "cursor|grok-4.5[effort=high]"));
        assertEquals("cursor · composer-2.5", nameOf(option, "cursor|composer-2.5"));
        assertEquals("claude-code · sonnet", nameOf(option, "claude-code"), "another brain keeps the registry's model, and none of the running one's");
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
}
