package org.goja.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21 Stage 4 — initial_load ingest + refresh/wipe maintenance. */
class ExperienceMaintenanceTest {

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ExperienceMaintenance maint(ExperienceMaintenance.PointerResolver r) {
        return new ExperienceMaintenance(store, r);
    }

    private void writeMemory(Path dir, String file, String frontmatter, String body) throws IOException {
        Files.writeString(dir.resolve(file), "---\n" + frontmatter + "\n---\n" + body);
    }

    @Test
    void load_ingests_memory_files_with_frontmatter_and_links(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md",
            "name: guard-lifecycle\ndescription: guard the workbench lifecycle\nmetadata:\n  type: feedback",
            "Body mentions [[recall-gap]] and [[another-note]].");
        writeMemory(dir, "b.md",
            "name: billing-dto\ndescription: billing DTOs keep no-arg ctors\ntype: domain_fact",
            "Legacy XML depends on them.");

        Map<String, Object> report = maint(fqn -> null).load(dir);
        assertEquals(2, report.get("loaded"));
        assertEquals(2L, store.count());

        List<StoredEntry> feedback = store.query(new RecallQuery(null, null, null, "guard the workbench", null));
        assertFalse(feedback.isEmpty(), "loaded entry is queryable by its summary");
        assertEquals(ExperienceEntry.ACCEPTED, feedback.get(0).status());
        assertEquals("feedback", feedback.get(0).type());

