package org.goja.mcp.knowledge;

import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21e Stage 2 — the anchor LIFECYCLE beyond ingest: backfill (memory loads
 * before projects), refresh provenance (auto-anchors are CLEARED, asserted anchors keep
 * supersede semantics), and the recall {@code #}-enclosure normalization (cue and
 * anchor match at type level regardless of member notation).
 */
class AnchorBackfillAndRefreshTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static void writeMemory(Path dir, String file, String frontmatter, String body)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(file), frontmatter + body);
    }

    private static StoredEntry bySummary(ExperienceStore store, String summary) {
        return store.all().stream()
            .filter(e -> summary.equals(e.summary()))
            .findFirst().orElseThrow(() -> new AssertionError("no entry with summary: " + summary));
    }

    @Test
    @DisplayName("backfill: memory loaded BEFORE the project gains anchors after project load; source_hash untouched")
    void backfill_anchors_memory_loaded_before_projects(@TempDir Path dir) throws Exception {
        AtomicReference<JdtServiceImpl> serviceRef = new AtomicReference<>();
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceMaintenance maint =
                new ExperienceMaintenance(store, fqn -> null, List::of, serviceRef::get);
            writeMemory(dir, "lesson.md", """
                ---
                description: pre-project greeting lesson
                type: lesson
                ---
                """, "State release lives in `HelloWorld.printGreeting`.\n");

            // 1) load while NO project is loaded → no anchor possible
            maint.load(dir);
            assertNull(bySummary(store, "pre-project greeting lesson").symbolFqn());

            // 2) project loads → backfill resolves the frozen text
            serviceRef.set(helper.loadProjectCopy("simple-maven"));
            Map<String, Object> report = maint.backfillAutoAnchors();
            assertEquals(1, report.get("anchored"), "backfill report: " + report);
            assertEquals("com.example.HelloWorld#printGreeting",
                bySummary(store, "pre-project greeting lesson").symbolFqn());

            // 3) the column-only write left source_hash alone: next load byte-strict skips
            Map<String, Object> again = maint.load(dir);
            assertEquals(0, again.get("loaded"), "backfill must not defeat skip-unchanged");
            assertEquals(1, again.get("unchanged"));
            assertEquals("com.example.HelloWorld#printGreeting",
                bySummary(store, "pre-project greeting lesson").symbolFqn(),
                "skip-unchanged load must not drop the backfilled anchor");

            // 4) idempotent: a second backfill has nothing left to do
            Map<String, Object> second = maint.backfillAutoAnchors();
            assertEquals(0, second.get("anchored"));
        }
    }

    @Test
    @DisplayName("refresh: unresolvable AUTO anchor is CLEARED — status untouched, never superseded")
    void refresh_clears_unresolvable_auto_anchor_not_supersedes() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String id = store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "auto-anchored survivor", Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED)
                .build());
            assertTrue(store.updateSymbolAnchor(id, "com.gone.Removed#method"));

            Map<String, Object> report =
                new ExperienceMaintenance(store, fqn -> Boolean.FALSE).refresh();

            StoredEntry e = bySummary(store, "auto-anchored survivor");
            assertNull(e.symbolFqn(), "auto-anchor cleared");
            assertEquals(ExperienceEntry.ACCEPTED, e.status(), "the lesson outlives its pointer");
            assertEquals(1, ((List<?>) report.get("cleared")).size());
            assertTrue(((List<?>) report.get("staled")).isEmpty(), "nothing superseded");
        }
    }

    @Test
    @DisplayName("refresh: unresolvable agent-recorded record(symbol=…) anchor still SUPERSEDES (asserted semantics)")
    void refresh_supersedes_unresolvable_agent_recorded_anchor() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "asserted by the agent", Confidence.MEDIUM)
                        .symbol("com.gone.Removed").build())
                .status(ExperienceEntry.ACCEPTED)
                .build());

            Map<String, Object> report =
                new ExperienceMaintenance(store, fqn -> Boolean.FALSE).refresh();

            assertEquals(ExperienceEntry.SUPERSEDED,
                bySummary(store, "asserted by the agent").status());
            assertEquals(1, ((List<?>) report.get("staled")).size());
            assertFalse(report.containsKey("cleared"), "no auto-anchors were involved");
        }
    }

    @Test
    @DisplayName("enclosure combos: cue and anchor match at TYPE level regardless of member notation")
    void enclosure_combos_match_at_type_level() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);

            String typeAnchored = store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "type level anchor", Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED).build());
            assertTrue(store.updateSymbolAnchor(typeAnchored, "pipeline.SlotManager"));

            String memberAnchored = store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "member level anchor", Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED).build());
            assertTrue(store.updateSymbolAnchor(memberAnchored, "execution.Slot#activeOrder"));

            // type↔type
            assertMatch(retrieval, "pipeline.SlotManager", "type level anchor");
            // member cue → type anchor
            assertMatch(retrieval, "pipeline.SlotManager#freeSlot", "type level anchor");
            // type cue → member anchor
            assertMatch(retrieval, "execution.Slot", "member level anchor");
            // member ↔ member (equality)
            assertMatch(retrieval, "execution.Slot#activeOrder", "member level anchor");
        }
    }

    @Test
    @DisplayName("member-affinity tiebreak: a #member cue ranks the entry that KNOWS the member first")
    void member_cue_ranks_member_aware_entry_above_type_bystanders() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);

            // Both anchored TYPE-level to the same busy type — equal specificity.
            String bystander = store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "unrelated lesson on the same type", Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED).build());
            assertTrue(store.updateSymbolAnchor(bystander, "pipeline.SlotManager"));

            String knowsMember = store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "the freeSlot release path misses state", Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED)
                .addSymptom("SlotManager.freeSlot(4)")
                .build());
            assertTrue(store.updateSymbolAnchor(knowsMember, "pipeline.SlotManager"));

            Map<String, Object> r = retrieval.recall(
                new RecallQuery("pipeline.SlotManager#freeSlot", null, null, null, null));
            assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) r.get("entries");
            assertEquals("the freeSlot release path misses state", entries.get(0).get("summary"),
                "the member-aware entry outranks the type-level bystander");
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertMatch(ExperienceRetrieval retrieval, String symbolCue, String expectedSummary) {
        Map<String, Object> r = retrieval.recall(new RecallQuery(symbolCue, null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"),
            "cue '" + symbolCue + "' must match: " + r);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) r.get("entries");
        assertTrue(entries.stream().anyMatch(e -> expectedSummary.equals(e.get("summary"))),
            "cue '" + symbolCue + "' must return '" + expectedSummary + "': " + entries);
    }

    @Test
    @DisplayName("dispatch fires the projects-mutated hook on load_project + project(add|remove) only")
    void registry_fires_projects_mutated_hook_on_mutations_only() throws Exception {
        org.goja.mcp.tools.ToolRegistry registry = new org.goja.mcp.tools.ToolRegistry();
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        registry.setProjectsMutatedHook(fired::incrementAndGet);
        registry.register(stub("project"));
        registry.register(stub("load_project"));

        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            registry.register(new org.goja.mcp.tools.ExperienceTool(() -> service, store));

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode stats = mapper.createObjectNode();
            stats.put("kind", "stats");
            assertTrue(registry.callTool("experience", stats).isSuccess());
            assertEquals(0, fired.get(), "a non-mutating call never fires the hook");

            com.fasterxml.jackson.databind.node.ObjectNode add = mapper.createObjectNode();
            add.put("action", "add");
            assertTrue(registry.callTool("project", add).isSuccess());
            assertEquals(1, fired.get(), "project(add) fires the hook");

            com.fasterxml.jackson.databind.node.ObjectNode list = mapper.createObjectNode();
            list.put("action", "list");
            assertTrue(registry.callTool("project", list).isSuccess());
            assertEquals(1, fired.get(), "project(list) does NOT fire the hook");

            com.fasterxml.jackson.databind.node.ObjectNode remove = mapper.createObjectNode();
            remove.put("action", "remove");
            assertTrue(registry.callTool("project", remove).isSuccess());
            assertEquals(2, fired.get(), "project(remove) fires the hook");

            assertTrue(registry.callTool("load_project", mapper.createObjectNode()).isSuccess());
            assertEquals(3, fired.get(), "load_project fires the hook");
        }
    }

    private static org.goja.mcp.tools.Tool stub(String name) {
        return new org.goja.mcp.tools.Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "stub";
            }

            @Override
            public Map<String, Object> getInputSchema() {
                return Map.of();
            }

            @Override
            public org.goja.mcp.models.ToolResponse execute(
                    com.fasterxml.jackson.databind.JsonNode arguments) {
                return org.goja.mcp.models.ToolResponse.success(Map.of("ok", true));
            }
        };
    }
}
