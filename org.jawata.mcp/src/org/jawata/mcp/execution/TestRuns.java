package org.jawata.mcp.execution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the {@code run_tests} calls of a running hire showed, in order (Sprint 28h Stage 27 (z5)). The stream a hire's brain sends records a
 * call and never its result, so the engine, which answers every call, keeps what each test run showed under the caller's id, which for a hire
 * is its plan id.
 *
 * <p>Only the latest {@link #PER_CALLER} runs of one caller are kept, so a run that never ends cannot make the store grow with it.</p>
 */
public final class TestRuns {

    /** How many runs of one caller are kept; the latest ones. */
    static final int PER_CALLER = 200;

    private static final TestRuns SHARED = new TestRuns();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Deque<TestFirst.Run>> byCaller = new ConcurrentHashMap<>();

    /** The store the registry records into and a hire's run takes from. */
    public static TestRuns shared() {
        return SHARED;
    }

    /** Keeps what this call showed under its caller when the tool is {@code run_tests}; any other tool is not kept. */
    public void record(String callerId, String tool, ToolResponse response) {
        if (!"run_tests".equals(tool)) {
            return;
        }
        Deque<TestFirst.Run> kept = byCaller.computeIfAbsent(callerId, id -> new ArrayDeque<>());
        synchronized (kept) {
            kept.addLast(classify(response));
            while (kept.size() > PER_CALLER) {
                kept.removeFirst();
            }
        }
    }

    /** What this caller's test runs showed, oldest first, handed over once: a second take of the same caller is empty. */
    public List<TestFirst.Run> take(String callerId) {
        Deque<TestFirst.Run> kept = byCaller.remove(callerId);
        if (kept == null) {
            return List.of();
        }
        synchronized (kept) {
            return new ArrayList<>(kept);
        }
    }

    /** What one answer of {@code run_tests} showed: red when a test failed, green when tests ran and none did, otherwise no run. */
    static TestFirst.Run classify(ToolResponse response) {
        if (response == null || !response.isSuccess() || response.getData() == null) {
            return TestFirst.Run.NOT_RUN;
        }
        JsonNode summary = MAPPER.valueToTree(response.getData()).path("summary");
        if (!summary.path("total").isNumber() || summary.path("total").asInt() <= 0) {
            return TestFirst.Run.NOT_RUN;
        }
        if (summary.path("failed").asInt() > 0) {
            return TestFirst.Run.RED;
        }
        return summary.path("evidenceFinalized").asBoolean(true) ? TestFirst.Run.GREEN : TestFirst.Run.NOT_RUN;
    }
}
