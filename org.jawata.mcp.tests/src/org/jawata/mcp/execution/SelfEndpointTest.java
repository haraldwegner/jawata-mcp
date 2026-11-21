package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, the dogfood after release (z34): a real Claude, started through the command line, was not known to the engine as the hire it
 * was. Its plan id travelled only as a header, which a brain's tool server was seen to drop, so the engine took its calls for the anonymous
 * caller's: a full test run it ordered kept no reason on its hire and the seat rules did not see it. The ACP path already puts the id in the
 * URL as well; the command-line path now does too.
 */
class SelfEndpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode serverOf(SelfEndpoint endpoint) throws Exception {
        return JSON.readTree(endpoint.claudeMcpConfig()).path("mcpServers").path(SelfEndpoint.SERVER_NAME);
    }

    @Test
    @DisplayName("28h z34: a Claude hire's tool server names its session in the URL as well as in the header, so a client that drops the header still arrives as that hire; an unbound endpoint names none")
    void aClaudeHiresToolServerCarriesItsSessionInTheUrl() throws Exception {
        SelfEndpoint bound = new SelfEndpoint("http://127.0.0.1:41234/mcp", "secret-key").forSession("plan-7");
        SelfEndpoint unbound = new SelfEndpoint("http://127.0.0.1:41234/mcp", "secret-key");

        JsonNode with = serverOf(bound);
        JsonNode without = serverOf(unbound);

        assertEquals("http://127.0.0.1:41234/mcp?session=plan-7", with.path("url").asText(),
            "the session id was handed over only as a header, which a brain's tool server was seen to drop: " + with);
        assertEquals("plan-7", with.path("headers").path(SelfEndpoint.SESSION_HEADER).asText(),
            "the header the engine reads first is no longer sent: " + with);
        assertFalse(without.path("url").asText().contains("?session="),
            "CONTROL: an unbound endpoint put a session id in the URL: " + without);
        assertFalse(without.path("headers").has(SelfEndpoint.SESSION_HEADER),
            "CONTROL: an unbound endpoint sent a session header: " + without);
    }
}
