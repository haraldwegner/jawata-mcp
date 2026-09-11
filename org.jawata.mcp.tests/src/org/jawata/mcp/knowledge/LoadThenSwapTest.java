package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

/**
 * Sprint 28f D3 — a rebuild whose load delivers nothing LOSES nothing.
 *
 * <p><b>This is the class C2's mutation is aimed at.</b> Restore the pre-load
 * {@code deleteBySource} loop to {@code wipeAndImport} and every case here goes red,
 * because the rows would be gone before the load was ever asked whether it had
 * anything to put back. That order is the 2026-09-08 accident: 378 rows to 194, with
 * the verb reporting a completed rebuild.</p>
 *
 * <p><b>What is NOT asserted here, stated rather than left to be assumed.</b> The plan
 * describes a staging set — rows written behind a marker and dropped if the load
 * throws. That is not built. D1 made the load an upsert, so a load that dies partway
 * leaves the rows it already wrote; what it cannot do is REMOVE anything, because
 * nothing is retired until the load has returned and delivered. So the property held
 * here is "no loss", not "no change", and the difference is declared at C2.</p>
 */
class LoadThenSwapTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void story(Path dir, String name, String summary) throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: " + summary
                + "\ntype: domain_fact\nreviewed: 2026-09-12\n---\n\nThe body.\n");
    }

    private static ObjectNode rebuild(String path) {
        ObjectNode a = JSON.createObjectNode();
        a.put("kind", "wipe_and_import");
        a.put("confirm", true);
        a.put("path", path);
        return a;
    }

    /** Load a root, and answer how many file-derived rows the store ended up holding. */
    private static long seed(ExperienceTool tool, H2ExperienceStore store, Path root)
            throws Exception {
        assertTrue(tool.execute(rebuild(root.toString())).isSuccess(),
            "the control: the store really was seeded before the failing rebuild");
        long held = store.count();
        assertTrue(held >= 1, () -> "the control expects rows, got " + held);
        return held;
    }

    /**
     * A rebuild pointed at a path that IS NOT THERE changes nothing.
     *
     * <p>The likeliest way to reach this in anger: a root that moved, a typo, a machine
     * where the folder was never checked out. Under the old order that call deleted the
     * whole file lane and then discovered there was nothing to reload.</p>
     */
    @Test
    void a_rebuild_from_a_path_that_does_not_exist_loses_nothing(@TempDir Path dir,
            @TempDir Path roots) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            story(roots, "kept", "a story a mistyped path must not cost anyone");
            story(roots, "also-kept", "a second story, so a survivor is visible");
            long held = seed(tool, store, roots);

            ToolResponse refused = tool.execute(
                rebuild(roots.resolve("no-such-directory").toString()));

            assertFalse(refused.isSuccess(), "a path that is not there is not a rebuild");
            assertEquals(held, store.count(),
                "NOTHING WAS LOST. This is the assertion C2's mutation is aimed at:"
                    + " restore the pre-load delete and the rows are gone here, because"
                    + " the delete ran before anyone asked whether the load had anything");
        }
    }

    /**
     * A rebuild from a root whose files the loader all REFUSES changes nothing.
     *
     * <p>Worse than the missing path, because the directory looks full. Stories without
     * the {@code reviewed:} stamp are present, parseable and declined — so the root
     * yields nothing while a human looking at it sees their knowledge sitting there.</p>
     */
    @Test
    void a_rebuild_whose_files_are_all_refused_loses_nothing(@TempDir Path dir,
            @TempDir Path roots, @TempDir Path unstamped) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            story(roots, "kept", "a story someone else's missing stamp must not cost");
            long held = seed(tool, store, roots);

            Files.writeString(unstamped.resolve("unstamped.md"),
                "---\nname: unstamped\ndescription: a story no cold reader has checked"
                    + "\ntype: domain_fact\n---\n\nThe body.\n");
            ToolResponse refused = tool.execute(rebuild(unstamped.toString()));

            assertFalse(refused.isSuccess());
            assertEquals(held, store.count(), "the store is exactly as it was");
        }
    }

    /**
     * And the swap DOES happen when the load delivers — or the three refusals above
     * would be satisfied by a verb that never rebuilds anything.
     *
     * <p>One source goes (its file is gone), one survives, and the response says both:
     * {@code removed} counts the retired rows and {@code success} compares them against
     * what was loaded.</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void a_rebuild_that_delivers_does_swap(@TempDir Path dir, @TempDir Path roots)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            story(roots, "goes", "a story whose file is about to disappear");
            story(roots, "stays", "a story whose file is not going anywhere");
            seed(tool, store, roots);
            assertEquals(2, store.fileSourceRefs().size(), "the control: two sources in");

            Files.delete(roots.resolve("goes.md"));
            ToolResponse done = tool.execute(rebuild(roots.toString()));

            assertTrue(done.isSuccess(), () -> "expected a rebuild: " + done.getError());
            java.util.Map<String, Object> data = (java.util.Map<String, Object>) done.getData();
            assertEquals(List.of("memory:" + roots.resolve("stays.md")),
                List.copyOf(store.fileSourceRefs()),
                () -> "exactly the surviving source remains: " + data);
            assertEquals(Boolean.TRUE, data.get("success"),
                () -> "a rebuild that loaded as much as it retired is a success: " + data);
            assertTrue(data.get("backup") == null || data.get("backup") instanceof String,
                "and it names the copy it took, or says there is none");
        }
    }
}
