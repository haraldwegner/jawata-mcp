package org.jawata.spi.host;

/**
 * Where the engine listens, and who is told when it does (Sprint 28k, E16; contract §4.2).
 *
 * <p>The host decides the address and the key; the engine binds them and reports back through {@link #listening}. What the host
 * does with the report is the host's business: the standalone engine prints the start line a supervisor waits for.</p>
 *
 * <p>Contract §4.2 also lists a {@code kind()} (stdio or HTTP) and a {@code ResolvedToken} key. Neither is here: nothing asks the
 * kind until the host assembles the engine itself (E18), and the key is the token's value, since the form it is disclosed in is
 * the standalone host's own (the start line), not something the engine needs.</p>
 */
public interface EndpointPolicy {

    /** The address to bind. */
    String bindAddress();

    /** The port to bind; 0 asks the operating system for a free one. */
    int port();

    /** The bearer token every request must carry. */
    String key();

    /** The listener is up: here is where it can be reached. */
    void listening(McpEndpoint at);
}
