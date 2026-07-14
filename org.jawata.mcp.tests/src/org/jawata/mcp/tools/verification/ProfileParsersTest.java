package org.jawata.mcp.tools.verification;

import org.jawata.mcp.runtime.profile.ProfileParsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 audit (2026-07-14) — the PURE parser tests that did not exist, and whose
 * absence let a wrong-answer bug ship in v2.13.0: {@code profile(action=threads)}
 * reported every thread's {@code id} as the decimal {@code nid} string reinterpreted
 * in base 16, so no reported id was any real thread on the machine.
 *
 * <p>The fixtures below are REAL {@code jcmd Thread.print} output, captured from a live
 * JDK 21 JVM and cross-checked against {@code /proc/<pid>/task/}: {@code nid=3817383} is
 * decimal, and 3817383 IS the kernel thread id (it appears in the task list, and the
 * bracketed {@code [3817383]} in the header is the same value). A parser test written
 * against an IMAGINED format is how the original bug survived 1362 green tests — so
 * these assert against the tool's own real words, not ours.</p>
 */
class ProfileParsersTest {

    /** Real `jcmd <pid> Thread.print` output (JDK 21.0.10, captured 2026-07-14). */
    private static final String REAL_THREAD_DUMP = """
        3817381:
        2026-07-14 23:58:01
        Full thread dump OpenJDK 64-Bit Server VM (21.0.10+7-Ubuntu-122.04 mixed mode, sharing):

        Threads class SMR info:
        _java_thread_list=0x00007485f00050b0, length=9, elements={
        0x00007485f0019c30, 0x00007485f01412e0
        }

        "nid-probe-main" #1 [3817383] prio=5 os_prio=0 cpu=24.72ms elapsed=1.20s tid=0x00007485f0019c30 nid=3817383 waiting on condition  [0x00007485f69fe000]
           java.lang.Thread.State: TIMED_WAITING (sleeping)
        	at java.lang.Thread.sleep0(java.base@21.0.10/Native Method)
        	at java.lang.Thread.sleep(java.base@21.0.10/Thread.java:509)
        	at NidProbe.main(NidProbe.java:5)

        "Reference Handler" #9 [3817391] daemon prio=10 os_prio=0 cpu=0.24ms elapsed=1.18s tid=0x00007485f01412e0 nid=3817391 waiting on condition  [0x00007485c4da4000]
           java.lang.Thread.State: RUNNABLE
        	at java.lang.ref.Reference.waitForReferencePendingList(java.base@21.0.10/Native Method)

        """;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> threadsOf(String raw) {
        return (List<Map<String, Object>>) ProfileParsers.parseThreadDump(raw, 20).get("threads");
    }

    @Test
    @DisplayName("THE BUG: a thread's id is the REAL OS thread id — decimal nid, not that string read as hex")
    void threadIdIsTheRealOsThreadId() {
        List<Map<String, Object>> threads = threadsOf(REAL_THREAD_DUMP);
        assertEquals(2, threads.size(), "both real headers must parse: " + threads);

        Map<String, Object> main = threads.get(0);
        assertEquals("nid-probe-main", main.get("name"));
        // Ground truth: this JVM's `nid=3817383` IS kernel task 3817383 (verified against
        // /proc/<pid>/task/). Read as base 16 it would be 58815363 — a thread that does
        // not exist, on any machine. An operator taking this id to `top -H` or a
        // `kill -QUIT` cross-reference lands on nothing.
        assertEquals(3817383L, main.get("id"),
            "the id must be the OS thread id the JVM actually named: " + main);
        assertEquals(3817391L, threads.get(1).get("id"), "got: " + threads.get(1));
    }

    @Test
    @DisplayName("the id agrees with the OS tid HotSpot prints in brackets on the same line")
    void idAgreesWithTheBracketedOsTidOnTheSameLine() {
        // HotSpot prints the same value twice: "[3817383] ... nid=3817383". They cannot
        // disagree — so this is a self-checking invariant on our own parse, and it is
        // exactly the check that would have caught the radix bug the moment it was typed.
        for (Map<String, Object> thread : threadsOf(REAL_THREAD_DUMP)) {
            long id = (Long) thread.get("id");
            assertTrue(REAL_THREAD_DUMP.contains("[" + id + "] "),
                "id " + id + " must be the OS tid HotSpot bracketed on that thread's own "
                    + "header line — it is not: " + thread.get("name"));
        }
    }

    @Test
    @DisplayName("daemon, state and a bounded stack come off the real dump too")
    void theRestOfTheRealHeaderParses() {
        List<Map<String, Object>> threads = threadsOf(REAL_THREAD_DUMP);

        Map<String, Object> main = threads.get(0);
        assertEquals(false, main.get("daemon"));
        assertEquals("TIMED_WAITING", main.get("state"));
        @SuppressWarnings("unchecked")
        List<String> stack = (List<String>) main.get("stack");
        assertEquals(3, stack.size(), "got: " + stack);
        assertTrue(stack.get(2).startsWith("NidProbe.main"), "got: " + stack);

        Map<String, Object> refHandler = threads.get(1);
        assertEquals(true, refHandler.get("daemon"), "the daemon flag is real: " + refHandler);
        assertEquals("RUNNABLE", refHandler.get("state"));
    }

    @Test
    @DisplayName("maxFrames bounds the stack, and an absent deadlock is an answer")
    void boundsAndAbsenceAreHonest() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> threads = (List<Map<String, Object>>)
            ProfileParsers.parseThreadDump(REAL_THREAD_DUMP, 1).get("threads");
        @SuppressWarnings("unchecked")
        List<String> stack = (List<String>) threads.get(0).get("stack");
        assertEquals(1, stack.size(), "maxFrames must actually bound: " + stack);

        @SuppressWarnings("unchecked")
        Map<String, Object> deadlock = (Map<String, Object>)
            ProfileParsers.parseThreadDump(REAL_THREAD_DUMP, 20).get("deadlock");
        assertEquals(Boolean.FALSE, deadlock.get("deadlocked"),
            "no deadlock in this dump — an absence, stated: " + deadlock);
        assertFalse(deadlock.containsKey("threads"), "and nothing fabricated: " + deadlock);
    }
}
