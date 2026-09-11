package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f D1 — the load is exercised through the store EVERY PRODUCTION LOAD GOES THROUGH.
 *
 * <p><b>Why this class exists rather than one more case in a sibling.</b> In the resident
 * the store is WRAPPED: {@link RecoveringExperienceStore} stands in front of the H2 store
 * so a failed open degrades instead of dying. Every test of this stage constructs the H2
 * store directly, so all of them run past the wrapper — and the wrapper is where a
 * forwarding method can be missing.</p>
 *
 * <p><b>The hole this closes, in the words of the method that has it.</b>
 * {@code ExperienceStore#upsertBySource} is declared with a DEFAULT that falls back to an
 * insert, so the wrapper compiles perfectly without overriding it. Delete the five-line
 * forward in {@code RecoveringExperienceStore} and nothing in this repository went red:
 * every production re-ingest would insert instead of rewriting in place — the exact defect
 * D1 exists to end — while the store reported success and every direct-store test stayed
 * green.</p>
 *
 * <p>It is the same shape {@code WritePathDedupTest#the_production_wrapper_path_flags_too}
 * was written for one package over, on a different method, for the same reason: <em>"so the
 * hook can never silently stop working in production while every direct-store test stays
 * green."</em></p>
 */
class LoadThroughTheWrapperTest {

    /**
     * A story file the loader accepts.
     *
     * <p>The BODY is a parameter and the description is not, which is the whole design of
     * this test and was arrived at by being wrong twice. {@code upsertBySource} matches an
     * existing row by {@code (source_ref, summary)}, so editing the DESCRIPTION legitimately
     * matches nothing and legitimately mints a new id — a test built on that edit fails
     * against correct code. The rewrite-in-place this class is about is the other edit: the
     * same description, a changed body.</p>
     */
    private static void story(Path dir, String name, String body) throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: a story whose wording is settled"
                + "\ntype: domain_fact\n---\n\n" + body + "\n");
    }

    /** Every entry id the store holds, which is what an in-place rewrite preserves. */
    private static java.util.Set<String> idsIn(ExperienceStore store) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (StoredEntry e : store.all()) {
            ids.add(e.id());
        }
        return ids;
    }

    /**
     * A wrapper that has RECOVERED onto the real store — the production steady state.
     *
     * <p>Recovery is a background thread, so it is waited for rather than assumed. A test
     * that wrote while still degraded would be exercising the in-memory fallback, which is
     * a different object with different behaviour and would prove nothing about the
     * forward.</p>
     */
    private static RecoveringExperienceStore recoveredOnto(Path dir) throws Exception {
        RecoveringExperienceStore wrapped = new RecoveringExperienceStore(
            "test: the real open failed once", () -> H2ExperienceStore.openAt(dir), 20L);
        long deadline = System.currentTimeMillis() + 10_000L;
        while (wrapped.degradedNotice() != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(wrapped.degradedNotice() == null,
            "the control: this test is about the RECOVERED wrapper, and it never recovered"
                + " — anything asserted below would be about the in-memory fallback");
        return wrapped;
    }

    @Test
    void a_reload_through_the_wrapper_rewrites_in_place(@TempDir Path dir, @TempDir Path roots)
            throws Exception {
        RecoveringExperienceStore wrapped = recoveredOnto(dir);
        try {
            ExperienceMaintenance maintenance =
                new ExperienceMaintenance(wrapped, fqn -> null);
            story(roots, "a-story", "The body as it was first written.");
            Map<String, Object> first = maintenance.load(roots, true);
            assertEquals(1, first.get("loaded"), () -> "the control: it loaded — " + first);
            java.util.Set<String> before = idsIn(wrapped);
            assertTrue(!before.isEmpty(), "the control: rows landed");

            // The edit that reaches the defect: the description is UNCHANGED, so the row
            // is found and must be rewritten where it lies. This is the case
            // `upsertBySource` exists for, and the case the interface default gets wrong.
            story(roots, "a-story", "The body after somebody corrected it.");
            Map<String, Object> second = maintenance.load(roots, true);
            assertEquals(1, second.get("loaded"), () -> "the control: it re-ingested — " + second);

            // THE ASSERTION IS ON THE IDS, AND THE ROW COUNT WOULD NOT DO.
            //
            // A mutation is what settled that. With the wrapper's forward deleted — the
            // very defect this class exists for — the count is UNCHANGED and the first
            // version of this test stayed green: the interface default inserts a second
            // row, and the family reconciliation added in this same fold then deletes the
            // first, so the arithmetic comes out the same by two wrongs.
            //
            // What the two cannot fake is identity. An in-place rewrite KEEPS THE ID,
            // which is the durability `upsertBySource` was written to provide and is what
            // every link, every vouch and every ledger row pointing at that entry depends
            // on. Insert-then-clean-up silently renumbers it.
            assertEquals(before, idsIn(wrapped),
                "THE IDS SURVIVED THE RE-INGEST. Without the wrapper's own forward the"
                    + " interface default inserts instead of rewriting, so the row is"
                    + " renumbered and everything pointing at it is left pointing at"
                    + " nothing — while the row COUNT, and every direct-store test, stays"
                    + " exactly as it was");
        } finally {
            wrapped.close();
        }
    }
}
