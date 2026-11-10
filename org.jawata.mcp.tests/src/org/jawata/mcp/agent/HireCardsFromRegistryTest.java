package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stage 24, M8 (D55): the cards of the Agent Control panel come from the engine's registry of hires, so a hire at any depth has a card and a
 * hire's state is the engine's word and not whatever a brain echoes of its tool call.
 */
class HireCardsFromRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<JsonNode> said = new ArrayList<>();
    private AcpServer server;
    private String session;

    /** A connection with one chat session whose principal brain says nothing of its own, unless told to stream the calls of a hire. */
    private void connect(Consumer<Consumer<JsonNode>> brainCalls) {
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                brainCalls.accept(toolCalls);
                chunks.accept("ok");
            }
        };
        server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null, null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
    }

    private final List<Thread> threads = new ArrayList<>();
    private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

    @AfterEach
    void close() throws InterruptedException {
        release.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }
        if (server != null) {
            server.closed();
        }
    }

    /** A hire the registry holds as running on a thread of its own, as the engine holds one: issued by this caller, with this plan id. */
    private void running(String seat, String brief, String callerId, String planId) throws InterruptedException {
        java.util.concurrent.CountDownLatch registered = new java.util.concurrent.CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin(seat, brief, callerId)) {
                HireRegistry.shared().planOpened(planId);
                registered.countDown();
                release.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
        assertTrue(registered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the hire did not register as running");
    }

    private void prompt() {
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "go");
        server.receive(JsonRpcMessage.request(2, "session/prompt", params));
    }

    /** What the host was told about one tool call, in the order it was told. */
    private List<JsonNode> toldAbout(String toolCallId) {
        return said.stream().map(m -> m.path("params").path("update")).filter(u -> toolCallId.equals(u.path("toolCallId").asText())).toList();
    }

    @Test
    @DisplayName("a hire at any depth has its own card, under the hire that issued it, and a hire of another conversation has none here")
    void aNestedHireShowsARowWithTheRightParent() throws Exception {
        connect(calls -> { });

        running("pm", "m8 run the sprint", session, "m8-plan-pm");
        running("chief-engineer", "m8 build the stage", "m8-plan-pm", "m8-plan-ce");
        running("pm", "m8 someone else's sprint", "m8-another-conversation", "m8-plan-elsewhere");

        JsonNode pm = toldAbout("m8-plan-pm").get(0);
        JsonNode ce = toldAbout("m8-plan-ce").get(0);
        assertAll(
            () -> assertEquals("tool_call", pm.path("sessionUpdate").asText(), "the lead's hire has no card"),
            () -> assertFalse(pm.path("_meta").path("jawata/hire").path("name").asText().isBlank(), "the card does not carry the hire's name"),
            () -> assertFalse(pm.path("_meta").path("jawata/hire").has("parentPlanId"), "a hire the chat issued was given a parent"),
            () -> assertEquals("tool_call", ce.path("sessionUpdate").asText(), "the nested hire has no card"),
            () -> assertEquals("m8-plan-pm", ce.path("_meta").path("jawata/hire").path("parentPlanId").asText(),
                "the nested hire is not under the hire that issued it"),
            () -> assertFalse(ce.path("_meta").path("jawata/hire").path("name").asText().isBlank()),
            () -> assertTrue(toldAbout("m8-plan-elsewhere").isEmpty(), "a hire of another conversation was shown on this connection"));
    }

    @Test
    @DisplayName("a hire the chat's own brain issued is changed by the engine's word, and the brain's echo of its call, which says nothing of the result, does not end it")
    void aHireTheBrainIssuedFollowsTheEngineAndNotTheEcho() {
        connect(calls -> {
            ObjectNode call = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "m8-call").put("status", "pending")
                .put("title", "mcp__jawata-javata-dev__hire");
            call.putObject("rawInput").put("hirer", "main").put("seat", "developer").put("brief", "m8 write the test");
            calls.accept(call);
            // The hire answered at once and gave no result: the brain's call is complete and the hire is not.
            calls.accept(JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "m8-call").put("status", "completed")
                .put("title", "mcp__jawata-javata-dev__hire")
                .put("rawOutput", "{\"data\":{\"hireId\":\"m8-plan-dev\",\"name\":\"Joe\",\"state\":\"running\",\"hired\":\"developer\"}}"));
        });
        prompt();

        JsonNode afterEcho = toldAbout("m8-call").get(toldAbout("m8-call").size() - 1).path("_meta").path("jawata/hire");
        try (HireRegistry.Registration running = HireRegistry.shared().begin("developer", "m8 write the test", session)) {
            HireRegistry.shared().planOpened("m8-plan-dev");
        }
        ObjectNode answer = JSON.createObjectNode().put("plan", "m8-plan-dev").put("name", "Joe").put("cleanExit", true).put("answer", "written");
        HireRegistry.shared().record("developer", "m8 write the test", answer);

        JsonNode last = toldAbout("m8-call").get(toldAbout("m8-call").size() - 1);
        assertAll(
            () -> assertEquals("running", afterEcho.path("state").asText(), "the brain's echo ended a hire that was still running"),
            () -> assertEquals("Joe", afterEcho.path("name").asText()),
            () -> assertEquals("done", last.path("_meta").path("jawata/hire").path("state").asText(), "the engine's word did not end the card"),
            () -> assertEquals("completed", last.path("status").asText()),
            () -> assertTrue(toldAbout("m8-plan-dev").isEmpty(), "a second card was made for a hire the brain's own call already shows"));
    }
}
