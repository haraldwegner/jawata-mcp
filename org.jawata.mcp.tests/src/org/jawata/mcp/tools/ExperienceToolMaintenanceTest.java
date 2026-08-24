package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21 Stage 4 — load / refresh / wipe / promote through the experience front door. */
class ExperienceToolMaintenanceTest {

    private ObjectMapper mapper;
    private ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess());
        return (Map<String, Object>) r.getData();
    }

    private String recordOne() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", "guard the workbench lifecycle here");
        a.put("symbol", "com.example.WorkflowCoordinator");
        // Sprint 28c: a lesson owes a situation and an outcome. Supplied rather
        // than the gate relaxed — this class is about load/refresh/anchor
        // resolution, and none of its assertions read these fields.
        a.put("situation", "when a view is disposed while one of its jobs still runs");
        a.put("verdict", "worked");
        return (String) data(tool.execute(a)).get("id");
    }

    @Test
    void load_via_tool_seeds_from_directory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("m.md"),
            "---\nname: n\ndescription: a domain note about this package\ntype: domain_fact\n---\nbody [[x]]");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        Map<String, Object> d = data(tool.execute(a));
        assertEquals(1, d.get("loaded"));
        assertEquals(1L, store.count());
    }

    @Test
    void load_without_recursive_arg_walks_subdirectories(@TempDir Path dir) throws IOException {
        // Sprint 21b (item C): the crawler finds everything — recursive is the DEFAULT.
        Files.createDirectory(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("deep.md"),
            "---\nname: deep\ndescription: a nested note under a subdirectory\ntype: reference\n---\nbody");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        assertEquals(1, data(tool.execute(a)).get("loaded"), "no recursive arg → walks subdirs");
    }

    @Test
    void load_recursive_false_stays_flat(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("deep.md"),
            "---\nname: deep\ndescription: a nested note under a subdirectory\ntype: reference\n---\nbody");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        a.put("recursive", false);
        assertEquals(0, data(tool.execute(a)).get("loaded"), "explicit recursive:false honored");
    }

    @Test
    void load_without_path_and_without_roots_fails() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        assertFalse(tool.execute(a).isSuccess(), "no path + no configured default roots");
    }

    // --- Sprint 21b (item D): refresh is automatic after load / import --------------------

    @Test
    void load_auto_refreshes_preexisting_stale_entries(@TempDir Path dir) throws IOException {
        recordOne(); // symbol com.example.WorkflowCoordinator, currently unresolvable=stale
        Files.writeString(dir.resolve("m.md"),
            "---\nname: n\ndescription: an unrelated note about something else\ntype: domain_fact\n---\nbody");
        ExperienceTool staleWorld = new ExperienceTool(() -> null, store,
            java.util.List::of, fqn -> Boolean.FALSE);
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        Map<String, Object> d = data(staleWorld.execute(a));
        assertEquals(1, d.get("loaded"));
        Map<?, ?> refresh = (Map<?, ?>) d.get("refresh");
        // Sprint 28c: this test is about the auto-refresh FIRING — that a load
        // judges pre-existing anchors with no explicit refresh call. The fixture
        // is form-1 (a lesson owes a situation), so a dead anchor now marks its
        // evidence rather than superseding it: an anchor says where knowledge
        // was learned, a situation says when it applies, and the second outlives
        // the first. Asserting `staled` here would demand the old behaviour that
        // the staleness guard deliberately removed.
        assertTrue(((java.util.List<?>) refresh.get("evidence_dead")).size() == 1,
            "pre-existing dead pointer flagged by load itself — no explicit refresh"
                + " call: " + refresh);
        assertTrue(((java.util.List<?>) refresh.get("staled")).isEmpty(),
            "and a form-1 entry is marked, never retired, by a resolver: " + refresh);
    }

    @Test
    void import_auto_refreshes_the_ingested_entries(@TempDir Path dir) throws IOException {
        recordOne();
        ObjectNode ex = mapper.createObjectNode();
        ex.put("kind", "export");
        Object entries = data(tool.execute(ex)).get("entries");
        ObjectNode wipe = mapper.createObjectNode();
        wipe.put("kind", "wipe");
        data(tool.execute(wipe));

        ExperienceTool staleWorld = new ExperienceTool(() -> null, store,
            java.util.List::of, fqn -> Boolean.FALSE);
        ObjectNode im = mapper.createObjectNode();
        im.put("kind", "import");
        im.set("entries", mapper.valueToTree(entries));
        Map<String, Object> d = data(staleWorld.execute(im));
        Map<?, ?> refresh = (Map<?, ?>) d.get("refresh");
        // Same as above, and it proves one thing more: the form survived the
        // export/wipe/import round trip. If any of those three dropped the
        // facets, the imported row would read as legacy and be SUPERSEDED here
        // instead — so this assertion fails loudly on a lossy round trip.
        assertTrue(((java.util.List<?>) refresh.get("evidence_dead")).size() == 1,
            "imported dead pointer flagged by import itself: " + refresh);
        assertTrue(((java.util.List<?>) refresh.get("staled")).isEmpty(),
            "and the round trip kept the form — a legacy row would have been"
                + " superseded here: " + refresh);
    }

    @Test
    void wipe_compacts_the_store_afterwards() {
        // Sprint 21b: MVStore never shrinks on deletes — a wipe that leaves an 800k
        // file reads as a bug, so wipe compacts.
        recordOne();
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "wipe");
        Map<String, Object> d = data(tool.execute(a));
        assertEquals(1L, ((Number) d.get("removed")).longValue());
        assertTrue(d.containsKey("compact"), "wipe response carries the compact report");
    }

    @Test
    void autoRefresh_never_throws_even_on_a_broken_resolver() {
        recordOne();
        ExperienceTool broken = new ExperienceTool(() -> null, store,
            java.util.List::of, fqn -> { throw new IllegalStateException("boom"); });
        Map<String, Object> r = broken.autoRefresh();
        assertTrue(r.containsKey("error"), "the startup auto-refresh path must never throw");
    }

    // --- Sprint 21a (items C+G): default roots + the confirm-gated reseed -----------------

    @Test
    void load_without_path_seeds_from_default_roots(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("m.md"),
            "---\nname: n\ndescription: a seeded note placed before the run\ntype: domain_fact\n---\nbody");
        ExperienceTool rooted = new ExperienceTool(() -> null, store, () -> java.util.List.of(dir));
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        assertEquals(1, data(rooted.execute(a)).get("loaded"));
    }

    @Test
    void reseed_requires_confirm_then_wipes_and_reloads(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("m.md"),
            "---\nname: n\ndescription: a seeded note placed before the run\ntype: domain_fact\n---\nbody");
        ExperienceTool rooted = new ExperienceTool(() -> null, store, () -> java.util.List.of(dir));
        recordOneVia(rooted);
        assertEquals(1L, store.count());

        ObjectNode noConfirm = mapper.createObjectNode();
        noConfirm.put("kind", "reseed");
        assertFalse(rooted.execute(noConfirm).isSuccess(), "reseed is confirm-gated");
        assertEquals(1L, store.count(), "nothing wiped without confirm");

        ObjectNode confirmed = mapper.createObjectNode();
        confirmed.put("kind", "reseed");
        confirmed.put("confirm", true);
        Map<String, Object> d = data(rooted.execute(confirmed));
        assertEquals(1L, d.get("removed"), "the hand-recorded entry was wiped");
        assertEquals(1, d.get("loaded"), "the seed file was reloaded");
        assertEquals(1L, store.count());
    }

    private void recordOneVia(ExperienceTool t) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", "this note exists to be wiped");
        // Sprint 28c: a lesson owes a situation and an outcome, and this record
        // must LAND or the wipe below removes nothing and proves nothing.
        a.put("situation", "when a store is seeded by hand before a reseed");
        a.put("verdict", "worked");
        t.execute(a);
    }

    @Test
    void wipe_via_tool_clears() {
        recordOne();
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "wipe");
        assertEquals(1L, data(tool.execute(a)).get("removed"));
        assertEquals(0L, store.count());
    }

    @Test
    void promote_via_tool_sets_status() {
        String id = recordOne();
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "promote");
        a.put("id", id);
        Map<String, Object> d = data(tool.execute(a));
        assertEquals("accepted", d.get("status"));
        assertEquals(true, d.get("changed"));
    }

    @Test
    void refresh_via_tool_no_project_skips() {
        recordOne();
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "refresh");
        Map<String, Object> d = data(tool.execute(a));
        assertEquals(1, d.get("checked"));
        assertEquals(1, d.get("skipped"));
    }
}
