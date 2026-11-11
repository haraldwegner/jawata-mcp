package org.jawata.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * A process that could not be ASKED for its debug address is not one that has no debug agent. {@link JvmTargets#attach} reported
 * both as {@code JVM_NOT_DEBUGGABLE}, and the 4.6.5 release gate's suite got that answer for a JVM started with
 * {@code -agentlib:jdwp} on a loaded machine (2026-11-11). The process here is not a JVM at all, so it can never be asked: the
 * answer must be the failure to ask, after the process exits, and never the claim that it lacks an agent.
 */
@DisabledOnOs(OS.WINDOWS) // the stand-in process is the POSIX `sleep`
class AJvmThatCannotBeAskedIsNotCalledUndebuggableTest {

    @Test
    void aProcessThatCannotBeAskedIsReportedAsSuchAndNotAsLackingAnAgent() throws Exception {
        Process notAJvm = new ProcessBuilder("sleep", "2").start();
        try {
            long started = System.nanoTime();
            Exception refused = assertThrows(Exception.class, () -> JvmTargets.attach(notAJvm.pid()));

            assertFalse(refused instanceof JvmTargets.NotDebuggable,
                "it was never asked, so it cannot be said to lack an agent: " + refused);
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(20)) < 0,
                "the asking stops when the process exits, not at the patience's end");
        } finally {
            notAJvm.destroyForcibly();
        }
    }

    /**
     * The attach signal is sent only to a process that already catches it: one that does not yet would be ENDED by it, which is how
     * the debugger test's program died with exit code 131 and no output. A running JVM (this one) catches it; `sleep` never does.
     * Linux only: the check reads /proc, and where there is none (macOS) it answers yes, as asking did before (CI, 2026-11-11).
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(OS.LINUX)
    void theAttachSignalIsSentOnlyToAProcessThatCatchesIt() throws Exception {
        Process notAJvm = new ProcessBuilder("sleep", "30").start();
        try {
            assertTrue(JvmTargets.catchesTheAttachSignal(ProcessHandle.current().pid()), "a running JVM catches SIGQUIT");
            assertFalse(JvmTargets.catchesTheAttachSignal(notAJvm.pid()), "sleep does not, so it is never sent the signal that would end it");

            assertThrows(Exception.class, () -> JvmTargets.awaitDebugAddress(notAJvm.pid(), Duration.ofMillis(600)), "it cannot be asked");
            assertTrue(notAJvm.isAlive(), "and asking it for a while did not end it");
        } finally {
            notAJvm.destroyForcibly();
        }
    }
}
