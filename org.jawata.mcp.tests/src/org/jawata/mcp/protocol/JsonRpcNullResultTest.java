package org.jawata.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16a: JSON-RPC says a success response carries a {@code result} member, and for a request with nothing to answer (a hover over
 * white space, {@code shutdown}) that member is {@code null}. The message class dropped a null result when writing and could not tell one
 * from an absent member when reading, so an editor's {@code "result": null} was neither a request, a notification nor a response. These
 * tests pin both directions, and that every other message reads and writes exactly as before, which is what the ACP and MCP wires rely on.
 */
class JsonRpcNullResultTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("a success response with nothing to answer is written with its result member set to null, and with no error")
    void aNullResultIsWritten() throws Exception {
        String wire = JSON.writeValueAsString(JsonRpcMessage.successResponse(3, null));

        JsonNode written = JSON.readTree(wire);
        assertTrue(written.has("result"), "the member is required by JSON-RPC: " + wire);
        assertTrue(written.get("result").isNull(), wire);
        assertFalse(written.has("error"), wire);
        assertEquals(3, written.get("id").asInt());
    }

    @Test
    @DisplayName("a response whose result is null is read as a response, with a result that is null")
    void aNullResultIsRead() throws Exception {
        JsonRpcMessage read = JSON.readValue("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":null}", JsonRpcMessage.class);

        assertTrue(read.isResponse(), "a response, and neither a request nor a notification");
        assertFalse(read.isRequest());
        assertFalse(read.isNotification());
        assertTrue(read.getResult() instanceof JsonNode node && node.isNull(), String.valueOf(read.getResult()));
    }

    @Test
    @DisplayName("a null result survives being read and written again")
    void aNullResultRoundTrips() throws Exception {
        String wire = "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":null}";

        assertEquals(wire, JSON.writeValueAsString(JSON.readValue(wire, JsonRpcMessage.class)));
    }

    @Test
    @DisplayName("a message with no result and no error member is still not a response")
    void anAbsentResultIsStillAbsent() throws Exception {
        JsonRpcMessage read = JSON.readValue("{\"jsonrpc\":\"2.0\",\"id\":9}", JsonRpcMessage.class);

        assertFalse(read.isResponse());
    }

    @Test
    @DisplayName("a success with a result, an error, a request and a notification are read and written again byte for byte")
    void everythingElseIsUnchanged() throws Exception {
        String[] wires = {
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"a\":1,\"b\":[true,\"x\"]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":[]}",
            "{\"jsonrpc\":\"2.0\",\"id\":\"s-1\",\"error\":{\"code\":-32601,\"message\":\"no such method\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"x\":null}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{}}"
        };
        for (String wire : wires) {
            assertEquals(wire, JSON.writeValueAsString(JSON.readValue(wire, JsonRpcMessage.class)), wire);
        }
    }
}
