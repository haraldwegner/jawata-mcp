package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jawata.mcp.execution.SeatBrainPicks;
import org.jawata.mcp.execution.SeatHire;
import org.jawata.mcp.execution.SeatTree;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S9, contract R5 and E6: the model of one seat is a select config option, {@code seat-model:<seat>}, that the host sets with
 * {@code session/set_config_option}; the choices are the brains the registry offers and the answer carries the options as they now stand.
 * Each test takes a seat of its own and hands it back to its seat file at the end, because the picks are one store for the whole process.
 */
class AgentControlSeatModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REGISTRY = "{\"claude-code\":{\"command\":[\"claude-agent-acp\"],\"vendor\":\"anthropic\",\"model\":\"opus\"},"
        + "\"codex\":{\"command\":[\"codex-acp\"],\"vendor\":\"openai\",\"model\":\"gpt-5\"},"
        + "\"bare\":{\"command\":[\"bare-acp\"]}}";

    /** An agent with a registry and one open session, on which options are read and set. */
    private static final class Connection {
        final List<JsonNode> said = new ArrayList<>();
        final AcpServer server;
        final String session;
        final JsonNode opened;

        Connection() {
            server = new AcpServer((prompt, chunks) -> chunks.accept("x"), List::of, message -> said.add(JSON.valueToTree(message)),
                TurnBoundary.open(), AgentRecorder.off(), null, null);
            server.offerBrains(() -> REGISTRY);
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            opened = said.stream().filter(m -> m.path("id").asInt() == 1).findFirst().orElseThrow().path("result");
            session = opened.path("sessionId").asText();
        }

        JsonNode set(Channel from, String configId, String value) {
            said.clear();
            var params = JSON.createObjectNode().put("sessionId", session).put("configId", configId).put("value", value);
            server.receive(JsonRpcMessage.request(9, "session/set_config_option", params), from);
            return said.stream().filter(m -> m.path("id").asInt() == 9).findFirst().orElseThrow();
        }
    }

    private static JsonNode optionOf(JsonNode configOptions, String id) {
        for (JsonNode option : configOptions) {
            if (id.equals(option.path("id").asText())) {
                return option;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a new session shows autonomy and one model select per hired seat, each in the category model, offering the seat file and the brains the registry names that a hire can use")
    void everyHiredSeatHasAModelSelect() {
        JsonNode options = new Connection().opened.path("configOptions");

        assertEquals(SeatTree.standard().seats().size() + 1, options.size(), options.toString());
        assertNotNull(optionOf(options, "autonomy"));
        JsonNode developer = optionOf(options, "seat-model:developer");
        assertNotNull(developer, options.toString());
        assertEquals("model", developer.path("category").asText());
        assertEquals("select", developer.path("type").asText());
        assertEquals("seat-file", developer.path("currentValue").asText());
        List<String> values = new ArrayList<>();
        developer.path("options").forEach(choice -> values.add(choice.path("value").asText()));
        assertEquals(List.of("seat-file", "claude-code", "codex"), values, "bare names no dialect and must not be offered: " + developer);
        assertEquals("claude-code · opus", developer.path("options").get(1).path("name").asText());
        assertNull(optionOf(options, "seat-model:main"), "nobody hires main, so it has no seat option");
    }

    @Test
    @DisplayName("the person's pick of a brain is answered in the response with that brain as current value, kept for the seat's next hire with the registry's model, and undone by choosing the seat file")
    void aPickIsAnsweredAndRememberedAndUndone() {
        Connection connection = new Connection();
        try {
            JsonNode picked = connection.set(Channel.HOST, "seat-model:developer", "claude-code");

            assertNull(picked.get("error"), picked.toString());
            assertEquals("claude-code", optionOf(picked.path("result").path("configOptions"), "seat-model:developer").path("currentValue").asText());
            assertEquals(Optional.of(new SeatBrainPicks.Pick(SeatHire.Brain.CLAUDE_CODE, "opus")), SeatBrainPicks.shared().forSeat("developer"));

            JsonNode back = connection.set(Channel.HOST, "seat-model:developer", "seat-file");

            assertEquals("seat-file", optionOf(back.path("result").path("configOptions"), "seat-model:developer").path("currentValue").asText());
            assertTrue(SeatBrainPicks.shared().forSeat("developer").isEmpty(), "choosing the seat file left a pick behind");
        } finally {
            connection.set(Channel.HOST, "seat-model:developer", "seat-file");
        }
    }

    @Test
    @DisplayName("a brain whose command line takes no model is picked without one, so the option never claims a model nobody will use")
    void aBrainThatTakesNoModelIsPickedWithoutOne() {
        Connection connection = new Connection();
        try {
            connection.set(Channel.HOST, "seat-model:profiler", "codex");

            assertEquals(Optional.of(new SeatBrainPicks.Pick(SeatHire.Brain.CODEX, "")), SeatBrainPicks.shared().forSeat("profiler"));
        } finally {
            connection.set(Channel.HOST, "seat-model:profiler", "seat-file");
        }
    }

    @Test
    @DisplayName("a pick from a hired agent, for a seat the tree does not have, or of a brain the registry does not offer is refused and nothing is picked")
    void whatCannotBePickedIsRefused() {
        Connection connection = new Connection();
        try {
            JsonNode fromAgent = connection.set(Channel.BRAIN, "seat-model:debugger", "codex");
            JsonNode noSeat = connection.set(Channel.HOST, "seat-model:nobody", "codex");
            JsonNode notOffered = connection.set(Channel.HOST, "seat-model:debugger", "bare");
            JsonNode unknown = connection.set(Channel.HOST, "seat-model:debugger", "no-such-brain");
            JsonNode missing = connection.set(Channel.HOST, "seat-model:debugger", null);

            assertEquals(-32003, fromAgent.path("error").path("code").asInt(), fromAgent.toString());
            assertEquals(-32602, noSeat.path("error").path("code").asInt(), noSeat.toString());
            assertEquals(-32602, notOffered.path("error").path("code").asInt(), notOffered.toString());
            assertEquals(-32602, unknown.path("error").path("code").asInt(), unknown.toString());
            assertEquals(-32602, missing.path("error").path("code").asInt(), missing.toString());
            assertTrue(SeatBrainPicks.shared().forSeat("debugger").isEmpty(), "a refused request left a pick");
        } finally {
            connection.set(Channel.HOST, "seat-model:debugger", "seat-file");
        }
    }
}
