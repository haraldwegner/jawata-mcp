package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.execution.CallOutcomes;
import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.execution.TestFirst;
import org.jawata.mcp.execution.TestRuns;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage 27 (o): the registry keeps the calls of a RUNNING hire that failed or were refused, by the hire's plan id, so Agent Control can show them.
 * A caller that is no running hire is not kept, or the store would grow with every session of every client.
 */
class ToolRegistryRecordsHireFailuresTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private final CountDownLatch release = new CountDownLatch(1);
    private Thread hire;

    @AfterEach
    void endTheHire() throws InterruptedException {
        release.countDown();
        if (hire != null) {
            hire.join(5_000);
        }
    }

    private void aHireIsRunning(String planId) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        hire = new Thread(() -> {
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin("developer", "failures " + planId, "")) {
                HireRegistry.shared().planOpened(planId);
                started.countDown();
                release.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        hire.setDaemon(true);
        hire.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "the hire did not start");
    }

    private static ToolRegistry registryWith(String name, ToolResponse answer) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String getName() {
                return name;
            }
            @Override public String getDescription() {
                return "a stand-in that answers as told";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return answer;
            }
        });
        return registry;
    }

    @Test
    @DisplayName("a call of a running hire that failed is kept under the hire's plan id; a call that succeeded, and a caller that is no hire, are not")
    void aRunningHiresFailedCallIsKept() throws Exception {
        aHireIsRunning("plan-records-1");
        JsonNode none = OM.readTree("{}");
        ToolRegistry failing = registryWith("rename_symbol", ToolResponse.error("REFACTORING_FAILED", "no", "hint"));
        ToolRegistry working = registryWith("search_symbols", ToolResponse.success("fine"));

        failing.callTool("rename_symbol", none, CallerIdentity.supplied("plan-records-1"));
        working.callTool("search_symbols", none, CallerIdentity.supplied("plan-records-1"));
        failing.callTool("rename_symbol", none, CallerIdentity.supplied("not-a-hire-session"));

        assertAll(
            () -> assertEquals(List.of(new CallOutcomes.Failed("rename_symbol", "REFACTORING_FAILED", "no")), CallOutcomes.shared().take("plan-records-1"),
                "THE CLAIM: the hire's failed call was not kept, or its success was"),
            () -> assertTrue(CallOutcomes.shared().take("not-a-hire-session").isEmpty(), "CONTROL: a session that is no running hire was kept"));
    }

    private static ToolResponse ran(int total, int failed) {
        return ToolResponse.success(Map.of("summary", Map.of("total", total, "passed", total - failed, "failed", failed)));
    }

    @Test
    @DisplayName("Stage 27 (z5): the test runs of a running hire are kept under its plan id in order, whatever they showed; another tool's answers and a caller that is no hire are not")
    void aRunningHiresTestRunsAreKept() throws Exception {
        aHireIsRunning("plan-testruns-1");
        JsonNode none = OM.readTree("{}");
        ToolRegistry red = registryWith("run_tests", ran(3, 1));
        ToolRegistry green = registryWith("run_tests", ran(3, 0));
        ToolRegistry other = registryWith("search_symbols", ToolResponse.success("fine"));

        red.callTool("run_tests", none, CallerIdentity.supplied("plan-testruns-1"));
        other.callTool("search_symbols", none, CallerIdentity.supplied("plan-testruns-1"));
        green.callTool("run_tests", none, CallerIdentity.supplied("plan-testruns-1"));
        red.callTool("run_tests", none, CallerIdentity.supplied("not-a-hire-session"));

        assertAll(
            () -> assertEquals(List.of(TestFirst.Run.RED, TestFirst.Run.GREEN), TestRuns.shared().take("plan-testruns-1"),
                "THE CLAIM: the hire's test runs were not kept, or another tool's answer was"),
            () -> assertTrue(TestRuns.shared().take("not-a-hire-session").isEmpty(), "CONTROL: a session that is no running hire was kept"));
    }
}
