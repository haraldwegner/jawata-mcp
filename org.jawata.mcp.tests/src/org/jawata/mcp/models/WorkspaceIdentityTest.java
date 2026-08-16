package org.jawata.mcp.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.protocol.McpProtocolHandler;
import org.jawata.mcp.tools.SearchSymbolsTool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 28a (D11) — a wrong-workspace question answers itself. The defect these
 * tests pin: with several jawata servers connected (one per workspace), every
 * server introduced itself identically and a symbol living in the OTHER
 * workspace came back as a bare empty result / bare NOT_FOUND, which reads as
 * "does not exist" instead of "does not exist HERE".
 */
class WorkspaceIdentityTest {

    @AfterEach
    void tearDown() {
        WorkspaceIdentity.reset();
    }

    @Test
    @DisplayName("uninstalled identity keeps every surface exactly as before")
    void uninstalled_isSilent() {
        assertFalse(WorkspaceIdentity.installed());
        assertNull(WorkspaceIdentity.describe());
        assertNull(WorkspaceIdentity.elsewhereHint());
        assertNull(SearchSymbolsTool.emptyResultSteering("Foo"));

        ToolResponse notFound = ToolResponse.symbolNotFound("com.example.Foo");
        assertFalse(notFound.isSuccess());
        String hint = notFound.getError().getHint();
        if (hint != null) {
            assertFalse(hint.contains("workspace ("),
                "no workspace enrichment before install");
        }
    }

    @Test
    @DisplayName("describe names the workspace and its configured projects")
    void describe_namesWorkspaceAndProjects() {
        WorkspaceIdentity.install("javata-dev",
            List.of(Path.of("/home/x/jawata-mcp"), Path.of("/home/x/fixtures/javadoc-seat")));

        String described = WorkspaceIdentity.describe();
        assertNotNull(described);
        assertTrue(described.contains("'javata-dev'"), "workspace name is named");
        assertTrue(described.contains("jawata-mcp"), "project dir names are named");
        assertTrue(described.contains("javadoc-seat"));
        assertTrue(described.contains("2 project(s)"));
    }

    @Test
    @DisplayName("a symbol-not-found hint says WHERE it looked and where else to ask")
    void symbolNotFound_saysWhere() {
        WorkspaceIdentity.install("javata-dev", List.of(Path.of("/home/x/jawata-mcp")));

        ToolResponse notFound = ToolResponse.symbolNotFound("com.jats2.model.Order");
        String hint = notFound.getError().getHint();
        assertNotNull(hint);
        assertTrue(hint.contains("'javata-dev'"), "hint names THIS workspace");
        assertTrue(hint.contains("jawata-mcp"), "hint names its projects");
        assertTrue(hint.contains("own jawata server"),
            "hint redirects to the other workspace's server");
    }

    @Test
    @DisplayName("an empty search result steers instead of reading as nonexistence")
    void emptySearch_steers() {
        WorkspaceIdentity.install("javata-dev", List.of(Path.of("/home/x/jawata-mcp")));

        String steering = SearchSymbolsTool.emptyResultSteering("Order");
        assertNotNull(steering);
        assertTrue(steering.contains("'Order'"), "names the query");
        assertTrue(steering.contains("'javata-dev'"), "names this workspace");
    }

    @Test
    @DisplayName("initialize instructions carry the workspace roster once installed")
    void initializeInstructions_carryRoster() throws Exception {
        WorkspaceIdentity.install("orb", List.of(Path.of("/home/x/falcon")));

        McpProtocolHandler handler = new McpProtocolHandler(new ToolRegistry());
        String response = handler.processMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");

        JsonNode result = new ObjectMapper().readTree(response).get("result");
        String instructions = result.get("instructions").asText();
        assertTrue(instructions.contains("search_symbols"), "the Sprint-22 guide survives");
        assertTrue(instructions.contains("'orb'"), "the roster names the workspace");
        assertTrue(instructions.contains("falcon"), "the roster names its projects");
    }

    @Test
    @DisplayName("live loaded keys win over the boot list; an empty live list does not")
    void liveKeys_winWhenPresent() {
        WorkspaceIdentity.install("ws", List.of(Path.of("/home/x/from-boot")));

        WorkspaceIdentity.installLiveKeys(List::of);
        assertTrue(WorkspaceIdentity.describe().contains("from-boot"),
            "empty live list (still loading) leaves the boot list answering");

        WorkspaceIdentity.installLiveKeys(() -> List.of("live-project-a", "live-project-b"));
        String described = WorkspaceIdentity.describe();
        assertTrue(described.contains("live-project-a"), "live keys take over");
        assertFalse(described.contains("from-boot"));
    }

    @Test
    @DisplayName("a long roster is capped, and the cap says how many more exist")
    void longRoster_isCapped() {
        WorkspaceIdentity.install("big",
            java.util.stream.IntStream.range(0, 29)
                .mapToObj(i -> Path.of("/w/project-" + i)).toList());

        String described = WorkspaceIdentity.describe();
        assertTrue(described.contains("29 project(s)"));
        assertTrue(described.contains("and 17 more"), "12 shown, 17 declared as unshown");
    }

    @Test
    @DisplayName("a throwing live supplier must never break a response")
    void throwingLiveSupplier_isSwallowed() {
        WorkspaceIdentity.install("ws", List.of(Path.of("/home/x/p")));
        WorkspaceIdentity.installLiveKeys(() -> { throw new IllegalStateException("boom"); });

        assertDoesNotThrow(WorkspaceIdentity::describe);
        assertTrue(WorkspaceIdentity.describe().contains("p"), "falls back to the boot list");
    }
}
