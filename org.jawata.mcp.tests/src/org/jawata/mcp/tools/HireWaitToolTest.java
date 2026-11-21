package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, Stage 27, row (z30): a client that runs jawata without the jawata chat is the principal of its hires, and nothing can push a
 * hire's report into its turn, so it asks for it. {@code hire_wait} answers the state of one hire's plan: running, the finished report, or
 * unknown, waiting a few seconds for a running one; and it marks a report it hands over as read, so the chat does not hand it over again.
 */
class HireWaitToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HireRegistry hires = HireRegistry.shared();
    private final HireWaitTool tool = new HireWaitTool(hires);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse response) {
        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
        return (Map<String, Object>) response.getData();
    }

    private ToolResponse ask(String hireId, int waitSeconds) {
        ObjectNode args = JSON.createObjectNode().put("hireId", hireId);
        args.put("waitSeconds", waitSeconds);
        return tool.execute(args);
    }

    private Thread holdRunning(String planId, CountDownLatch end) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration hire = hires.begin("developer", "z30 " + planId, "")) {
                hires.planOpened(planId);
                registered.countDown();
                end.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
        return thread;
    }

    @Test
    @DisplayName("28h z30: a running hire is answered as still running after the wait, and the wait is real; an id no hire has is answered unknown")
    void aRunningHireIsStillRunning() throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        Thread running = holdRunning("plan-z30-running", end);
        try {
            long startedAt = System.nanoTime();
            Map<String, Object> waited = dataOf(ask("plan-z30-running", 1));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            Map<String, Object> unknown = dataOf(ask("plan-z30-nobody", 0));

            assertAll(
                () -> assertEquals("running", waited.get("state"), String.valueOf(waited)),
                () -> assertTrue(tookMs >= 900, "the call did not wait for the running hire: " + tookMs + " ms"),
                () -> assertEquals("unknown", unknown.get("state"), "CONTROL: an id no hire has was given a state: " + unknown));
        } finally {
            end.countDown();
            running.join(5_000);
        }
    }

    @Test
    @DisplayName("28h z30: a finished hire's report is handed over, new the first time and marked read, so the chat does not hand it over again; asking again answers the same report as not new")
    void aFinishedReportIsHandedOverOnce() throws Exception {
        String plan = "plan-z30-finished";
        hires.nextHireIsBackground();
        try (HireRegistry.Registration hire = hires.begin("developer", "z30 finished", "")) {
            hires.planOpened(plan);
        }
        JsonNode answer = JSON.createObjectNode().put("plan", plan).put("name", "Zed").put("result", "done");
        hires.record("developer", "z30 finished", answer);

        Map<String, Object> first = dataOf(ask(plan, 0));
        Map<String, Object> again = dataOf(ask(plan, 0));

        assertAll(
            () -> assertEquals("finished", first.get("state"), String.valueOf(first)),
            () -> assertEquals(answer, JSON.valueToTree(first.get("report")), "the report is not the hire's answer"),
            () -> assertEquals(Boolean.TRUE, first.get("newToYou"), "the first read was not marked new"),
            () -> assertEquals(Boolean.FALSE, again.get("newToYou"), "the second read of the same report was marked new again"),
            () -> assertEquals(List.of(), hires.undeliveredFor(""), "the chat would still hand over a report the tool already gave"));
    }
}
