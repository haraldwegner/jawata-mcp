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
    @DisplayName("mcp#27: when siblings are known, the hint NAMES them instead of gesturing")
    void theHintNamesTheOtherResidents() {
        WorkspaceIdentity.install("javata-dev", List.of(Path.of("/tmp/jawata-mcp")));
        WorkspaceIdentity.installSiblings(() -> List.of(
            new SiblingRegistry.Sibling("orb-strategy", 8082, "t2"),
            new SiblingRegistry.Sibling("patterns", 8083, "t3")));

        String hint = WorkspaceIdentity.elsewhereHint();

        assertAll(
            () -> assertTrue(hint.contains("orb-strategy"), "got: " + hint),
            () -> assertTrue(hint.contains("patterns"), "got: " + hint),
            // The original sentence survives — this ADDS an answer, it does not replace the
            // statement about how jawata is deployed.
            () -> assertTrue(hint.contains("own jawata server"), "got: " + hint));
    }

    @Test
    @DisplayName("mcp#27 THE CONTROL — no registry means we do not KNOW of siblings, not that there are none")
    void withoutARegistryTheHintIsUnchangedAndClaimsNothing() {
        // The distinction this codebase keeps having to make. A hand-launched resident has no
        // studio behind it and no registry, and its machine may still be full of siblings — so
        // the hint must not acquire a sentence asserting that none are running.
        WorkspaceIdentity.install("javata-dev", List.of(Path.of("/tmp/jawata-mcp")));

        String hint = WorkspaceIdentity.elsewhereHint();

        assertAll(
            () -> assertFalse(hint.contains("Running here"),
                "an absent registry must not become a claim about what is running: " + hint),
            () -> assertTrue(hint.contains("own jawata server"),
                "and the original hint is untouched: " + hint));
    }

    @Test
    @DisplayName("mcp#27: a supplier that throws is survivable — a hint must not fail a search")
    void aBrokenSupplierDoesNotBreakTheHint() {
        WorkspaceIdentity.install("javata-dev", List.of(Path.of("/tmp/jawata-mcp")));
        WorkspaceIdentity.installSiblings(() -> {
            throw new IllegalStateException("registry unreadable");
        });

        String hint = WorkspaceIdentity.elsewhereHint();

        assertNotNull(hint, "the hint still answers");
        assertFalse(hint.contains("Running here"),
            "and claims nothing it could not read: " + hint);
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
    @DisplayName("mcp#32: after a TERMINAL load failure the identity stops claiming the projects")
    void failedLoad_saysFailedInsteadOfNamingProjectsAsPresent() {
        // The boot list exists to cover the async-LOADING window. It kept
        // answering after a terminal failure, so this server introduced its
        // project as present while health_check reported projectCount: 0 — the
        // same server contradicting itself in two answers to one agent.
        WorkspaceIdentity.install("javata-dev", List.of(Path.of("/home/x/jawata-mcp")));
        WorkspaceIdentity.installLiveKeys(List::of);
        WorkspaceIdentity.installLoadFailure(() ->
            "all 1 workspace project(s) FAILED to load — first: jawata-mcp: Maven resolution failed");

        String hint = WorkspaceIdentity.elsewhereHint();
        assertNotNull(hint);
        assertTrue(hint.contains("FAILED to load"),
            "the failure is stated, not implied: " + hint);
        assertTrue(hint.contains("Maven resolution failed"),
            "and it carries the reason the agent must relay: " + hint);
        assertFalse(hint.contains("1 project(s): jawata-mcp"),
            "the configured project must NOT be presented as present: " + hint);
    }

    @Test
    @DisplayName("while the load is still running the boot list still answers")
    void stillLoading_keepsUsingTheBootList() {
        WorkspaceIdentity.install("javata-dev", List.of(Path.of("/home/x/jawata-mcp")));
        WorkspaceIdentity.installLiveKeys(List::of);
        WorkspaceIdentity.installLoadFailure(() -> null);   // no failure (yet)

        assertTrue(WorkspaceIdentity.describe().contains("1 project(s): jawata-mcp"),
            "the async-loading window is precisely why the boot list exists");
    }

    @Test
    @DisplayName("mcp#32: two hints joined read as two sentences")
    void notFoundHint_isNotARunOnLine() {
        WorkspaceIdentity.install("javata-dev", List.of(Path.of("/home/x/jawata-mcp")));

        String hint = ToolResponse.symbolNotFound("com.example.Foo").getError().getHint();
        assertNotNull(hint);
        assertFalse(hint.contains("symbols This is"),
            "the base hint and the workspace hint were concatenated with a bare space, so every "
                + "not-found answer read as one run-on line: " + hint);
        assertTrue(hint.contains(". This is") || hint.startsWith("This is"),
            "the join is a sentence break: " + hint);
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
