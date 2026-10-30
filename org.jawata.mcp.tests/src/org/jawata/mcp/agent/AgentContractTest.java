package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S4 (D1, D3, D6): the agent plays the recorded sessions of the contract. The host's side of each recording is fed to the
 * agent; the agent's answer is compared with the recorded agent's by SHAPE and by the fields the contract names, never by prose: the
 * handshake's protocol version and agent identity, a session id, the streamed answer text in order, the stop reason, and the group of
 * every advertised command.
 *
 * <p>R1 is a real turn. R3 is R1 with every advertised command tagged with its group: the agent must advertise the commands it is
 * given with their groups. Where the commands come from in production (the seats served to hosts) is S14's; here they are supplied.
 * R2, which holds a tool call and a permission request answered by the host, is S6's and S9's, where tool calls arise.</p>
 *
 * <p>A check that cannot fail proves nothing, so the same comparison is run over answers that are wrong and must find them.</p>
 */
class AgentContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Line(String dir, JsonNode msg) {
    }

    private static List<Line> recording(String name) throws IOException {
        try (InputStream in = AgentContractTest.class.getResourceAsStream("/test-resources/acp/" + name + ".jsonl")) {
            assertNotNull(in, "the recorded session " + name + " is not among the test resources");
            List<Line> lines = new ArrayList<>();
            for (String text : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!text.isBlank()) {
                    JsonNode line = JSON.readTree(text);
                    lines.add(new Line(line.get("dir").asText(), line.get("msg")));
                }
            }
            return lines;
        }
    }

    private static List<String> recordedAnswer(List<Line> recording) {
        List<String> chunks = new ArrayList<>();
        for (Line line : recording) {
            JsonNode update = line.msg().path("params").path("update");
            if (line.dir().equals("recv") && "agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                chunks.add(update.path("content").path("text").asText());
            }
        }
        return chunks;
    }

    private static List<AdvertisedCommand> recordedCommands(List<Line> recording) {
        List<AdvertisedCommand> commands = new ArrayList<>();
        for (Line line : recording) {
            JsonNode update = line.msg().path("params").path("update");
            if (line.dir().equals("recv") && "available_commands_update".equals(update.path("sessionUpdate").asText())) {
                for (JsonNode entry : update.path("availableCommands")) {
                    String group = entry.path("_meta").path("jawata/group").asText(null);
                    if (group != null) {
                        commands.add(new AdvertisedCommand(entry.path("name").asText(), entry.path("description").asText(), group));
                    }
                }
            }
        }
        return commands;
    }

    private static EngineApi anEngine() {
        return (EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> null);
    }

    /** What the agent said while the recorded host spoke. */
    private static List<JsonNode> play(List<Line> recording, AgentBrain brain, List<AdvertisedCommand> commands) throws IOException {
        List<JsonNode> said = new ArrayList<>();
        AgentFace face = new AgentFace(() -> commands);
        face.attach(anEngine());
        AcpServer server = face.open(brain, message -> said.add(JSON.valueToTree(message)));
        String session = null;
        for (Line line : recording) {
            if (!line.dir().equals("send")) {
                continue;
            }
            ObjectNode send = line.msg().deepCopy();
            if (session != null && send.path("params").has("sessionId")) {
                ((ObjectNode) send.get("params")).put("sessionId", session);
            }
            server.receive(JSON.treeToValue(send, JsonRpcMessage.class));
            if ("session/new".equals(send.path("method").asText())) {
                session = said.stream().filter(m -> m.has("id") && m.get("id").equals(send.get("id")))
                    .map(m -> m.path("result").path("sessionId").asText()).findFirst().orElse(null);
            }
        }
        return said;
    }

    /** Every way the agent's answer differs from the recorded one in what the contract names. Empty means it matches. */
    private static List<String> mismatches(List<Line> recording, List<JsonNode> said) {
        List<String> problems = new ArrayList<>();
        for (Line line : recording) {
            JsonNode recorded = line.msg();
            if (!line.dir().equals("recv") || !recorded.has("result")) {
                continue;
            }
            JsonNode ours = said.stream().filter(m -> m.has("id") && m.get("id").equals(recorded.get("id"))).findFirst().orElse(null);
            if (ours == null || !ours.has("result")) {
                problems.add("no result for request " + recorded.get("id"));
                continue;
            }
            for (String field : List.of("protocolVersion", "stopReason")) {
                if (recorded.path("result").has(field) && !recorded.path("result").get(field).equals(ours.path("result").get(field))) {
                    problems.add("result." + field + " is " + ours.path("result").get(field) + ", recorded " + recorded.path("result").get(field));
                }
            }
            if (recorded.path("result").has("sessionId") && ours.path("result").path("sessionId").asText().isBlank()) {
                problems.add("no session id");
            }
            if (recorded.path("result").has("agentInfo") && ours.path("result").path("agentInfo").path("name").asText().isBlank()) {
                problems.add("no agent name");
            }
        }
        List<String> recordedChunks = recordedAnswer(recording);
        List<String> chunks = new ArrayList<>();
        int lastChunk = -1;
        int promptResponse = -1;
        for (int i = 0; i < said.size(); i++) {
            JsonNode update = said.get(i).path("params").path("update");
            if ("agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                chunks.add(update.path("content").path("text").asText());
                lastChunk = i;
                if (update.path("messageId").asText().isBlank()) {
                    problems.add("a chunk with no message id");
                }
            }
            if (said.get(i).path("result").has("stopReason")) {
                promptResponse = i;
            }
        }
        if (!recordedChunks.equals(chunks)) {
            problems.add("answer text " + chunks + ", recorded " + recordedChunks);
        }
        if (lastChunk > promptResponse && promptResponse >= 0) {
            problems.add("a chunk came after the prompt's answer");
        }
        List<AdvertisedCommand> recordedCommands = recordedCommands(recording);
        if (!recordedCommands.isEmpty()) {
            Set<String> expected = new TreeSet<>();
            recordedCommands.forEach(c -> expected.add(c.name() + "=" + c.group()));
            Set<String> got = new TreeSet<>();
            for (JsonNode message : said) {
                JsonNode update = message.path("params").path("update");
                if ("available_commands_update".equals(update.path("sessionUpdate").asText())) {
                    for (JsonNode entry : update.path("availableCommands")) {
                        got.add(entry.path("name").asText() + "=" + entry.path("_meta").path("jawata/group").asText());
                    }
                }
            }
            if (!expected.equals(got)) {
                problems.add("advertised commands " + got + ", recorded " + expected);
            }
        }
        return problems;
    }

    @Test
    @DisplayName("R1 — a prompt and its streamed answer: the agent answers the handshake, the session and the prompt as the recorded agent did")
    void r1TheAgentAnswersAsTheRecordedAgentDid() throws IOException {
        List<Line> r1 = recording("R1");
        List<String> answer = recordedAnswer(r1);
        assertFalse(answer.isEmpty(), "the recording holds no answer to replay");

        List<JsonNode> said = play(r1, (prompt, chunks) -> answer.forEach(chunks), List.of());

        assertEquals(List.of(), mismatches(r1, said));
    }

    @Test
    @DisplayName("R3 — R1 with every advertised command tagged with its group: the agent advertises the commands it is given with their groups")
    void r3TheAgentAdvertisesCommandsWithTheirGroups() throws IOException {
        List<Line> r3 = recording("R3");
        List<AdvertisedCommand> commands = recordedCommands(r3);
        assertTrue(commands.stream().map(AdvertisedCommand::group).collect(Collectors.toSet()).containsAll(Set.of("seat", "skill", "utility")),
            "the recording does not hold all three groups: " + commands);
        List<String> answer = recordedAnswer(r3);

        List<JsonNode> said = play(r3, (prompt, chunks) -> answer.forEach(chunks), commands);

        assertEquals(List.of(), mismatches(r3, said));
    }

    @Test
    @DisplayName("THE CONTROL — the same comparison finds a wrong answer text, a missing command group and a silent agent")
    void theComparisonCanFail() throws IOException {
        List<Line> r3 = recording("R3");
        List<AdvertisedCommand> commands = recordedCommands(r3);
        List<String> answer = recordedAnswer(r3);

        List<JsonNode> wrongText = play(r3, (prompt, chunks) -> chunks.accept("not what was recorded"), commands);
        List<JsonNode> noGroups = play(r3, (prompt, chunks) -> answer.forEach(chunks), List.of());

        assertFalse(mismatches(r3, wrongText).isEmpty(), "a wrong answer text went unnoticed");
        assertFalse(mismatches(r3, noGroups).isEmpty(), "missing command groups went unnoticed");
        assertFalse(mismatches(r3, List.of()).isEmpty(), "a silent agent went unnoticed");
    }

    @Test
    @DisplayName("a session the agent never opened is refused with an error, not answered")
    void anUnknownSessionIsRefused() throws IOException {
        List<JsonNode> said = new ArrayList<>();
        AgentFace face = new AgentFace();
        face.attach(anEngine());
        AcpServer server = face.open((prompt, chunks) -> chunks.accept("never said"), message -> said.add(JSON.valueToTree(message)));

        server.receive(JSON.treeToValue(JSON.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"nope\",\"prompt\":[]}}"),
            JsonRpcMessage.class));

        assertEquals(1, said.size());
        assertTrue(said.get(0).has("error"), "an unknown session was answered: " + said.get(0));
    }

    @Test
    @DisplayName("a face the engine has not attached opens no connection")
    void aDetachedFaceOpensNoConnection() {
        AgentFace face = new AgentFace();

        assertThrows(IllegalStateException.class, () -> face.open((prompt, chunks) -> { }, message -> { }));
        face.attach(anEngine());
        face.detach();
        assertThrows(IllegalStateException.class, () -> face.open((prompt, chunks) -> { }, message -> { }));
    }
}
