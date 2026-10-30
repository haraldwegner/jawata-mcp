package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.fixtures.TransportFixtures;
import org.jawata.mcp.transport.HttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S5 (D1, D6; E2): the program a host's agent setting names is run for real. The dist's {@code jawata.jar --acp} is spawned
 * under the environment an editor gives its children ({@code ELECTRON_RUN_AS_NODE=1}, {@code VSCODE_*}), the host's side of the recorded
 * session R1 is written to its stdin, and the agent's answers are read from its stdout. The agent behind it is the face on a keyed
 * listener, answering with the text R1 recorded. A wrong key must end the program with a refusal, not a silent hang.
 */
class AcpBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long WAIT_SECONDS = 60;

    /** The host's side of a recorded session, in order. */
    private static List<ObjectNode> hostMessagesOf(String name) throws IOException {
        try (InputStream in = AcpBridgeTest.class.getResourceAsStream("/test-resources/acp/" + name + ".jsonl")) {
            assertNotNull(in, "the recorded session " + name + " is not among the test resources");
            List<ObjectNode> sent = new ArrayList<>();
            for (String text : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!text.isBlank()) {
                    JsonNode line = JSON.readTree(text);
                    if (line.get("dir").asText().equals("send")) {
                        sent.add((ObjectNode) line.get("msg"));
                    }
                }
            }
            return sent;
        }
    }

    /** The text the recorded agent streamed, chunk by chunk. */
    private static List<String> recordedAnswer(String name) throws IOException {
        List<String> chunks = new ArrayList<>();
        try (InputStream in = AcpBridgeTest.class.getResourceAsStream("/test-resources/acp/" + name + ".jsonl")) {
            assertNotNull(in, "the recorded session " + name + " is not among the test resources");
            for (String text : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!text.isBlank()) {
                    JsonNode line = JSON.readTree(text);
                    JsonNode update = line.path("msg").path("params").path("update");
                    if (line.get("dir").asText().equals("recv") && "agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                        chunks.add(update.path("content").path("text").asText());
                    }
                }
            }
        }
        return chunks;
    }

    /** The agent face on a listener that asks for {@code key}; it answers a prompt with {@code answer}. */
    private static HttpTransport servedBy(String key, List<String> answer) throws Exception {
        AgentFace face = new AgentFace(List::of, () -> (prompt, chunks) -> answer.forEach(chunks));
        face.attach((EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> null));
        HttpTransport transport = new HttpTransport(TransportFixtures.plainEndpoint(0, "127.0.0.1", key),
            new TransportFixtures.RecordingLifecycle());
        transport.mount(face.path(), face.handler());
        TransportFixtures.runInBackground(transport);
        transport.getActualPort();
        return transport;
    }

    /** The dist's program, started as an editor would start it. */
    private static Process startBridge(int port, String key) throws IOException {
        String dist = System.getProperty("jawata.dist.root");
        assertNotNull(dist, "jawata.dist.root is not set: this test runs in the dist's own framework (build/run-suite.sh)");
        Path jar = Path.of(dist, "jawata.jar");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
            jar.toString(), "--acp", "--url", "http://127.0.0.1:" + port, "--key", key);
        builder.environment().put("ELECTRON_RUN_AS_NODE", "1");
        builder.environment().put("VSCODE_PID", "4242");
        builder.environment().put("VSCODE_IPC_HOOK", "/tmp/vscode-ipc-none.sock");
        return builder.start();
    }

    /** Every line the process writes to stdout, as it arrives. */
    private static BlockingQueue<String> linesOf(Process process) {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // the process ended; what was read is all there is
            }
        }, "bridge-stdout");
        reader.setDaemon(true);
        reader.start();
        return lines;
    }

    private static JsonNode nextMessage(BlockingQueue<String> lines) throws Exception {
        String line = lines.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(line, "the program said nothing within " + WAIT_SECONDS + " seconds");
        return JSON.readTree(line);
    }

    private static void write(OutputStream stdin, JsonNode message) throws IOException {
        stdin.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    @Test
    @DisplayName("R1 through stdio — the dist program spawned under an editor environment carries the recorded session to the agent and its answer back")
    void r1PlaysThroughTheStdioProgram() throws Exception {
        List<String> answer = recordedAnswer("R1");
        assertFalse(answer.isEmpty(), "the recording holds no answer to replay");
        HttpTransport transport = servedBy("tok", answer);
        Process bridge = startBridge(transport.getActualPort(), "tok");
        try {
            BlockingQueue<String> lines = linesOf(bridge);
            OutputStream stdin = bridge.getOutputStream();
            String session = null;
            List<String> streamed = new ArrayList<>();
            JsonNode promptAnswer = null;
            for (ObjectNode sent : hostMessagesOf("R1")) {
                if (session != null && sent.path("params").has("sessionId")) {
                    ((ObjectNode) sent.get("params")).put("sessionId", session);
                }
                write(stdin, sent);
                if (!sent.has("id")) {
                    continue;
                }
                JsonNode reply;
                do {
                    reply = nextMessage(lines);
                    JsonNode update = reply.path("params").path("update");
                    if ("agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                        streamed.add(update.path("content").path("text").asText());
                    }
                } while (!reply.has("id") || !reply.get("id").equals(sent.get("id")));
                if ("session/new".equals(sent.path("method").asText())) {
                    session = reply.path("result").path("sessionId").asText();
                }
                if ("session/prompt".equals(sent.path("method").asText())) {
                    promptAnswer = reply;
                }
            }
            assertNotNull(promptAnswer, "R1 holds no prompt");
            assertEquals("end_turn", promptAnswer.path("result").path("stopReason").asText());
            assertEquals(answer, streamed);
        } finally {
            bridge.destroyForcibly();
            transport.close();
        }
    }

    @Test
    @DisplayName("CONTROL — a wrong key ends the program with the refusal status and a line saying so, and nothing reaches stdout")
    void aWrongKeyIsRefused() throws Exception {
        HttpTransport transport = servedBy("tok", List.of("never sent"));
        Process bridge = startBridge(transport.getActualPort(), "wrong");
        try {
            BlockingQueue<String> lines = linesOf(bridge);
            write(bridge.getOutputStream(), hostMessagesOf("R1").get(0));

            assertTrue(bridge.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "the program did not end after its key was refused");
            assertEquals(3, bridge.exitValue());
            String said = new String(bridge.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(said.contains("401"), "stderr does not name the refusal: " + said);
            assertTrue(lines.isEmpty(), "something reached stdout: " + lines);
        } finally {
            bridge.destroyForcibly();
            transport.close();
        }
    }
}
