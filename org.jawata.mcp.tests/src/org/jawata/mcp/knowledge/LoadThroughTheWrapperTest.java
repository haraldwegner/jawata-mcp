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

    /** A story file the loader accepts; re-writing it with a new summary is the case. */
    private static void story(Path dir, String name, String summary) throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: " + summary
                + "\ntype: domain_fact\n---\n\nThe body.\n");
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
            story(roots, "a-story", "what this story said when it was first written");
            Map<String, Object> first = maintenance.load(roots, true);
            assertEquals(1, first.get("loaded"), () -> "the control: it loaded — " + first);
            long afterFirst = wrapped.count();
            assertTrue(afterFirst >= 1, () -> "the control: rows landed, got " + afterFirst);

            // The edit that finds the defect: a NEW summary matches no existing row by
            // (source_ref, summary), so a store that inserts instead of reconciling ends
            // up holding both the old statement and the new one.
            story(roots, "a-story", "what the same story says after somebody corrected it");
            Map<String, Object> second = maintenance.load(roots, true);
            assertEquals(1, second.get("loaded"), () -> "the control: it re-ingested — " + second);

            assertEquals(afterFirst, wrapped.count(),
                "THE ROW COUNT DID NOT GROW. One file still says one thing, so the store"
                    + " holds one statement of it. Without the wrapper's own forward this"
                    + " is where a production re-ingest silently accumulates, while every"
                    + " test that builds the H2 store directly stays green");
        } finally {
            wrapped.close();
        }
    }
}
