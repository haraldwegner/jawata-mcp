package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 28h dogfood S19-1 (2026-11-17): the chat stayed on "working" after the brain had finished. The engine wrote the finished turn to the transcript
 * lane on the thread that has to answer the prompt, with no time limit, so a lane that was slow or held by another resident held the answer back
 * for as long as it took. A turn now waits for the lane only up to a bound and answers without it; the late write still lands, in order.
 */
class AgentRecorderBoundedTest {

    /** A lane that takes a turn only once it is let go, and notes the keys it took in the order it took them. */
    private static final class HeldLane implements ToolCalls {
        final CountDownLatch released = new CountDownLatch(1);
        final List<String> took = new CopyOnWriteArrayList<>();

        @Override
        public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
            try {
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            took.add(arguments.path("key").asText());
            return ToolResponse.success(Map.of());
        }

        @Override
        public List<Map<String, Object>> getToolDefinitions() {
            return List.of();
        }
    }

    @Test
    @DisplayName("S19-1: a turn is answered after the bound although the lane has not taken it, and the late write lands, in order")
    void aSlowLaneDoesNotHoldTheTurnBack() throws Exception {
        HeldLane lane = new HeldLane();
        AgentRecorder recorder = new AgentRecorder(lane, AgentRecorder.Depth.ALL, 300);
        PrincipalSession session = new PrincipalSession("s19-bounded", "", false);

        long started = System.nanoTime();
        recorder.record(AgentRecorder.Level.PRINCIPAL, session, "first", "typed", "said", List.of());
        recorder.record(AgentRecorder.Level.PRINCIPAL, session, "second", "typed", "said", List.of());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(millis < 5_000, "two turns waited " + millis + " ms for a lane that had not taken them; each may wait only its bound of 300 ms");
        assertTrue(lane.took.isEmpty(), "the lane took a turn before it was let go");

        lane.released.countDown();
        for (int i = 0; i < 100 && lane.took.size() < 2; i++) {
            Thread.sleep(50);
        }
        assertEquals(List.of("first", "second"), lane.took, "the late writes did not land, or landed out of order");
    }

    @Test
    @DisplayName("S19-1: a lane that takes the turn at once has taken it when record returns, so a history read right after sees it")
    void aFastLaneHasTakenTheTurnWhenRecordReturns() {
        HeldLane lane = new HeldLane();
        lane.released.countDown();
        AgentRecorder recorder = new AgentRecorder(lane, AgentRecorder.Depth.ALL, 5_000);

        recorder.record(AgentRecorder.Level.PRINCIPAL, new PrincipalSession("s19-fast", "", false), "only", "typed", "said", List.of());

        assertEquals(List.of("only"), lane.took, "record returned before a lane that was ready had taken the turn");
        assertEquals(0, recorder.slowWrites(), "a write that took no time was counted as slow");
    }

    @Test
    @DisplayName("S19-1: a write the lane takes a second or more to take is counted, so a stalled lane leaves a trace")
    void aSlowWriteIsCounted() throws Exception {
        HeldLane lane = new HeldLane();
        AgentRecorder recorder = new AgentRecorder(lane, AgentRecorder.Depth.ALL, 10_000);
        Thread letGo = new Thread(() -> {
            try {
                Thread.sleep(1_300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lane.released.countDown();
        });
        letGo.start();

        recorder.record(AgentRecorder.Level.PRINCIPAL, new PrincipalSession("s19-slow", "", false), "slow", "typed", "said", List.of());
        letGo.join();

        assertEquals(List.of("slow"), lane.took);
        assertEquals(1, recorder.slowWrites(), "a write that took over a second was not counted");
    }
}
