package org.jawata.mcp.knowledge;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21e Stage 1 — the resolution gate: ingest-time auto-anchoring anchors ONLY a
 * token that resolves uniquely to a PROJECT-SOURCE type. Dead names, binary/library
 * types, ambiguous simple names and dominance ties never anchor — absence beats a
 * wrong pointer. Auto-anchors are COLUMN-ONLY: body_json carries no {@code symbol} key
 * (the asserted-provenance marker refresh() distinguishes on).
 */
class SymbolAnchorResolutionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static void writeMemory(Path dir, String file, String frontmatter, String body)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(file), frontmatter + body);
    }

    private static ExperienceMaintenance maint(ExperienceStore store, JdtServiceImpl service) {
        return new ExperienceMaintenance(store, fqn -> null, List::of, () -> service);
    }

    private static StoredEntry bySummary(ExperienceStore store, String summary) {
        return store.all().stream()
            .filter(e -> summary.equals(e.summary()))
            .findFirst().orElseThrow(() -> new AssertionError("no entry with summary: " + summary));
    }

    @Test
    @DisplayName("unique source type + agreed existing member → anchored, column-only (no body_json symbol key)")
    void auto_anchor_resolves_unique_source_type_and_member(@TempDir Path dir) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            writeMemory(dir, "lesson.md", """
                ---
                name: greeting-release-lesson
                description: the greeting release path misses state
                type: reference
                ---
                """, "The bug lives in `HelloWorld.printGreeting` — the greeting release path.\n");
            Map<String, Object> report = maint(store, service).load(dir);

            StoredEntry e = bySummary(store, "the greeting release path misses state");
            assertEquals("com.example.HelloWorld#printGreeting", e.symbolFqn());
            assertEquals(1, report.get("anchored"));
            // provenance invariant: the AUTO anchor never enters the frozen fact map
            assertFalse(e.body().containsKey("symbol"),
                "auto-anchored body_json must carry no symbol key: " + e.body());
        }
    }

    @Test
    @DisplayName("dead symbol → no anchor")
    void dead_symbol_never_anchors(@TempDir Path dir) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            writeMemory(dir, "lesson.md", """
                ---
                description: a lesson anchored to a dead symbol
                type: reference
                ---
                """, "The old `DoesNotExistAnywhere.frob` path was removed long ago.\n");
            maint(store, service).load(dir);
            assertNull(bySummary(store, "a lesson anchored to a dead symbol").symbolFqn());
        }
    }

    @Test
    @DisplayName("binary/library types (qualified and simple) → no anchor")
    void library_type_never_anchors(@TempDir Path dir) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            writeMemory(dir, "lesson.md", """
                ---
                description: a lesson anchored to a library type
                type: reference
                ---
                """, "Prefer `java.util.List` over arrays; `String` interning surprises.\n");
            maint(store, service).load(dir);
            assertNull(bySummary(store, "a lesson anchored to a library type").symbolFqn());
        }
    }

    @Test
    @DisplayName("ambiguous simple name (2+ source types) → no anchor")
    void ambiguous_simple_name_never_anchors(@TempDir Path dir) throws Exception {
        Path copy = helper.copyFixture("simple-maven");
        Files.createDirectories(copy.resolve("src/main/java/com/example/dup"));
        Files.writeString(copy.resolve("src/main/java/com/example/dup/HelloWorld.java"),
            "package com.example.dup;\n\npublic class HelloWorld {\n}\n");
        // loadProjectCopy re-copies over the same destination (REPLACE_EXISTING keeps
        // the injected duplicate) and loads the modified project.
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            writeMemory(dir, "lesson.md", """
                ---
                description: a lesson anchored to an ambiguous type
                type: reference
                ---
                """, "The `HelloWorld` type is mentioned, twice even: `HelloWorld`.\n");
            maint(store, service).load(dir);
            assertNull(bySummary(store, "a lesson anchored to an ambiguous type").symbolFqn());
        }
    }

    @Test
    @DisplayName("dominance tie between two live types → no anchor")
    void tie_between_two_live_types_never_anchors(@TempDir Path dir) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            writeMemory(dir, "lesson.md", """
                ---
                description: a lesson whose anchor ties two types
                type: reference
                ---
                """, "`HelloWorld` and `NamingTargets` appear exactly once each.\n");
            maint(store, service).load(dir);
            assertNull(bySummary(store, "a lesson whose anchor ties two types").symbolFqn());
        }
    }

    @Test
    @DisplayName("nonexistent member on the dominant type → type-level anchor")
    void nonexistent_member_keeps_type_level_anchor(@TempDir Path dir) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            writeMemory(dir, "lesson.md", """
                ---
                description: a lesson that merely mentions a filename
                type: reference
                ---
                """, "See `HelloWorld.java` for the whole story.\n");
            maint(store, service).load(dir);
            assertEquals("com.example.HelloWorld",
                bySummary(store, "a lesson that merely mentions a filename").symbolFqn());
        }
    }

    @Test
    @DisplayName("frontmatter symbol: wins — auto-resolution not consulted, body_json keeps the key")
    void frontmatter_symbol_wins_over_auto_resolution(@TempDir Path dir) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            writeMemory(dir, "lesson.md", """
                ---
                description: a lesson whose anchor the author asserted
                type: reference
                symbol: com.example.NamingTargets
                ---
                """, "Text dominated by `HelloWorld.printGreeting` and `HelloWorld` mentions.\n");
            maint(store, service).load(dir);
            StoredEntry e = bySummary(store, "a lesson whose anchor the author asserted");
            assertEquals("com.example.NamingTargets", e.symbolFqn());
            assertTrue(e.body().containsKey("symbol"),
                "asserted anchor stays in the fact map: " + e.body());
        }
    }

    @Test
    @DisplayName("section entries anchor from their OWN text (the ORB book-flatten gap)")
    void section_entries_anchor_from_their_own_text(@TempDir Path dir) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            writeMemory(dir, "lesson.md", """
                ---
                description: a lesson split across two sections
                type: reference
                ---
                """, """
                Preamble without any code tokens at all.

                ## Process notes

                Nothing code-shaped here either.

                ## The worked example anchors from its own text

                The remainder lived in `HelloWorld.printGreeting`, missed by `HelloWorld` cleanup.
                """);
            maint(store, service).load(dir);
            assertEquals("com.example.HelloWorld#printGreeting",
                bySummary(store, "The worked example anchors from its own text").symbolFqn());
            assertNull(bySummary(store, "Process notes").symbolFqn());
            assertNull(bySummary(store, "a lesson split across two sections").symbolFqn());
        }
    }

    @Test
    @DisplayName("no JDT service → anchor stays NULL, load succeeds (backfill's case)")
    void no_service_leaves_anchor_null(@TempDir Path dir) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            writeMemory(dir, "lesson.md", """
                ---
                description: a lesson written before the project loaded
                type: reference
                ---
                """, "Mentions `HelloWorld.printGreeting` before any project is loaded.\n");
            new ExperienceMaintenance(store, fqn -> null, List::of, () -> null).load(dir);
            assertNull(bySummary(store, "a lesson written before the project loaded").symbolFqn());
        }
    }
}
