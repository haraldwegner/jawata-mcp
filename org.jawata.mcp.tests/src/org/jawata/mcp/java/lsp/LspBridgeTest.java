package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.fixtures.TransportFixtures;
import org.jawata.mcp.transport.HttpTransport;
import org.jawata.spi.host.HostCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S16a: the program an editor's language client names ({@code jawata.jar --lsp}) is run for real. The dist's jar is spawned
 * under the environment an editor gives its children, the editor's side is written to its stdin as {@code Content-Length} frames, and the
 * server's answers are read back from its stdout the same way. The server behind it is the language face on a keyed listener. The lengths
 * are in BYTES: a frame whose text holds characters outside ASCII is where counting characters instead is seen.
 */
class LspBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long WAIT_SECONDS = 60;

    private static HttpTransport servedBy(String key) throws Exception {
        HostCapabilities host = new HostCapabilities("test", true, true, true, List.of("java"));
        LspFace face = new LspFace(() -> null);
        face.attach((EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> "host".equals(method.getName()) ? host : null));
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", key), new TransportFixtures.RecordingLifecycle());
        transport.mount(face.path(), face.handler());
        TransportFixtures.runInBackground(transport);
        transport.getActualPort();
        return transport;
    }

    private static Process startBridge(int port, String key) throws IOException {
        String dist = System.getProperty("jawata.dist.root");
        assertNotNull(dist, "jawata.dist.root is not set: this test runs in the dist's own framework (build/run-suite.sh)");
        Path jar = Path.of(dist, "jawata.jar");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
            jar.toString(), "--lsp", "--url", "http://127.0.0.1:" + port, "--key", key);
        builder.environment().put("ELECTRON_RUN_AS_NODE", "1");
        builder.environment().put("VSCODE_PID", "4242");
        return builder.start();
    }

    /** Writes one message as the editor's client does: the header counts the bytes of the body. */
    private static void writeFrame(OutputStream stdin, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        stdin.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        stdin.write(bytes);
        stdin.flush();
    }

    /** Reads one frame the way an editor's client does: the header, then exactly that many bytes. */
    private static String readFrame(InputStream stdout) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int b = stdout.read();
            assertTrue(b >= 0, "the program ended in the middle of a frame header: " + header);
            header.write(b);
        }
        String text = header.toString(StandardCharsets.US_ASCII);
        int length = Integer.parseInt(text.substring(text.toLowerCase().indexOf("content-length:") + "content-length:".length()).trim().split("\\s")[0]);
        byte[] body = stdout.readNBytes(length);
        assertEquals(length, body.length, "the frame's body is shorter than its header says");
        return new String(body, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an editor's frames through the dist program reach the language server and its answers come back as frames, with lengths in bytes")
    void framesGoThroughTheStdioProgram() throws Exception {
        HttpTransport transport = servedBy("tok");
        Process bridge = startBridge(transport.getActualPort(), "tok");
        try {
            writeFrame(bridge.getOutputStream(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":null}}");
            JsonNode initialized = JSON.readTree(readFrame(bridge.getInputStream()));
            assertEquals(1, initialized.path("id").asInt());
            assertEquals(2, initialized.path("result").path("capabilities").path("textDocumentSync").path("change").asInt());

            String method = "textDocument/ünïcöde-😀";
            writeFrame(bridge.getOutputStream(), JSON.writeValueAsString(java.util.Map.of("jsonrpc", "2.0", "id", 2, "method", method)));
            JsonNode refused = JSON.readTree(readFrame(bridge.getInputStream()));
            assertEquals(2, refused.path("id").asInt());
            assertTrue(refused.path("error").path("message").asText().endsWith(method),
                "the method with its non-ASCII characters did not survive both framings: " + refused);
        } finally {
            bridge.destroyForcibly();
            transport.close();
        }
    }

    @Test
    @DisplayName("CONTROL — a wrong key ends the program with the refusal status and a line saying so, and nothing reaches stdout")
    void aWrongKeyIsRefused() throws Exception {
        HttpTransport transport = servedBy("tok");
        Process bridge = startBridge(transport.getActualPort(), "wrong");
        try {
            writeFrame(bridge.getOutputStream(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");

            assertTrue(bridge.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "the program did not end after its key was refused");
            assertEquals(3, bridge.exitValue());
            String said = new String(bridge.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(said.contains("401") && said.contains("--lsp"), "stderr does not name the refusal: " + said);
            assertEquals(0, bridge.getInputStream().available(), "something reached stdout");
        } finally {
            bridge.destroyForcibly();
            transport.close();
        }
    }
}
