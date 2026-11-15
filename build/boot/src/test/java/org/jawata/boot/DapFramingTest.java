package org.jawata.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28i, Stage 25 (step A1, ARCHITECTURE-28i-s25.md F1): the debug adapter protocol is not JSON-RPC, and the resident's route reads every
 * message as JSON-RPC, so the launcher carries each debug message inside a JSON-RPC envelope on the way in and takes it out again on the way
 * back. The editor still reads and writes plain {@code Content-Length} frames of debug messages.
 */
class DapFramingTest {

    private static byte[] frame(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ("Content-Length: " + bytes.length + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an editor's debug message read from stdin is handed to the route inside a JSON-RPC envelope, unchanged")
    void anInboundMessageIsEnveloped() throws IOException {
        String request = "{\"seq\":1,\"type\":\"request\",\"command\":\"initialize\",\"arguments\":{\"adapterID\":\"jawata\"}}";

        String sent = Bridge.DAP.reader(new ByteArrayInputStream(frame(request))).next();

        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"dap\",\"params\":" + request + "}", sent);
    }

    @Test
    @DisplayName("a route message is written to stdout as the debug message it carries, with its length counted in bytes")
    void anOutboundMessageIsUnwrapped() throws IOException {
        String event = "{\"seq\":7,\"type\":\"event\",\"event\":\"output\",\"body\":{\"output\":\"ünï 😀 } \\\" { done\"}}";
        String routed = "{\"jsonrpc\":\"2.0\",\"method\":\"dap\",\"params\":" + event + "}";
        ByteArrayOutputStream written = new ByteArrayOutputStream();

        Bridge.DAP.write(new PrintStream(written, true, StandardCharsets.UTF_8), routed);

        assertEquals(new String(frame(event), StandardCharsets.UTF_8), written.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the carried message is found whatever order the envelope's members come in, and braces or quotes inside its strings do not end it early")
    void theCarriedMessageIsFoundByItsMember() throws IOException {
        String response = "{\"seq\":3,\"type\":\"response\",\"request_seq\":1,\"success\":true,\"command\":\"initialize\",\"body\":{\"a\":\"}}\"}}";
        String routed = "{\"params\":" + response + ",\"method\":\"dap\",\"jsonrpc\":\"2.0\"}";
        ByteArrayOutputStream written = new ByteArrayOutputStream();

        Bridge.DAP.write(new PrintStream(written, true, StandardCharsets.UTF_8), routed);

        assertEquals(new String(frame(response), StandardCharsets.UTF_8), written.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CONTROL — a route message that does not carry a debug message writes nothing to stdout")
    void aMessageWithNoDebugMessageIsNotWritten() throws IOException {
        ByteArrayOutputStream written = new ByteArrayOutputStream();

        Bridge.DAP.write(new PrintStream(written, true, StandardCharsets.UTF_8), "{\"jsonrpc\":\"2.0\",\"method\":\"other\",\"params\":{\"x\":1}}");
        Bridge.DAP.write(new PrintStream(written, true, StandardCharsets.UTF_8), "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}");

        assertEquals(0, written.size());
    }

    @Test
    @DisplayName("the end of the editor's input ends the reader")
    void theEndOfInputEndsTheReader() throws IOException {
        assertNull(Bridge.DAP.reader(new ByteArrayInputStream(new byte[0])).next());
    }
}
