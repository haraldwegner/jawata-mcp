package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.jawata.mcp.execution.PersonsWords;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, D57 (j): a release is made only on his word, so what the release tool reads must be what HE typed. The agent face's prompt from
 * the host writes it, under the key the chat's principal brain presents on its tool calls; the same prompt from a hired brain does not.
 */
class PersonsWordsFromTheHostTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("28h D57 (j): his message from the host is recorded for the principal's key, and a brain's prompt on the same session is not")
    void onlyTheHostsPromptIsHisWord() {
        String key = "principal-words-" + System.nanoTime();
        List<JsonNode> said = new ArrayList<>();
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                chunks.accept("ok");
            }

            @Override
            public Optional<String> toolSessionKey() {
                return Optional.of(key);
            }
        };
        AcpServer server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();

        server.receive(prompt(2, session, "Release it"), Channel.HOST);
        assertTrue(PersonsWords.gaveTheReleaseWord(key), "his word was not recorded: " + PersonsWords.lastSaid(key));

        server.receive(prompt(3, session, "go on with the work"), Channel.BRAIN);
        assertEquals(Optional.of("Release it"), PersonsWords.lastSaid(key), "a brain's prompt was taken for his word");

        server.receive(prompt(4, session, "not yet, carry on"), Channel.HOST);
        assertFalse(PersonsWords.gaveTheReleaseWord(key), "an older message still counted after he spoke again");
    }

    private static JsonRpcMessage prompt(int id, String session, String text) {
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", text);
        return JsonRpcMessage.request(id, "session/prompt", params);
    }
}
