package org.jawata.mcp.java.lsp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.jawata.mcp.protocol.JsonRpcMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one thing the language protocol adds to JSON-RPC (Sprint 28h, S16a): its framing. A message is a header, a blank line and a body;
 * the header carries the body's length in BYTES, so a body with characters outside ASCII is longer on the wire than in characters.
 * Messages themselves are {@link JsonRpcMessage}, the class the agent's wire already uses, so a language server and an agent do not
 * have two ways to read the same JSON.
 *
 * <p>Other header lines (a content type) are read and ignored. A frame without a length, a length that is not a number, and an input
 * that ends inside a frame are errors of the caller's stream and are reported as {@link IOException}; an input that ends between
 * frames is the end of the conversation and reads as no message.</p>
 */
public final class LspFraming {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LspFraming() {
    }

    /**
     * Reads the next message.
     *
     * @return the message, or null when the input ended between frames
     */
    public static JsonRpcMessage read(InputStream in) throws IOException {
        String body = readBody(in);
        if (body == null) {
            return null;
        }
        try {
            return JSON.readValue(body, JsonRpcMessage.class);
        } catch (JsonProcessingException e) {
            throw new IOException("a frame's body is not a JSON-RPC message: " + e.getOriginalMessage(), e);
        }
    }

    /** Writes one message as one frame and flushes, so a peer waiting on it is not left waiting on a buffer. */
    public static void write(OutputStream out, JsonRpcMessage message) throws IOException {
        byte[] body = JSON.writeValueAsBytes(message);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String readBody(InputStream in) throws IOException {
        int length = -1;
        boolean any = false;
        while (true) {
            String line = readHeaderLine(in);
            if (line == null) {
                if (any) {
                    throw new EOFException("the input ended inside a frame's header");
                }
                return null;
            }
            any = true;
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("a frame's Content-Length is not a number: " + line, e);
                }
            }
        }
        if (length < 0) {
            throw new IOException("a frame has no Content-Length header");
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            throw new EOFException("the input ended inside a frame's body: " + body.length + " of " + length + " bytes");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** One header line without its line break, or null at the end of the input with nothing read. */
    private static String readHeaderLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return c == -1 && line.length() == 0 ? null : line.toString();
    }
}
