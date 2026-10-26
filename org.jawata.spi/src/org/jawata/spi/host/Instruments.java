package org.jawata.spi.host;

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
}
