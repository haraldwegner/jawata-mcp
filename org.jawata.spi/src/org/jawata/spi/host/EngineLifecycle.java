package org.jawata.spi.host;

/**
 * What the engine tells its host when the listener fails (Sprint 28k, E16; contract §4.2), so the host decides what that means for
 * its process. The engine never ends the process itself.
 *
 * <p>The standalone engine ends with a distinct status for each, which a supervisor reads: 13 for a port that is taken, 70 for a
 * listener that went deaf behind a living process. An IDE shows a notice and may restart the listener.</p>
 */
public interface EngineLifecycle {

    /** The listener has stopped answering while the process is alive. */
    void listenerDeaf();

    /**
     * The listener could not be started.
     *
     * @param port  the port that was asked for
     * @param cause why it could not be bound
     */
    void cannotListen(int port, Exception cause);
}
