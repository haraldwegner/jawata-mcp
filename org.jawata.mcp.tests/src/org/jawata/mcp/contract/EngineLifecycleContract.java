package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.jawata.spi.host.EngineLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every host promises when the engine tells it its listener failed (Sprint 28k, Stage 9; contract §4.2 and §18 ring 1): each
 * report reaches the host exactly once, and taking it never throws into the engine, whatever the host decides to do about it.
 *
 * <p>The standalone host's answer ends the process (exit status 13 for a port that is taken, 70 for a listener gone deaf), so the
 * headless implementation cannot run these assertions in a test process; its two exits are the end-to-end gate's promise P5. A host
 * that only shows a notice, as the Eclipse host does and the fake does, runs this contract.</p>
 */
public abstract class EngineLifecycleContract {

    /**
     * @return the lifecycle under test, fresh for each test
     * @throws Exception when the implementation cannot be built
     */
    protected abstract EngineLifecycle lifecycle() throws Exception;

    /**
     * @return how the host has been told so far, one entry per report, in order; the entries name which report it was
     * @throws Exception when the implementation cannot be read
     */
    protected abstract List<String> reportsSeen() throws Exception;

    @Test
    @DisplayName("a deaf listener is reported to the host once, and reporting it never throws")
    void aDeafListenerIsReportedOnce() throws Exception {
        EngineLifecycle lifecycle = lifecycle();

        assertDoesNotThrow(lifecycle::listenerDeaf);

        assertEquals(1, reportsSeen().size(), "got: " + reportsSeen());
    }

    @Test
    @DisplayName("a listener that could not start is reported once with the port that was asked for, and reporting it never throws")
    void aListenerThatCouldNotStartIsReportedOnce() throws Exception {
        EngineLifecycle lifecycle = lifecycle();

        assertDoesNotThrow(() -> lifecycle.cannotListen(8123, new IOException("address already in use")));

        List<String> seen = reportsSeen();
        assertEquals(1, seen.size(), "got: " + seen);
        assertTrue(seen.get(0).contains("8123"), "the report names the port that was asked for: " + seen);
    }

    @Test
    @DisplayName("the same report reads the same each time, and each call is one report")
    void eachCallIsOneReport() throws Exception {
        EngineLifecycle lifecycle = lifecycle();

        lifecycle.listenerDeaf();
        lifecycle.cannotListen(1, new IOException("no"));
        lifecycle.listenerDeaf();

        List<String> seen = reportsSeen();
        assertEquals(3, seen.size(), "got: " + seen);
        assertEquals(seen.get(0), seen.get(2), "the same report reads the same: " + seen);
    }
}
