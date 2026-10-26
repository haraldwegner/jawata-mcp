package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.util.Optional;

import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every host that listens promises about where (Sprint 28k, Stage 9; contract §4.2 and §18 ring 1): an address and a port the
 * operating system can bind, a key that is not blank when there is one, and, when there is none, an address only this machine can
 * reach, because the transport serves an endpoint without a key unauthenticated and refuses it anywhere else. Being told where the
 * listener is never throws into the engine.
 *
 * <p>The headless policy prints its start line when it is told; the line is the supervisor's contract and is read by the end-to-end
 * gate, so this contract only checks that telling does not fail.</p>
 */
public abstract class EndpointPolicyContract {

    /**
     * @return the policy under test
     * @throws Exception when the implementation cannot be built
     */
    protected abstract EndpointPolicy policy() throws Exception;

    @Test
    @DisplayName("the address names a host and the port is one the operating system can bind or choose")
    void theAddressAndPortCanBeBound() throws Exception {
        EndpointPolicy policy = policy();

        assertNotNull(policy.bindAddress());
        assertFalse(policy.bindAddress().isBlank(), "an address to bind");
        assertTrue(policy.port() >= 0 && policy.port() <= 65535, "0 asks for a free port; got " + policy.port());
    }

    @Test
    @DisplayName("a key, when the host offers one, is not blank; and with none the address is a loopback one")
    void aKeyIsNotBlankAndNoKeyMeansLoopback() throws Exception {
        EndpointPolicy policy = policy();

        Optional<String> key = policy.key();

        assertNotNull(key, "an absent key is an empty Optional, never null");
        if (key.isPresent()) {
            assertFalse(key.get().isBlank(), "a blank key authenticates nothing");
        } else {
            assertTrue(InetAddress.getByName(policy.bindAddress()).isLoopbackAddress(),
                "an endpoint with no key is served without authentication, so only this machine may reach it: " + policy.bindAddress());
        }
    }

    @Test
    @DisplayName("being told where the listener is does not throw")
    void beingToldWhereTheListenerIsDoesNotThrow() throws Exception {
        EndpointPolicy policy = policy();
        McpEndpoint at = new McpEndpoint(URI.create("http://127.0.0.1:" + Math.max(policy.port(), 1) + "/mcp"), policy.key());

        assertDoesNotThrow(() -> policy.listening(at));
    }
}
