package org.jawata.mcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three container-level outcomes the runner claims to report, as classes that really
 * produce them — driven by {@code build/container-marker-gate.sh}, never by the suite.
 *
 * <p><b>Why these exist.</b> mcp#54 taught the runner to print a container ABORT under the
 * {@code ~~ ABORTED} marker and to carry {@code containersAborted}; mcp#51 taught it to
 * carry {@code containersFailed} and to fail on it. Both shipped with the same hole in the
 * evidence: <em>no class in the suite produces either outcome</em>, so the markers, the two
 * counters and the verdict gate's cause-naming were exercised by nothing. Each issue
 * recorded that gap rather than implying it was covered, and homed the demonstration here.
 *
 * <p><b>Why they are not {@code *Test} classes, and why that is not a trick.</b>
 * {@code run-suite.sh} discovers by {@code grep 'Test\.class$'}, so a name that does not end
 * in {@code Test} is compiled into the bundle and never picked up. That is the whole seam:
 * these classes must be RUNNABLE by an explicit class list and UNREACHABLE by the gated
 * suite, because the fixes they demonstrate make a container abort or failure fail the run.
 * A fixture that deliberately breaks its container cannot live inside the thing it breaks.
 *
 * <p><b>Read together they are one run, deliberately.</b> The gate drives all three in a
 * single runner invocation, which is the only way to reach the case both counters are for
 * at once: {@code containersAborted=1} and {@code containersFailed=1} on one summary line,
 * with the identity ALSO short — the shape mcp#51's independent (rather than chained)
 * cause-naming was written for and which no test had ever produced.
 */
public final class ContainerMarkerProbes {

    private ContainerMarkerProbes() {}

    /**
     * The mcp#54 case: a {@code @BeforeAll} that assumes its way out. Both tests are
     * DISCOVERED — so they land in {@code total} — and reach no bucket at all, which is why
     * this breaks the verdict identity and why the coverage loss is real rather than a skip.
     */
    public static class Aborting {
        @BeforeAll
        static void assumeOutOfExistence() {
            assumeTrue(false, "mcp#54 probe: the container assumed its way out");
        }

        @Test
        void neverRunsButIsCounted() {
            assertTrue(true);
        }

        @Test
        void norDoesThisOne() {
            assertTrue(true);
        }
    }

    /**
     * The mcp#51 case: every test passes and the teardown throws. The counts BALANCE for
     * this class — both tests ran and reported — so nothing about the identity can see it,
     * and {@code containersFailed} is the only thing that does.
     */
    public static class Failing {
        @Test
        void passes() {
            assertTrue(true);
        }

        @Test
        void alsoPasses() {
            assertTrue(true);
        }

        @AfterAll
        static void teardownThrows() {
            throw new IllegalStateException("mcp#51 probe: the teardown died after both tests passed");
        }
    }

    /**
     * THE CONTROL, and without it the other two prove nothing: counters stuck at 1 would
     * read exactly like counters that work. This class must contribute two passes and zero
     * to both container counters.
     */
    public static class Healthy {
        @Test
        void passes() {
            assertTrue(true);
        }

        @Test
        void alsoPasses() {
            assertTrue(true);
        }
    }
}
