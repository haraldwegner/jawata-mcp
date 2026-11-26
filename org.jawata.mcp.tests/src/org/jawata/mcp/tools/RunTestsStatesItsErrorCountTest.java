package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

import org.jawata.mcp.execution.ForkedTestRunner;
import org.jawata.mcp.learn.ResponseFacts;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h z51: a plan step gated on {@code run_tests} passes on a green run and fails on a red one. The gate reads an answer's own
 * error count, so a finished run states it; a run that cannot be judged states none, and the gate reads that as not answered.
 */
class RunTestsStatesItsErrorCountTest {

    private static ForkedTestRunner.Result finished(int total, int failed) {
        ForkedTestRunner.Result run = new ForkedTestRunner.Result();
        run.total = total;
        run.failed = failed;
        run.passed = total - failed;
        run.evidenceFinalized = true;
        return run;
    }

    /** What a gate reads from the answer made of this run. */
    private static OptionalLong gateReads(ForkedTestRunner.Result run) {
        Map<String, Object> data = new LinkedHashMap<>();
        RunTestsTool.appendResult(data, run);
        return ResponseFacts.errorCount(ToolResponse.success(data));
    }

    @Test
    @DisplayName("z51: a green run states zero errors and a red run states its failures")
    void aFinishedRunStatesItsFailuresAsErrors() {
        assertAll(
            () -> assertEquals(OptionalLong.of(0), gateReads(finished(16, 0)), "16 of 16 passed"),
            () -> assertEquals(OptionalLong.of(2), gateReads(finished(16, 2)), "2 of 16 failed"));
    }

    @Test
    @DisplayName("z51: a run that found no test, was cancelled, timed out or ended abnormally states no count, so a gate is not answered")
    void aRunThatCannotBeJudgedStatesNoCount() {
        ForkedTestRunner.Result cancelled = finished(3, 0);
        cancelled.cancelled = true;
        cancelled.evidenceFinalized = false;
        ForkedTestRunner.Result timedOut = finished(3, 0);
        timedOut.timedOut = true;
        timedOut.evidenceFinalized = false;
        ForkedTestRunner.Result abnormal = finished(3, 0);
        abnormal.evidenceFinalized = false;

        assertAll(
            () -> assertEquals(OptionalLong.empty(), gateReads(finished(0, 0)), "no test was found"),
            () -> assertEquals(OptionalLong.empty(), gateReads(cancelled), "cancelled"),
            () -> assertEquals(OptionalLong.empty(), gateReads(timedOut), "timed out"),
            () -> assertEquals(OptionalLong.empty(), gateReads(abnormal), "the runner ended abnormally"));
    }
}
