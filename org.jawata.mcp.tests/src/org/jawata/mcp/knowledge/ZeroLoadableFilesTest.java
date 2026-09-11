package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

/**
 * Sprint 28f D3 — a root with nothing loadable is REFUSED before anything is removed.
 *
 * <p><b>The accident this is named for happened.</b> On 2026-09-08 the verb took the
 * store from 378 rows to 194. Its old shape deleted every file-lane row and then
 * loaded, so a root that yielded nothing "completed" with {@code loaded=0}, an emptied
 * lane and a tombstone on every source in it — a total loss reported as a success with
 * an honest-looking count on it.</p>
 *
 * <p><b>What makes the fix possible is D1.</b> The load is an upsert now and removes
 * nothing, so it can run FIRST and its own result is the pre-flight. That is deliberately
 * not a separate walk: "loadable" is a real question — the stamp gate, the depth and size
 * caps, the parse, the duplicate rule — and a second walk would re-decide all of it and
 * could disagree with the loader it guards.</p>
 */
class ZeroLoadableFilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode rebuild(Path root) {
        ObjectNode a = JSON.createObjectNode();
        a.put("kind", "wipe_and_import");
        a.put("confirm", true);
        a.put("path", root.toString());
        return a;
    }

    /** A story the loader will accept: frontmatter, a description, and a stamp. */
    private static void story(Path dir, String name, String summary) throws Exception {
        Files.writeString(dir.resolve(name + ".md"),
            "---\nname: " + name + "\ndescription: " + summary
                + "\ntype: domain_fact\nreviewed: 2026-09-12\n---\n\nThe body.\n");
    }

    /**
     * An EMPTY root is refused, and the store still holds what it held.
     *
     * <p>The row count before and after is the assertion that matters. A refusal that
     * still emptied the lane would look identical in every other respect — same verb,
     * same {@code loaded=0} — which is precisely how the original went unnoticed.</p>
     */
    @Test
    void an_empty_root_is_refused_and_the_store_is_unchanged(@TempDir Path dir,
            @TempDir Path good, @TempDir Path empty) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);

            story(good, "kept", "a story the rebuild will be asked to abandon");
            assertTrue(tool.execute(rebuild(good)).isSuccess(),
                "the control: a root WITH a loadable file rebuilds normally");
            long held = store.count();
            assertTrue(held >= 1, () -> "the control: the first rebuild stored something, got " + held);

            ToolResponse refused = tool.execute(rebuild(empty));

            assertFalse(refused.isSuccess(),
                "a root yielding nothing must be REFUSED, not completed with loaded=0");
            String rendered = String.valueOf(refused.getError());
            assertTrue(rendered.contains("NOTHING_LOADABLE") || rendered.contains("no loadable"),
                () -> "the refusal must say what was wrong: " + rendered);
            assertEquals(held, store.count(),
                "AND NOTHING WAS REMOVED. This is the whole point: the old shape deleted"
                    + " first and asked afterwards, so an empty root emptied the lane and"
                    + " called it done");
        }
    }

    /**
     * A root holding files the loader REFUSES is the same case, and it is the likelier one.
     *
     * <p>An empty directory is an obvious mistake. A directory full of stories that all
     * lack their {@code reviewed:} stamp looks full to a human and yields nothing to this
     * verb — which is how someone reaches this state while believing their knowledge is
     * right there on disk.</p>
     */
    @Test
    void a_root_whose_files_are_all_refused_is_the_same_refusal(@TempDir Path dir,
            @TempDir Path good, @TempDir Path unstamped) throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            story(good, "kept", "a story that survives someone else's mistake");
            assertTrue(tool.execute(rebuild(good)).isSuccess());
            long held = store.count();

            // Present, parseable, and unstamped — so the rebuild's own gate declines it.
            Files.writeString(unstamped.resolve("unstamped.md"),
                "---\nname: unstamped\ndescription: a story nobody had a cold reader check"
                    + "\ntype: domain_fact\n---\n\nThe body.\n");

            ToolResponse refused = tool.execute(rebuild(unstamped));

            assertFalse(refused.isSuccess(),
                "a full-looking root that yields nothing is the dangerous case, not the"
                    + " empty one");
            assertEquals(held, store.count(), "the store is untouched");
        }
    }

    /** And a root that DOES load still rebuilds — or the refusal above proves nothing. */
    @Test
    void a_root_with_a_loadable_file_still_rebuilds(@TempDir Path dir, @TempDir Path roots)
            throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            story(roots, "first", "the story this rebuild is about");
            ToolResponse done = tool.execute(rebuild(roots));

            assertTrue(done.isSuccess(), () -> "expected a rebuild: " + done.getError());
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = (java.util.Map<String, Object>) done.getData();
            assertEquals(Boolean.TRUE, data.get("success"),
                () -> "a rebuild that loaded at least as much as it retired is a success: " + data);
            assertTrue(store.count() >= 1);
        }
    }
}
