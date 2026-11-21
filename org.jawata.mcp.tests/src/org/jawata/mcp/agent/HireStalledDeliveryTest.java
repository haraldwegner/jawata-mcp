package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * Sprint 28h, Stage 27, row (z28): when the registry tells that a hire has gone quiet, the chat connection shows the hire's card as stalled
 * and, for a hire the principal's brain started in the background, gives that brain a turn saying so, so the principal can look at it before the
 * engine stops it. A hire that was not started in the background has the card and no turn: its caller is still waiting on it.
 */
class HireStalledDeliveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<JsonNode> said = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private AcpServer server;
    private String session;
    private long ceilingBefore;

    private void connect() {
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                prompts.add(prompt);
                chunks.accept("ok");
            }
        };
        server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
    }

    @AfterEach
    void close() throws InterruptedException {
        HireRegistry.shared().quietCeiling(ceilingBefore);
        release.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }
        if (server != null) {
            server.closed();
        }
    }

    private void aHireIsOut(String seat, String brief, String planId, boolean inTheBackground) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            if (inTheBackground) {
                HireRegistry.shared().nextHireIsBackground();
            }
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin(seat, brief, session)) {
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
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
    }

    private boolean waitUntil(java.util.function.BooleanSupplier condition, int seconds) throws InterruptedException {
        for (int i = 0; i < seconds * 20 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private boolean cardSaysStalled(String planId) {
        return said.stream().anyMatch(m -> m.toString().contains(planId) && m.toString().contains("\"state\":\"stalled\""));
    }

    @Test
    @DisplayName("28h z28: a quiet hire's card reads stalled on the host without anything asking; the brain that started it in the background is given a turn naming it; a hire that was not started in the background gets the card and no turn")
    void aQuietHireIsShownAsStalledAndItsBrainIsTold() throws Exception {
        ceilingBefore = HireRegistry.shared().quietCeiling();
        HireRegistry.shared().quietCeiling(1);
        connect();
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", "start the work");
        server.receive(JsonRpcMessage.request(2, "session/prompt", params));
        aHireIsOut("developer", "z28 quiet background", "z28-quiet-plan-bg", true);
        aHireIsOut("developer", "z28 quiet foreground", "z28-quiet-plan-fg", false);
        int promptsBefore = prompts.size();
        Thread.sleep(1_300);

        HireRegistry.shared().noteQuiet(1);
        boolean told = waitUntil(() -> prompts.size() > promptsBefore, 10);
        Thread.sleep(300);

        assertAll(
            () -> assertTrue(cardSaysStalled("z28-quiet-plan-bg"), "the background hire's card never read stalled on the host"),
            () -> assertTrue(cardSaysStalled("z28-quiet-plan-fg"), "the foreground hire's card never read stalled on the host"),
            () -> assertTrue(told, "the brain that started the quiet hire was not given a turn"),
            () -> assertTrue(told && prompts.get(prompts.size() - 1).contains("has gone quiet") && prompts.get(prompts.size() - 1).contains("developer"),
                "the turn does not say which hire has gone quiet: " + prompts),
            () -> assertEquals(promptsBefore + 1, prompts.size(), "CONTROL: a hire that was not started in the background was given a turn too: " + prompts));
    }
}
