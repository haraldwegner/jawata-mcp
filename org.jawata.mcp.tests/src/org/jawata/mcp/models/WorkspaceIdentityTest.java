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

    // ---- mcp#27 stage 1: the hint that ASKS the siblings rather than naming them ----

    private static SiblingPeek.Sweep held(String workspace, String project) {
        return new SiblingPeek.Sweep(
            java.util.Optional.of(new SiblingPeek.Found(workspace, project)), 2, 2, false);
    }

    private void twoSiblings() {
        WorkspaceIdentity.install("javata-dev", List.of(Path.of("/tmp/jawata-mcp")));
        WorkspaceIdentity.installSiblings(() -> List.of(
            new SiblingRegistry.Sibling("orb-strategy", 8082, "t2"),
            new SiblingRegistry.Sibling("patterns", 8083, "t3")));
    }

    @Test
    @DisplayName("mcp#27: a symbol we do not have is ANSWERED — the workspace and the project")
    void theHintNamesWhoHasIt() {
        twoSiblings();
        WorkspaceIdentity.installPeek(fqn -> held("orb-strategy", "com-jats2-model"));

        String hint = WorkspaceIdentity.elsewhereHint("com.jats2.model.Order");

        assertAll(
            () -> assertTrue(hint.contains("orb-strategy"), "got: " + hint),
            () -> assertTrue(hint.contains("com-jats2-model"), "got: " + hint),
            // The naming-only tail is REPLACED, not appended to: having asked them, listing who
            // is up and then saying what they said is two answers to one question.
            () -> assertFalse(hint.contains("Running here:"), "got: " + hint),
            // ...and the sentence that says why this server cannot see it survives, because the
            // reader still needs to know which server answered.
            () -> assertTrue(hint.contains("own jawata server"), "got: " + hint));
    }

    @Test
    @DisplayName("mcp#27 THE CONTROL - nobody holding it still says how many were asked")
    void theHintSaysWhatItExamined() {
        twoSiblings();
        WorkspaceIdentity.installPeek(fqn ->
            new SiblingPeek.Sweep(java.util.Optional.empty(), 2, 2, false));

        String hint = WorkspaceIdentity.elsewhereHint("com.jats2.model.Order");

        // Without this, an implementation that answered "no sibling has it" after asking NONE
        // of them would pass the test above and be indistinguishable from one that asked.
        assertTrue(hint.contains("2 of 2"), "the answer must say what it examined: " + hint);
    }

    @Test
    @DisplayName("mcp#27: a peek that THROWS leaves the naming hint standing")
    void aBrokenPeekNeverBreaksTheMiss() {
        twoSiblings();
        WorkspaceIdentity.installPeek(fqn -> { throw new IllegalStateException("boom"); });

        String hint = assertDoesNotThrow(
            () -> WorkspaceIdentity.elsewhereHint("com.jats2.model.Order"));

        // The caller is already reporting a miss; enriching it can only add, never fail.
        assertTrue(hint.contains("orb-strategy"), "falls back to naming them: " + hint);
    }

    @Test
    @DisplayName("mcp#27: with no peek installed the hint is exactly the naming hint")
    void noPeekIsTheOldBehaviour() {
        twoSiblings();

        assertEquals(WorkspaceIdentity.elsewhereHint(),
            WorkspaceIdentity.elsewhereHint("com.jats2.model.Order"),
            "a resident with no peek wired must answer as it did before");
    }

    @Test
    @DisplayName("mcp#27: only an askable TYPE is asked about")
    void whatCanBeAsked() {
        assertAll(
            // A sibling is asked inspect(kind=source, typeName=...), which resolves a type - so
            // a member form is a question about its type.
            () -> assertEquals("com.foo.Bar",
                WorkspaceIdentity.askableTypeName("com.foo.Bar#run")),
            () -> assertEquals("com.foo.Bar",
                WorkspaceIdentity.askableTypeName("com.foo.Bar#run(int,java.lang.String)")),
            () -> assertEquals("com.foo.Bar", WorkspaceIdentity.askableTypeName("com.foo.Bar")),
            // A name with no package resolves nowhere, so asking would spend the whole budget
            // to learn nothing.
            () -> assertNull(WorkspaceIdentity.askableTypeName("Bar")),
            () -> assertNull(WorkspaceIdentity.askableTypeName("#run")),
            () -> assertNull(WorkspaceIdentity.askableTypeName("com.foo.")),
            () -> assertNull(WorkspaceIdentity.askableTypeName("   ")),
            () -> assertNull(WorkspaceIdentity.askableTypeName(null)));
    }

    @Test
    @DisplayName("mcp#27 THE WIRING - the SYMBOL_NOT_FOUND response itself carries the answer")
    void theMissPathItselfAsksTheSiblings() {
        // Every test above drives elsewhereHint(String) directly, which proves the OVERLOAD
        // works and says nothing about whether anything calls it. That gap is this project's
        // recorded three-time failure: a capability built, tested, and reached by nothing in
        // the live process. This one enters through the miss a client actually receives.
        twoSiblings();
        WorkspaceIdentity.installPeek(fqn -> held("orb-strategy", "com-jats2-model"));

        ToolResponse response = ToolResponse.symbolNotFound("com.jats2.model.Order");

        assertFalse(response.isSuccess(), "still a miss: " + response.getError());
        String hint = response.getError().getHint();
        assertAll(
            () -> assertTrue(hint.contains("orb-strategy"), "got: " + hint),
            () -> assertTrue(hint.contains("com-jats2-model"), "got: " + hint),
            // The original not-found guidance is not thrown away to make room for it.
            () -> assertTrue(hint.contains("own jawata server"), "got: " + hint));
    }

    @Test
    @DisplayName("mcp#27: a bare simple name is NOT asked - no sibling is contacted at all")
    void aSimpleNameIsNotAsked() {
        twoSiblings();
        java.util.concurrent.atomic.AtomicInteger asked =
            new java.util.concurrent.atomic.AtomicInteger();
        WorkspaceIdentity.installPeek(fqn -> {
            asked.incrementAndGet();
            return held("orb-strategy", "p");
        });

        String hint = WorkspaceIdentity.elsewhereHint("Bar");

        assertAll(
            () -> assertEquals(0, asked.get(), "an unaskable name must not start a walk"),
            () -> assertTrue(hint.contains("Running here:"), "it still names them: " + hint));
    }
}
