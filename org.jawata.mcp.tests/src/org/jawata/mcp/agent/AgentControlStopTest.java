package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jawata.mcp.execution.RunningHires;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S9 (his ruling on the Agent Control panel, 2026-10-31): the panel can stop one agent. The stop ends that one hire while the
 * principal's turn goes on and receives a failed-hire answer. The method name is provisional until the chat's recording R12 carries it.
 */
class AgentControlStopTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SEAT = "chief-engineer";
    private static final String BRIEF = "build the stage";

    /** An agent that has seen one hire (planId t1) go by, finished or still running, with a connection on which stops are asked. */
    private static final class Connection {
        final List<JsonNode> said = new ArrayList<>();
        final AcpServer server;

        Connection(boolean finished) {
            AgentBrain brain = new AgentBrain() {
                @Override
                public void answer(String prompt, Consumer<String> chunks) {
                    answer(prompt, chunks, call -> { });
                }

                @Override
                public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                    ObjectNode hire = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "t1").put("status", "pending")
                        .put("title", "mcp__jawata-javata-dev__hire");
                    hire.putObject("rawInput").put("hirer", "pm").put("seat", SEAT).put("brief", BRIEF);
                    toolCalls.accept(hire);
                    if (finished) {
                        toolCalls.accept(JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "t1")
                            .put("status", "completed").put("title", "mcp__jawata-javata-dev__hire")
                            .put("rawOutput", "{\"data\":{\"cleanExit\":true,\"answer\":\"done\"}}"));
                    }
                    chunks.accept("ok");
                }
            };
            server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), AgentRecorder.off(), null, null);
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", "go");
            server.receive(JsonRpcMessage.request(2, "session/prompt", params));
        }

        JsonNode stop(String planId) {
            ObjectNode params = JSON.createObjectNode();
            if (planId != null) {
                params.put("planId", planId);
            }
            server.receive(JsonRpcMessage.request(9, "_jawata/hire/stop", params));
            return said.stream().filter(m -> m.path("id").asInt() == 9).findFirst().orElseThrow();
        }
    }

    /** A hire running on its own thread, registered the way the hire tool registers one; the thread ends when it is interrupted. */
    private static Thread aRunningHire(CountDownLatch interrupted) throws InterruptedException {
        int before = RunningHires.shared().count();
        Thread worker = new Thread(() -> {
            try (RunningHires.Registration registration = RunningHires.shared().begin(SEAT, BRIEF)) {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        worker.setDaemon(true);
        worker.start();
        for (int i = 0; i < 100 && RunningHires.shared().count() == before; i++) {
            Thread.sleep(50);
        }
        assertEquals(before + 1, RunningHires.shared().count(), "the hire did not register as running");
        return worker;
    }

    @Test
    @DisplayName("stopping a running hire interrupts the thread running it, answers how many were stopped, and the hire is no longer running")
    void aRunningHireIsStopped() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread worker = aRunningHire(interrupted);
        Connection connection = new Connection(false);

        JsonNode answer = connection.stop("t1");

        assertNull(answer.get("error"), answer.toString());
        assertEquals("t1", answer.path("result").path("planId").asText());
        assertEquals(1, answer.path("result").path("stopped").asInt());
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the thread running the hire was never interrupted");
        worker.join(5_000);
        assertFalse(worker.isAlive(), "the hire thread did not end");
    }

    @Test
    @DisplayName("a hire that has already finished has nothing to stop, and the running hire of the same seat is left alone")
    void aFinishedHireIsNotStopped() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread worker = aRunningHire(interrupted);
        try {
            JsonNode answer = new Connection(true).stop("t1");

            assertNotNull(answer.get("error"), answer.toString());
            assertEquals(-32602, answer.path("error").path("code").asInt());
            assertFalse(interrupted.await(300, TimeUnit.MILLISECONDS), "a finished hire's stop interrupted a running one");
        } finally {
            worker.interrupt();
            worker.join(5_000);
        }
    }

    @Test
    @DisplayName("a hire this engine is not running, one the agent never saw, and no plan id are bad requests, and nothing is interrupted")
    void whatCannotBeStoppedIsRefused() throws Exception {
        Connection connection = new Connection(false);

        for (String planId : new String[] {"t1", "nope", null}) {
            JsonNode answer = connection.stop(planId);
            assertNotNull(answer.get("error"), planId + ": " + answer);
            assertEquals(-32602, answer.path("error").path("code").asInt(), planId + ": " + answer);
        }
    }
}
