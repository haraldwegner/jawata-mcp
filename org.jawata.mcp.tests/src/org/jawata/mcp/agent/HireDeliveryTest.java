package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Stage 24, M10 (D55): the report of a hire the principal started in the background comes back to the principal's brain as a new turn, one
 * turn at a time per session, and the person's message waits behind a report turn that is running and is then answered.
 */
class HireDeliveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPORT_MARK = "A hire you started has ended";
    private static final String BRAIN_KEY = "principal-test-brain-key";
    /** Who the hires of a test say asked for them; the chat's own id unless a test says it was the brain's key. */
    private volatile String caller;

    private final List<JsonNode> said = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    /** Held by the report turn when a test wants it to last, so the person's message can be sent while it runs. */
    private volatile CountDownLatch holdTheReport = new CountDownLatch(0);
    private final CountDownLatch reportTurnStarted = new CountDownLatch(1);
    private final List<Thread> threads = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private AcpServer server;
    private String session;

    private void connect() {
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, Consumer<String> chunks) {
                answer(prompt, chunks, call -> { });
            }

            @Override
            public java.util.Optional<String> toolSessionKey() {
                return java.util.Optional.of(BRAIN_KEY);
            }

            @Override
            public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                prompts.add(prompt);
                if (prompt.contains(REPORT_MARK)) {
                    reportTurnStarted.countDown();
                    try {
                        holdTheReport.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    chunks.accept("I have read the report.");
                } else {
                    chunks.accept("ok");
                }
            }
        };
        server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
    }

    @AfterEach
    void close() throws InterruptedException {
        holdTheReport.countDown();
        release.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }
        if (server != null) {
            server.closed();
        }
    }

    private void personSays(int requestId, String words) {
        ObjectNode params = JSON.createObjectNode().put("sessionId", session);
        params.putArray("prompt").addObject().put("type", "text").put("text", words);
        server.receive(JsonRpcMessage.request(requestId, "session/prompt", params));
    }

    /** A hire the chat's brain started, running on a thread of its own as the engine runs one; started in the background or not as asked. */
    private void aHireIsOut(String seat, String brief, String planId, boolean inTheBackground) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            if (inTheBackground) {
                HireRegistry.shared().nextHireIsBackground();
            }
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin(seat, brief, caller == null ? session : caller)) {
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

    private static ObjectNode answered(String planId, String name, String text) {
        return JSON.createObjectNode().put("plan", planId).put("name", name).put("cleanExit", true).put("answer", text);
    }

    private boolean waitUntil(java.util.function.BooleanSupplier condition, int seconds) throws InterruptedException {
        for (int i = 0; i < seconds * 20 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("a hire the brain started in the background reports back as a new turn of that brain; a hire that was not started in the background does not")
    void aFinishedBackgroundHireIsReportedToThePrincipalAsANewTurn() throws Exception {
        connect();
        personSays(2, "start the work");
        aHireIsOut("developer", "m10 write it", "m10-plan-bg", true);
        aHireIsOut("developer", "m10 write it too", "m10-plan-fg", false);

        // THE CONTROL: this hire's answer is the call's own result, so nothing is delivered for it.
        HireRegistry.shared().record("developer", "m10 write it too", answered("m10-plan-fg", "Marta", "answered to its caller"));
        Thread.sleep(500);
        int promptsAfterTheForegroundOne = prompts.size();

        HireRegistry.shared().record("developer", "m10 write it", answered("m10-plan-bg", "Joe", "the code is written"));
        boolean reported = waitUntil(() -> prompts.size() > promptsAfterTheForegroundOne, 10);

        assertAll(
            () -> assertEquals(1, promptsAfterTheForegroundOne, "a hire that was not started in the background was delivered as a turn: " + prompts),
            () -> assertTrue(reported, "the finished background hire never reached the brain"),
            () -> assertTrue(prompts.get(prompts.size() - 1).contains(REPORT_MARK)),
            () -> assertTrue(prompts.get(prompts.size() - 1).contains("Joe · developer"), "the report does not name the hire: " + prompts),
            () -> assertTrue(prompts.get(prompts.size() - 1).contains("State: done")),
            () -> assertTrue(prompts.get(prompts.size() - 1).contains("the code is written"), "the report does not carry the answer"),
            () -> assertTrue(said.stream().anyMatch(m -> m.path("params").path("update").path("content").path("text").asText().contains("I have read the report.")),
                "the principal's answer to the report did not reach the host"));
    }

    @Test
    @DisplayName("D55: with a report turn running and a second report in line, the person's message waits only for the running turn and is answered before the second report")
    void thePersonsMessageGoesAheadOfAReportThatIsOnlyQueued() throws Exception {
        connect();
        personSays(2, "start the work");
        aHireIsOut("developer", "queue brief first", "queue-plan-1", true);
        aHireIsOut("developer", "queue brief second", "queue-plan-2", true);
        holdTheReport = new CountDownLatch(1);
        HireRegistry.shared().record("developer", "queue brief first", answered("queue-plan-1", "Joe", "first report text"));
        assertTrue(reportTurnStarted.await(10, TimeUnit.SECONDS), "the first report turn never started");
        HireRegistry.shared().record("developer", "queue brief second", answered("queue-plan-2", "Mira", "second report text"));
        Thread.sleep(300); // the second report is now in line behind the running turn

        Thread typing = new Thread(() -> personSays(3, "and what about the other thing?"));
        typing.setDaemon(true);
        threads.add(typing);
        typing.start();
        Thread.sleep(300);
        holdTheReport.countDown();
        typing.join(10_000);
        waitUntil(() -> prompts.stream().anyMatch(p -> p.contains("second report text")), 10);

        int person = -1;
        int second = -1;
        for (int i = 0; i < prompts.size(); i++) {
            if (prompts.get(i).contains("the other thing")) {
                person = i;
            }
            if (prompts.get(i).contains("second report text")) {
                second = i;
            }
        }
        assertTrue(person >= 0 && second >= 0 && person < second,
            "the person's message was not answered before the queued report (person at " + person + ", second report at " + second + "): " + prompts);
    }

    @Test
    @DisplayName("found live with Cursor: a hire asked for by the brain's own key, which is not the chat's id, is shown on its chat and reported to its brain")
    void aHireAskedForByTheBrainsOwnKeyReportsToItsChat() throws Exception {
        connect();
        personSays(2, "start the work");
        caller = BRAIN_KEY;
        aHireIsOut("developer", "live brief under the brain key", "live-plan-key", true);

        HireRegistry.shared().record("developer", "live brief under the brain key", answered("live-plan-key", "Ida", "written under the brain key"));
        boolean reported = waitUntil(() -> prompts.stream().anyMatch(p -> p.contains("written under the brain key")), 10);

        assertTrue(reported, "a hire asked for by the brain's own key was never reported to its brain: " + prompts);
    }

    @Test
    @DisplayName("the person's message sent while a report turn runs waits for it and is then answered, in that order")
    void thePersonsMessageWaitsForTheReportTurnAndIsThenAnswered() throws Exception {
        connect();
        personSays(2, "start the work");
        aHireIsOut("developer", "m10 slow report", "m10-plan-slow", true);
        holdTheReport = new CountDownLatch(1);
        HireRegistry.shared().record("developer", "m10 slow report", answered("m10-plan-slow", "Joe", "done at last"));
        assertTrue(reportTurnStarted.await(10, TimeUnit.SECONDS), "the report turn never started");

        // The person types while the report turn is running: their message is held and is not lost.
        Thread typing = new Thread(() -> personSays(3, "and what about the other thing?"));
        typing.setDaemon(true);
        threads.add(typing);
        typing.start();
        Thread.sleep(500);
        List<String> whileTheReportRuns = new ArrayList<>(prompts);

        holdTheReport.countDown();
        typing.join(10_000);

        assertAll(
            () -> assertFalse(whileTheReportRuns.stream().anyMatch(p -> p.contains("the other thing")), "the person's message was sent to the brain while its report turn ran: " + whileTheReportRuns),
            () -> assertFalse(typing.isAlive(), "the person's message was never answered"),
            () -> assertTrue(prompts.get(prompts.size() - 1).contains("the other thing"), "the person's message was not the last turn: " + prompts),
            () -> assertTrue(prompts.get(prompts.size() - 2).contains(REPORT_MARK), "the report turn was not the one before it: " + prompts));
    }
}