        // [[wikilinks]] became related edges in the stored document.
        Map<String, Object> doc = store.get(feedback.get(0).id()).orElseThrow();
        assertTrue(doc.get("links") instanceof List<?>);
        assertEquals(2, ((List<?>) doc.get("links")).size());
    }

    @Test
    void load_is_idempotent_per_source(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: n\ndescription: d\ntype: lesson", "body");
        maint(fqn -> null).load(dir);
        maint(fqn -> null).load(dir);   // re-load same source
        assertEquals(1L, store.count(), "re-load replaces, not duplicates");
    }

    @Test
    void load_flags_stale_symbol_on_ingest(@TempDir Path dir) throws IOException {
        writeMemory(dir, "s.md",
            "name: n\ndescription: d\ntype: lesson\nsymbol: com.gone.Removed", "body");
        Map<String, Object> report = maint(fqn -> Boolean.FALSE).load(dir);
        assertEquals(1, ((List<?>) report.get("stale")).size(), "unresolvable pointer flagged at ingest");
    }

    @Test
    void refresh_flags_stale_pointer_as_superseded() {
        String id = store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "note", Confidence.HIGH).symbol("com.gone.Removed").build()).build());

        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();
        assertEquals(1, report.get("checked"));
        assertEquals(1, ((List<?>) report.get("staled")).size());

        // The stale entry is superseded and no longer surfaces in recall.
        StoredEntry after = store.all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.SUPERSEDED, after.status());
        assertTrue(store.query(new RecallQuery("com.gone.Removed", null, null, null, null)).isEmpty());
    }

    @Test
    void refresh_with_no_project_skips_without_flagging() {
        store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "note", Confidence.HIGH).symbol("com.a.Foo").build()).build());
        Map<String, Object> report = maint(fqn -> null).refresh();
        assertEquals(1, report.get("checked"));
        assertEquals(1, report.get("skipped"));
        assertEquals(0, ((List<?>) report.get("staled")).size());
    }

    // --- Sprint 21a (item C): default roots + link-graph traversal ------------------------

    @Test
    void load_follows_the_link_graph_from_an_index_file(@TempDir Path dir) throws IOException {
        // MEMORY.md-style index: a wikilink + a relative markdown link into a subdir; the
        // linked files are reachable ONLY via links (the index is the single root).
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("MEMORY.md"),
            "- [Fact A](fact-a.md) — hook\n- see also [[fact-b]]\n");
        writeMemory(dir, "fact-a.md", "name: fact-a\ndescription: fact a\ntype: lesson",
            "a links onward to [b2](sub/fact-c.md)");
        writeMemory(dir, "fact-b.md", "name: fact-b\ndescription: fact b\ntype: lesson", "leaf");
        Files.writeString(dir.resolve("sub").resolve("fact-c.md"),
            "---\nname: fact-c\ndescription: fact c\ntype: lesson\n---\nleaf");

        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir.resolve("MEMORY.md")), false, 5, 200, 2_000_000L);
        assertEquals(4, report.get("loaded"), "index + wikilink + mdlink + transitive mdlink");
        assertEquals(4L, store.count());
        assertTrue(((List<?>) report.get("skipped")).isEmpty());
    }

    @Test
    void load_link_cycle_terminates(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: a\ndescription: a\ntype: lesson", "see [[b]]");
        writeMemory(dir, "b.md", "name: b\ndescription: b\ntype: lesson", "see [[a]]");
        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir.resolve("a.md")), false, 5, 200, 2_000_000L);
        assertEquals(2, report.get("loaded"), "a↔b crawled once each");
    }

    @Test
    void load_caps_are_honest_not_silent(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: a\ndescription: a\ntype: lesson", "x");
        writeMemory(dir, "b.md", "name: b\ndescription: b\ntype: lesson", "x");
        writeMemory(dir, "c.md", "name: c\ndescription: c\ntype: lesson", "x");
        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir), false, 5, 2, 2_000_000L);
        assertEquals(2, report.get("loaded"), "max-files cap");
        assertFalse(((List<?>) report.get("skipped")).isEmpty(), "the drop is reported, not silent");
    }

    @Test
    void load_recursive_walks_subdirectories(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("deep.md"),
            "---\nname: deep\ndescription: deep note\ntype: lesson\n---\nbody");
        assertEquals(0, maint(fqn -> null).load(dir, false).get("loaded"),
            "top-level listing does not see nested files");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"),
            "recursive mode walks subdirectories");
    }

    @Test
    void default_caps_are_runaway_backstops_not_tuning_values(@TempDir Path dir) throws IOException {
        // Sprint 21b (item C): "I want everything you can find" — a memory tree LARGER than
        // the old tuning caps (depth 5 / 200 files) must ingest COMPLETELY with the defaults.
        for (int i = 0; i < 210; i++) {
            writeMemory(dir, "n" + i + ".md", "name: n" + i + "\ndescription: note " + i + "\ntype: lesson", "x");
        }
        Path deep = dir;
        for (int i = 0; i < 7; i++) {
            deep = deep.resolve("d" + i);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("deepest.md"),
            "---\nname: deepest\ndescription: seven levels down\ntype: lesson\n---\nbody");

        Map<String, Object> report = maint(fqn -> null).load(dir, true);
        assertEquals(211, report.get("loaded"), "210 flat + 1 at depth 7 — nothing dropped");
        assertTrue(((List<?>) report.get("skipped")).isEmpty(), "no backstop fired");
    }

    @Test
    void load_without_path_uses_default_roots(@TempDir Path dir) throws IOException {
        writeMemory(dir, "seed.md", "name: s\ndescription: seeded\ntype: domain_fact", "x");
        ExperienceMaintenance withRoots =
            new ExperienceMaintenance(store, fqn -> null, () -> List.of(dir));
        assertTrue(withRoots.hasDefaultRoots());
        assertEquals(1, withRoots.load(null, false).get("loaded"));

        ExperienceMaintenance noRoots = maint(fqn -> null);
        assertFalse(noRoots.hasDefaultRoots());
        assertTrue(noRoots.load(null, false).containsKey("error"),
            "no path + no roots = explicit error");
    }

    // --- Sprint 21a (item I): non-Java anchors are opaque to JDT maintenance --------------

    @Test
    void refresh_never_supersedes_a_non_java_anchor() {
        String rustId = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "peel stops at closing quote", Confidence.HIGH)
                    .symbol("manager_service::build_recall_script").build())
            .language("rust").status(ExperienceEntry.ACCEPTED).build());
        String javaId = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "gone type", Confidence.HIGH)
                    .symbol("com.gone.Removed").build())
            .status(ExperienceEntry.ACCEPTED).build());

        // A resolver that resolves NOTHING — the destructive case for foreign anchors.
        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();
        assertEquals(1, report.get("checked"), "only the Java anchor is judged");
        assertEquals(1, report.get("non_java"));
        assertEquals(1, ((List<?>) report.get("staled")).size());

        StoredEntry rust = store.all().stream().filter(e -> e.id().equals(rustId)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.ACCEPTED, rust.status(), "Rust anchor survives refresh untouched");
        assertEquals("rust", rust.language());
        StoredEntry java = store.all().stream().filter(e -> e.id().equals(javaId)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.SUPERSEDED, java.status(), "genuinely stale Java pointer still superseded");
    }

    @Test
    void load_does_not_flag_non_java_symbols_stale(@TempDir Path dir) throws IOException {
        writeMemory(dir, "rust.md",
            "name: n\ndescription: d\ntype: lesson\nsymbol: gateway::forward\nlanguage: rust", "body");
        Map<String, Object> report = maint(fqn -> Boolean.FALSE).load(dir);
        assertEquals(1, report.get("loaded"));
        assertEquals(0, ((List<?>) report.get("stale")).size(),
            "a Rust anchor is not judged by the JDT resolver at ingest");
        assertEquals("rust", store.all().get(0).language(), "frontmatter language persisted");
    }

    @Test
    void wipe_clears_everything() {
        store.put(SymbolFact.of("lesson", "a", Confidence.LOW).symbol("com.a.Foo").build());
        store.put(SymbolFact.of("lesson", "b", Confidence.LOW).symbol("com.b.Bar").build());
        Map<String, Object> report = maint(fqn -> null).wipe();
        assertEquals(2L, report.get("removed"));
        assertEquals(0L, store.count());
    }
}
