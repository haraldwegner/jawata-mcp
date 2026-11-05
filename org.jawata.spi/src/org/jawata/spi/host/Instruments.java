package org.jawata.spi.host;

import java.util.Optional;

/**
 * The host's own instruments (Sprint 28k, contract §4.2): what a host can tell the engine about debugging that the engine cannot find out
 * for itself. As built it is whose turn it is to debug. A host that runs programs for the engine adds that to this port with the stage that
 * gives the engine a caller for it, so the port holds only what something asks.
 */
public interface Instruments {

    /** What a host with no instruments of its own answers: nobody else is debugging. */
    Instruments NONE = () -> DebugTurn.FREE;

    /** @return whose turn it is to debug */
    DebugTurn debugTurn();

    /**
     * The host's way of running a program from the person's run configurations for the debugger and the profiler (contract §4.2, §6.1).
     * A host that runs no programs for the engine, such as the headless one, answers nothing, and {@code debug action=launch} then takes a
     * main class and a classpath as before.
     *
     * @return the host's runs, or empty
     */
    default Optional<HostRuns> runs() {
        return Optional.empty();
    }
}
