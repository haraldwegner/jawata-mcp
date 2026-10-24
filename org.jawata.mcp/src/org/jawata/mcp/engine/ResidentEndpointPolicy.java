package org.jawata.mcp.engine;

import org.jawata.mcp.transport.ResolvedToken;
import org.jawata.mcp.transport.TransportConfig;
import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.McpEndpoint;

/**
 * Where the engine that runs alone listens, and the one line it prints when it does (Sprint 28k, E16; contract §5).
 *
 * <p>The start line is {@code READY url=http://<bind>:<port> <token field>} on stdout, exactly once. It is the contract with
 * whatever supervises the process (jawata-studio reads it to learn the port), so its bytes do not change. The token field is the
 * token's own disclosure decision (studio#14): with a token file it names the FILE, so the manager's log, which tees stdout to
 * disk, never holds the secret.</p>
 */
public final class ResidentEndpointPolicy implements EndpointPolicy {

    private final TransportConfig config;
    private final ResolvedToken token;

    /**
     * @param config the address and port the process was started with
     * @param token  the token those arguments resolved to
     */
    public ResidentEndpointPolicy(TransportConfig config, ResolvedToken token) {
        this.config = config;
        this.token = token;
    }

    @Override
    public String bindAddress() {
        return config.getBindAddress();
    }

    @Override
    public int port() {
        return config.getPort();
    }

    @Override
    public String key() {
        return token.token();
    }

    @Override
    public void listening(McpEndpoint at) {
        System.out.println("READY url=http://" + config.getBindAddress() + ":" + at.url().getPort()
            + " " + token.readyTokenField());
    }
}
