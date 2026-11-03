package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Sprint 28h, S9 (D10): the agent serves the per-role figures of the measures, read from the hires it has seen and from nothing a seat said
 * about itself. A developer is counted against for a code change its brain made by hand where its instructions named tools; the other roles
 * are reported with their usage and nothing counted against them, and what has no reading is said and not shown as a zero.
 */
class AgentControlFiguresTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode hireUpdate(String kind, String id, String status) {
        return JSON.createObjectNode().put("sessionUpdate", kind).put("toolCallId", id).put("status", status)
            .put("title", "mcp__jawata-javata-dev__hire");
    }

    /** A hire by {@code hirer} of {@code seat}, started and then finished with {@code answerJson}. */
    private static List<ObjectNode> hire(String id, String hirer, String seat, String answerJson) {
        ObjectNode started = hireUpdate("tool_call", id, "pending");
        started.putObject("rawInput").put("hirer", hirer).put("seat", seat).put("brief", "work");
        return List.of(started, hireUpdate("tool_call_update", id, "completed").put("rawOutput", answerJson));
    }

    private static JsonNode figuresAfter(List<ObjectNode> updates) {
        List<JsonNode> said = new ArrayList<>();
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                updates.forEach(toolCalls);
                chunks.accept("ok");
            }
        };
        AcpServer server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null, null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "go");
        server.receive(JsonRpcMessage.request(2, "session/prompt", params));
        server.receive(JsonRpcMessage.request(9, "_jawata/hire/figures", JSON.createObjectNode()));
        return said.stream().filter(m -> m.path("id").asInt() == 9).findFirst().orElseThrow().path("result");
    }

    private static JsonNode row(JsonNode figures, String seat) {
        for (JsonNode role : figures.path("roles")) {
            if (seat.equals(role.path("seat").asText())) {
                return role;
            }
        }
        throw new AssertionError("no row for " + seat + " in " + figures);
    }

    @Test
    @DisplayName("a developer is counted against for a hand edit made where its instructions named tools; its tokens and tool calls are summed per role")
    void aDevelopersHandEditsAreCountedWhereToolsWereNamed() {
        List<ObjectNode> updates = new ArrayList<>();
        updates.addAll(hire("d1", "chief-engineer", "developer",
            "{\"data\":{\"cleanExit\":true,\"toolSteps\":3,\"codeChangesByHand\":2,\"tokens\":{\"input\":100,\"output\":50},\"instructionScore\":{\"instructionsIssued\":2,\"ranAsWritten\":1}}}"));
        updates.addAll(hire("d2", "chief-engineer", "developer",
            "{\"data\":{\"cleanExit\":true,\"toolSteps\":1,\"codeChangesByHand\":1,\"tokens\":{\"input\":10,\"output\":5},\"instructionScore\":{\"instructionsIssued\":1,\"ranAsWritten\":1}}}"));

        JsonNode developer = row(figuresAfter(updates), "developer");

        assertEquals(2, developer.path("hires").asInt());
        assertEquals(165, developer.path("tokens").asLong());
        assertEquals(4, developer.path("toolSteps").asInt());
        assertEquals(3, developer.path("countedAgainst").asInt(), "the hand edits of instructed hires were not counted against the developer: " + developer);
    }

    @Test
    @DisplayName("CONTROL: a developer's hand edit in a hire with no instructions is not counted, and no other role is counted against")
    void aHandEditWithoutInstructionsAndOtherRolesCountNothing() {
        List<ObjectNode> updates = new ArrayList<>();
        updates.addAll(hire("d1", "chief-engineer", "developer",
            "{\"data\":{\"cleanExit\":true,\"toolSteps\":2,\"codeChangesByHand\":5}}"));
        updates.addAll(hire("p1", "main", "pm",
            "{\"data\":{\"cleanExit\":true,\"toolSteps\":0,\"codeChangesByHand\":4,\"instructionScore\":{\"instructionsIssued\":2}}}"));

        JsonNode figures = figuresAfter(updates);

        assertEquals(0, row(figures, "developer").path("countedAgainst").asInt(), "an edit made with no instruction naming a tool was counted");
        assertEquals(0, row(figures, "pm").path("countedAgainst").asInt(), "a pm was counted against");
    }

    @Test
    @DisplayName("what has no reading is said: the answer names the chief engineer's edit orders as not measured, so its zero is not read as a good result")
    void whatIsNotMeasuredIsSaid() {
        JsonNode figures = figuresAfter(hire("c1", "pm", "chief-engineer", "{\"data\":{\"cleanExit\":true,\"toolSteps\":1}}"));

        assertTrue(figures.path("notMeasured").toString().contains("chief-engineer"), figures.toString());
        assertFalse(figures.path("roles").isEmpty(), "the chief engineer's usage is missing: " + figures);
    }
}
