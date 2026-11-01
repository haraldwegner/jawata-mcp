package org.jawata.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.jawata.mcp.engine.PromptSource;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S14 (D21): the handler answers {@code prompts/list} and {@code prompts/get} from the prompt source it is given, advertises the
 * capability only while a prompt is there to offer, and does not know the methods at all with no source.
 */
class McpProtocolHandlerPromptsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicReference<List<PromptSource.Prompt>> offered = new AtomicReference<>(List.of());
    private McpProtocolHandler handler;

    private final PromptSource source = new PromptSource() {
        @Override
        public List<Prompt> list() {
            return offered.get();
        }

        @Override
        public Optional<String> text(String name) {
            return offered.get().stream().filter(p -> p.name().equals(name)).findFirst().map(p -> "text of " + p.name());
        }
    };

    @BeforeEach
    void setUp() {
        handler = new McpProtocolHandler(new ToolRegistry());
    }

    private JsonNode ask(String method, String params) throws Exception {
        return JSON.readTree(handler.processMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}"));
    }

    @Test
    @DisplayName("the capability is advertised while a seat is on offer, and not otherwise")
    void capabilityOnlyWhileSeatsExist() throws Exception {
        handler.setPromptSource(source);
        assertFalse(ask("initialize", "{}").get("result").get("capabilities").has("prompts"));

        offered.set(List.of(new PromptSource.Prompt("refactor", "architect (/refactor)", "Reviews the design.")));

        assertTrue(ask("initialize", "{}").get("result").get("capabilities").has("prompts"));
    }

    @Test
    @DisplayName("with no prompt source wired, no capability is advertised and both methods are not found")
    void withNoSourceTheMethodsAreNotFound() throws Exception {
        assertFalse(ask("initialize", "{}").get("result").get("capabilities").has("prompts"));
        assertEquals(-32601, ask("prompts/list", "{}").get("error").get("code").asInt());
        assertEquals(-32601, ask("prompts/get", "{\"name\":\"x\"}").get("error").get("code").asInt());
    }

    @Test
    @DisplayName("prompts/list gives each prompt its name, title and description")
    void listGivesNameTitleDescription() throws Exception {
        offered.set(List.of(new PromptSource.Prompt("refactor", "architect (/refactor)", "Reviews the design.")));
        handler.setPromptSource(source);

        JsonNode row = ask("prompts/list", "{}").get("result").get("prompts").get(0);

        assertEquals("refactor", row.get("name").asText());
        assertEquals("architect (/refactor)", row.get("title").asText());
        assertEquals("Reviews the design.", row.get("description").asText());
    }

    @Test
    @DisplayName("prompts/list answers an empty list, not an error, when the source has nothing now")
    void listIsEmptyWhenNothingIsOffered() throws Exception {
        handler.setPromptSource(source);

        JsonNode result = ask("prompts/list", "{}").get("result");

        assertEquals(0, result.get("prompts").size());
    }

    @Test
    @DisplayName("prompts/get returns the text as one user message with the prompt's description")
    void getReturnsTheTextAsOneUserMessage() throws Exception {
        offered.set(List.of(new PromptSource.Prompt("refactor", "architect (/refactor)", "Reviews the design.")));
        handler.setPromptSource(source);

        JsonNode result = ask("prompts/get", "{\"name\":\"refactor\"}").get("result");

        assertEquals("Reviews the design.", result.get("description").asText());
        assertEquals(1, result.get("messages").size());
        JsonNode message = result.get("messages").get(0);
        assertEquals("user", message.get("role").asText());
        assertEquals("text", message.get("content").get("type").asText());
        assertEquals("text of refactor", message.get("content").get("text").asText());
    }

    @Test
    @DisplayName("prompts/get with a name no seat has, or no name, is an invalid-parameters error")
    void getWithAnUnknownOrMissingNameIsInvalidParams() throws Exception {
        handler.setPromptSource(source);

        assertEquals(-32602, ask("prompts/get", "{\"name\":\"nobody\"}").get("error").get("code").asInt());
        assertEquals(-32602, ask("prompts/get", "{}").get("error").get("code").asInt());
    }
}
