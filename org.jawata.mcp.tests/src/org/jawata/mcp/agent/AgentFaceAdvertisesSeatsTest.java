package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jawata.mcp.engine.EngineApi;
import org.jawata.mcp.execution.ScheduledHires;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.spi.host.EngineSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S17 (R3 live): the agent as the framework builds it advertises the seats the engine serves as its commands, each under the
 * word a person types, in the group {@code seat}. The first live run of the chat against the agent showed an empty list, because the face's
 * default list was a placeholder; this class holds the wire from the engine's seats-folder setting to the session's advertised commands.
 */
class AgentFaceAdvertisesSeatsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The commands the default face advertises to a session opened on an engine whose seats-folder setting is {@code folder} (null: not set). */
    private static List<JsonNode> advertisedBy(String folder) {
        EngineSettings settings = key -> ScheduledHires.SEATS_SETTING.equals(key) ? Optional.ofNullable(folder) : Optional.empty();
        EngineApi engine = (EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> "settings".equals(method.getName()) ? settings : null);
        List<JsonNode> said = new ArrayList<>();
        AgentFace face = new AgentFace();
        face.attach(engine);
        AcpServer server = face.open((prompt, chunks) -> chunks.accept("x"), message -> said.add(JSON.valueToTree(message)), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        List<JsonNode> commands = new ArrayList<>();
        for (JsonNode message : said) {
            JsonNode update = message.path("params").path("update");
            if ("available_commands_update".equals(update.path("sessionUpdate").asText())) {
                update.path("availableCommands").forEach(commands::add);
            }
        }
        return commands;
    }

    @Test
    @DisplayName("the seats of the folder the engine setting names are advertised, each under its typed word with its purpose, in the group seat, and a switched-off seat is not")
    void theServedSeatsAreTheAdvertisedCommands(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("architect.md"), "---\nname: architect\ncommand: refactor\npurpose: Reviews the design of a change.\n---\n\nYou are the architect seat.\n");
        Files.writeString(dir.resolve("worker.md"), "---\nname: worker\npurpose: Carries out one instruction.\n---\n\nYou are the worker seat.\n");
        Files.writeString(dir.resolve("off.md"), "---\nname: off\ncommand: nope\nenabled: false\n---\n\nSwitched off.\n");

        List<JsonNode> commands = advertisedBy(dir.toString());

        List<String> names = commands.stream().map(c -> c.path("name").asText()).toList();
        assertTrue(names.contains("refactor"), names.toString());
        assertTrue(names.contains("worker"), "a seat with no typed word is offered under its name: " + names);
        assertTrue(!names.contains("nope") && !names.contains("off"), "a switched-off seat was advertised: " + names);
        JsonNode refactor = commands.stream().filter(c -> "refactor".equals(c.path("name").asText())).findFirst().orElseThrow();
        assertEquals("Reviews the design of a change.", refactor.path("description").asText());
        assertEquals("seat", refactor.path("_meta").path("jawata/group").asText());
    }

    @Test
    @DisplayName("D53 (d): a seat whose file says typed: false is off the list, and the skills of the skills folder are on it in the group skill")
    void theListCarriesTheTypedSeatsAndTheSkills(@TempDir Path dir) throws Exception {
        Path seats = Files.createDirectory(dir.resolve("seats"));
        Path skills = Files.createDirectory(dir.resolve("skills"));
        Files.writeString(seats.resolve("report.md"), "---\nname: report\ncommand: report\npurpose: Drafts a bug report.\n---\n\nYou are the report seat.\n");
        Files.writeString(seats.resolve("observer.md"), "---\nname: observer\ncommand: observer\ntyped: false\npurpose: Watches.\n---\n\nYou watch.\n");
        Files.writeString(skills.resolve("sprint.md"), "---\nname: sprint\npurpose: Runs the pipeline.\n---\n\nThe sprint text.\n");
        EngineSettings settings = key -> switch (key) {
            case ScheduledHires.SEATS_SETTING -> Optional.of(seats.toString());
            case AgentFace.SKILLS_SETTING -> Optional.of(skills.toString());
            default -> Optional.empty();
        };
        EngineApi engine = (EngineApi) Proxy.newProxyInstance(EngineApi.class.getClassLoader(), new Class<?>[] {EngineApi.class},
            (proxy, method, args) -> "settings".equals(method.getName()) ? settings : null);
        List<JsonNode> said = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        AgentFace face = new AgentFace();
        face.attach(engine);
        AcpServer server = face.open((prompt, chunks) -> prompts.add(prompt), message -> said.add(JSON.valueToTree(message)), null);
        server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
        List<String> listed = new ArrayList<>();
        String sessionId = null;
        for (JsonNode message : said) {
            sessionId = message.path("result").path("sessionId").asText(sessionId);
            message.path("params").path("update").path("availableCommands").forEach(c -> listed.add(c.path("_meta").path("jawata/group").asText() + ":" + c.path("name").asText()));
        }
        assertEquals(List.of("seat:report", "skill:sprint"), listed);

        var prompt = JSON.createObjectNode().put("sessionId", sessionId);
        prompt.putArray("prompt").addObject().put("type", "text").put("text", "/sprint the plan");
        server.receive(JsonRpcMessage.request(2, "session/prompt", prompt));
        assertTrue(prompts.get(0).startsWith("The sprint text.") && prompts.get(0).endsWith("ARGUMENTS: the plan"), "picking a word runs what it stands for: " + prompts);
    }

    @Test
    @DisplayName("the control: with no seats folder set the agent advertises no command")
    void withNoFolderNoCommandIsAdvertised() {
        assertEquals(List.of(), advertisedBy(null));
    }
}
