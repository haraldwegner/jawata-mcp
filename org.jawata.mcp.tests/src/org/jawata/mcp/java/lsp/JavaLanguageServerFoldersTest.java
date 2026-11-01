package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16a, workspace folders and progress: the folders an editor opens are made known to the engine through the gate's {@code project}
 * tool unless the engine already holds a project that contains them, folders added later are taken the same way, folders removed are
 * left alone (the engine's projects are shared with agents), and an editor that takes progress is shown the loading.
 */
class JavaLanguageServerFoldersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lsp-folders-test-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
    private final List<JsonNode> projectCalls = new CopyOnWriteArrayList<>();
    private JavaLanguageServer server;
    private Path held;
    private Path other;

    private void start() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        held = helper.getFixturePath("simple-maven");
        other = helper.getTempDirectory().resolve("another-project");
        ToolCalls recording = new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                assertEquals("project", name, "the only tool the folders need");
                assertEquals(CallerIdentity.Origin.EDITOR, caller.origin());
                projectCalls.add(arguments);
                return ToolResponse.success(Map.of("ok", true));
            }

            @Override
            public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
        server = new JavaLanguageServer(() -> service, recording, CallerIdentity.editor("folders-test"), sent::add, SCHEDULER, 0);
    }

    private void initialize(boolean takesProgress, Path... folders) throws Exception {
        List<Map<String, Object>> named = new ArrayList<>();
        for (Path folder : folders) {
            named.add(Map.of("uri", folder.toUri().toString(), "name", folder.getFileName().toString()));
        }
        Map<String, Object> capabilities = takesProgress ? Map.of("window", Map.of("workDoneProgress", true)) : Map.of();
        server.receive(JsonRpcMessage.request(1, "initialize", JSON.valueToTree(Map.of("capabilities", capabilities, "workspaceFolders", named))));
        server.receive(JsonRpcMessage.notification("initialized", JSON.createObjectNode()));
    }

    private List<String> progressKinds() {
        List<String> kinds = new ArrayList<>();
        for (JsonRpcMessage message : sent) {
            if ("window/workDoneProgress/create".equals(message.getMethod())) {
                kinds.add("create");
            } else if ("$/progress".equals(message.getMethod())) {
                kinds.add(message.getParams().path("value").path("kind").asText());
            }
        }
        return kinds;
    }

    @Test
    @DisplayName("a folder the engine already holds a project for is left alone, and any other folder the editor opens is added as a project")
    void openFoldersReachTheEngine() throws Exception {
        start();

        initialize(false, held, other);

        assertEquals(1, projectCalls.size(), "only the folder the engine does not hold: " + projectCalls);
        assertEquals("add", projectCalls.get(0).path("action").asText());
        assertEquals(other.toString(), projectCalls.get(0).path("projectPath").asText());
        assertEquals(List.of(), progressKinds(), "an editor that did not say it takes progress is shown none");
    }

    @Test
    @DisplayName("a folder added to the workspace later is taken the same way, and a folder removed from it changes nothing in the engine")
    void foldersAddedLaterAreTakenAndRemovedOnesAreNot() throws Exception {
        start();
        initialize(false, held);
        assertEquals(0, projectCalls.size());

        server.receive(JsonRpcMessage.notification("workspace/didChangeWorkspaceFolders", JSON.valueToTree(Map.of("event", Map.of(
            "added", List.of(Map.of("uri", other.toUri().toString(), "name", "another-project")),
            "removed", List.of(Map.of("uri", held.toUri().toString(), "name", "simple-maven")))))));

        assertEquals(1, projectCalls.size(), "the added folder only: " + projectCalls);
        assertEquals(other.toString(), projectCalls.get(0).path("projectPath").asText());
    }

    @Test
    @DisplayName("an editor that takes progress is shown the loading of a folder: a created report, its beginning and its end, in that order, under one token")
    void loadingIsShownAsProgress() throws Exception {
        start();

        initialize(true, other);

        assertEquals(List.of("create", "begin", "end"), progressKinds());
        String token = sent.stream().filter(m -> "window/workDoneProgress/create".equals(m.getMethod())).findFirst().orElseThrow()
            .getParams().path("token").asText();
        assertTrue(sent.stream().filter(m -> "$/progress".equals(m.getMethod()))
            .allMatch(m -> token.equals(m.getParams().path("token").asText())), "one token for the report");
    }
}
