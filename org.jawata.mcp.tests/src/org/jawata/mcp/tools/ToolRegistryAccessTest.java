package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jawata.mcp.access.AccessCheck;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S13 (D20, E11): the access policy where every client's call passes. The claim is not only that a call is refused but that it
 * is refused BEFORE the tool runs, so each case counts how often the tool's body executed, and the same call is made by clients that
 * differ only in who they are (ACL8).
 */
class ToolRegistryAccessTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final AtomicInteger executed = new AtomicInteger();
    private Path root;
    private Path project;
    private Path policy;

    @BeforeEach
    void layOutAMachine() throws IOException {
        root = Files.createTempDirectory("jawata-registry-access-").toRealPath();
        project = Files.createDirectories(root.resolve("home").resolve("project"));
        policy = root.resolve("access-policy.json");
        Files.writeString(policy, "{\"allow\":[\"${workspace}\"],\"deny\":[]}");
    }

    @AfterEach
    void removeTheMachine() throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private ToolRegistry registry(Path policyFile) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String getName() {
                return "read_something";
            }
            @Override public String getDescription() {
                return "a counting stand-in for a tool that is handed a file";
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                executed.incrementAndGet();
                return ToolResponse.success(Map.of("read", true));
            }
        });
        if (policyFile != null) {
            registry.setAccessCheck(new AccessCheck(policyFile, session -> List.of(project), root.resolve("home"), root.resolve("scratch")));
        }
        return registry;
    }

    private JsonNode file(Path place) throws IOException {
        return OM.readTree("{\"file_path\":" + OM.writeValueAsString(place.toString()) + "}");
    }

    @Test
    @DisplayName("a call naming a place the policy does not allow is refused with a code a caller can branch on, and the tool never runs")
    void aForbiddenPlaceIsRefusedBeforeTheToolRuns() throws Exception {
        ToolRegistry registry = registry(policy);

        ToolResponse response = registry.callTool("read_something", file(root.resolve("home").resolve("Documents").resolve("notes.txt")),
            CallerIdentity.supplied("claude-session"));

        assertAll(
            () -> assertFalse(response.isSuccess()),
            () -> assertEquals("ACCESS_DENIED", response.getError().getCode()),
            () -> assertTrue(response.getError().getMessage().startsWith("Access policy: "), response.getError().getMessage()),
            () -> assertEquals(0, executed.get(), "THE ORDERING CLAUSE: the tool's body never ran"));
    }

    @Test
    @DisplayName("THE CONTROL: a call naming a place the policy allows runs the tool once")
    void anAllowedPlaceRuns() throws Exception {
        ToolResponse response = registry(policy).callTool("read_something", file(project.resolve("A.java")),
            CallerIdentity.supplied("claude-session"));

        assertAll(() -> assertTrue(response.isSuccess()), () -> assertEquals(1, executed.get()));
    }

    @Test
    @DisplayName("ACL8: the same place is refused to a Cursor session, a Claude Code session and an ACP session alike")
    void everyClientIsHeldToIt() throws Exception {
        ToolRegistry registry = registry(policy);
        JsonNode forbidden = file(root.resolve("home").resolve("Documents").resolve("notes.txt"));

        List<String> codes = new java.util.ArrayList<>();
        for (String client : List.of("cursor-session", "claude-code-session", "acp-session")) {
            codes.add(registry.callTool("read_something", forbidden, CallerIdentity.supplied(client)).getError().getCode());
        }

        assertEquals(List.of("ACCESS_DENIED", "ACCESS_DENIED", "ACCESS_DENIED"), codes);
        assertEquals(0, executed.get());
    }

    @Test
    @DisplayName("ACL5: a policy file that is not there refuses a call naming a place and says so, and a call naming none still runs")
    void aMissingPolicyRefusesWhatNamesAPlace() throws Exception {
        ToolRegistry registry = registry(root.resolve("nowhere.json"));

        ToolResponse naming = registry.callTool("read_something", file(project.resolve("A.java")), CallerIdentity.supplied("s"));
        ToolResponse namingNothing = registry.callTool("read_something", OM.readTree("{\"kind\":\"references\"}"), CallerIdentity.supplied("s"));

        assertAll(
            () -> assertFalse(naming.isSuccess()),
            () -> assertTrue(naming.getError().getMessage().contains("there is no access policy at"), naming.getError().getMessage()),
            () -> assertTrue(namingNothing.isSuccess(), "CONTROL: a call that names no place was refused too"),
            () -> assertEquals(1, executed.get()));
    }

    @Test
    @DisplayName("a registry with no access check installed (a host with no data folder) runs the call, so the check is the only thing that refuses")
    void withoutTheCheckNothingIsRefused() throws Exception {
        ToolResponse response = registry(null).callTool("read_something", file(root.resolve("home").resolve("Documents").resolve("notes.txt")),
            CallerIdentity.supplied("s"));

        assertTrue(response.isSuccess());
        assertEquals(1, executed.get());
    }
}
