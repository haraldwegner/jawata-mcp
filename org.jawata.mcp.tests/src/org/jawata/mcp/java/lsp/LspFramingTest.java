package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16a: the language protocol's framing, a Content-Length header in bytes, a blank line and a body, carried over the messages the
 * agent's wire already uses. Pure streams, no engine.
 */
class LspFramingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static byte[] frame(String header, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return (header.replace("{n}", String.valueOf(bytes.length)) + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a message written and read again is the same message, for a request, a notification and a null result")
    void aMessageSurvivesTheWire() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        LspFraming.write(wire, JsonRpcMessage.request(1, "textDocument/hover", JSON.readTree("{\"a\":[1,2]}")));
        LspFraming.write(wire, JsonRpcMessage.notification("initialized", JSON.readTree("{}")));
        LspFraming.write(wire, JsonRpcMessage.successResponse(1, null));

        InputStream in = new ByteArrayInputStream(wire.toByteArray());
        JsonRpcMessage request = LspFraming.read(in);
        JsonRpcMessage notification = LspFraming.read(in);
        JsonRpcMessage response = LspFraming.read(in);

        assertTrue(request.isRequest());
        assertEquals("textDocument/hover", request.getMethod());
        assertTrue(notification.isNotification());
        assertTrue(response.isResponse(), "the null result is a response");
        assertNull(LspFraming.read(in), "the input ended between frames");
    }

    @Test
    @DisplayName("the length is in bytes: a body with characters outside ASCII is longer on the wire than in characters")
    void theLengthIsBytes() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        LspFraming.write(wire, JsonRpcMessage.notification("window/logMessage", JSON.readTree("{\"message\":\"grüße 😀\"}")));

        byte[] bytes = wire.toByteArray();
        String text = new String(bytes, StandardCharsets.UTF_8);
        int declared = Integer.parseInt(text.substring("Content-Length: ".length(), text.indexOf("\r\n")));
        int bodyBytes = bytes.length - text.indexOf("\r\n\r\n") - 4;
        assertEquals(bodyBytes, declared);
        assertTrue(declared > text.substring(text.indexOf("\r\n\r\n") + 4).length(), "bytes outnumber characters here");

        JsonRpcMessage read = LspFraming.read(new ByteArrayInputStream(bytes));
        assertEquals("grüße 😀", read.getParams().get("message").asText());
    }

    @Test
    @DisplayName("an extra header line and a header written in other letter case are accepted")
    void otherHeadersAreIgnoredAndTheNameIsCaseBlind() throws Exception {
        byte[] bytes = frame("content-length: {n}\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8", "{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");

        assertEquals("exit", LspFraming.read(new ByteArrayInputStream(bytes)).getMethod());
    }

    @Test
    @DisplayName("a frame that arrives a byte at a time is read whole")
    void aSlowStreamIsRead() throws Exception {
        byte[] bytes = frame("Content-Length: {n}", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"shutdown\"}");
        InputStream oneByteAtATime = new InputStream() {
            private int at;

            @Override
            public int read() {
                return at < bytes.length ? bytes[at++] & 0xff : -1;
            }

            @Override
            public int read(byte[] into, int from, int length) {
                if (length == 0) {
                    return 0;
                }
                int one = read();
                if (one < 0) {
                    return -1;
                }
                into[from] = (byte) one;
                return 1;
            }
        };

        assertEquals("shutdown", LspFraming.read(oneByteAtATime).getMethod());
    }

    @Test
    @DisplayName("a frame without a length, with a length that is not a number, or cut short is an error, and an empty input is the end")
    void brokenFramesAreErrors() throws Exception {
        assertNull(LspFraming.read(new ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class, () -> LspFraming.read(new ByteArrayInputStream("Content-Type: x\r\n\r\n{}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> LspFraming.read(new ByteArrayInputStream("Content-Length: ten\r\n\r\n{}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(EOFException.class, () -> LspFraming.read(new ByteArrayInputStream("Content-Length: 50\r\n\r\n{}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(EOFException.class, () -> LspFraming.read(new ByteArrayInputStream("Content-Length: 2\r\n".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> LspFraming.read(new ByteArrayInputStream(frame("Content-Length: {n}", "not json"))));
    }
}
