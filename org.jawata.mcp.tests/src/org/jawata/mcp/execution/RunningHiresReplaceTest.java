package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D53 (i): moving a running hire onto another brain ends the old brain and must not lose what it had said. */
class RunningHiresReplaceTest {

    @Test
    @DisplayName("a replace ends the brain's process and leaves the thread uninterrupted, so the run still reads what the brain had said")
    void aReplaceEndsTheProcessWithoutAnInterrupt() {
        RunningHires hires = RunningHires.shared();
        AtomicBoolean processEnded = new AtomicBoolean();
        try (RunningHires.Registration running = hires.begin("replace-test-seat", "replace-test-brief")) {
            hires.onStop(() -> processEnded.set(true));

            int replaced = hires.replace("replace-test-seat", "replace-test-brief", SeatHire.Brain.CURSOR, "");

            assertEquals(1, replaced);
            assertTrue(processEnded.get(), "the old brain's process was left running");
            assertFalse(Thread.currentThread().isInterrupted(), "the interrupt makes the run lose what the brain had said");
            assertTrue(running.replacement().isPresent(), "the run was not told which brain comes next");
        } finally {
            Thread.interrupted();
        }
    }
}
