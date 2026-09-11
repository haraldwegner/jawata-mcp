package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

/**
 * Sprint 28f D4 — a machine with no story folder is the ORDINARY case, not a broken one.
 *
 * <p><b>D1 says so in as many words: a substrate root of null is "every client".</b> The
 * whole loop has to close there — write a story, find it again, and be able to take a
 * copy of what holds it — because that is the machine most of this product runs on. A
 * store that only works where somebody happens to keep a folder of markdown is a store
 * most users do not have.</p>
 *
 * <p><b>And the instruction such a machine is given had to change, which is D4.</b> It
 * used to read "write the story as a .md file under this root, then reseed" — advice
 * nobody without a root can follow, naming a verb that no longer exists. It now says
 * {@code record}, which is true on every machine: D1 stopped a load deleting, so a
 * recorded row survives one, and D2 puts a copy beside the store before anything
 * removes rows.</p>
 */
class NullSubstrateLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode args(String kind) {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", kind);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    private static ToolResponse record(ExperienceTool tool, String summary) {
        ObjectNode a = args("record");
        a.put("type", "lesson");
        a.put("summary", summary);
        a.put("situation", "when a machine has no folder of stories on it at all");
        a.put("verdict", "worked");
        return tool.execute(a);
    }

    /**
     * The whole loop on a store with a file but NO story folder: record, recall, copy.
     *
     * <p>The file store is what makes the backup half meaningful; the absent SUBSTRATE
     * is the point. Those are different things and the test keeps them apart, because
     * conflating them is how "every client" got advice that only suits one.</p>
     */
    @Test
    void record_recall_and_back_up_on_a_machine_with_no_story_folder(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);

            assertTrue(record(tool, "a clock read at the wrong moment dates the event to 1970")
                .isSuccess(), "recording must work with no substrate at all");
            assertEquals(1L, store.count());

            ObjectNode recall = args("recall");
            recall.put("symptom", "an event dated to 1970");
            recall.put("format", "text");
            assertTrue(tool.execute(recall).isSuccess(), "and recall answers");

            Map<String, Object> copy = data(tool.execute(args("backup")));
            assertNotNull(copy.get("backup"),
                "and the row can be copied — the store's durability does not depend on"
                    + " anyone keeping a folder of markdown");
        }
    }

    /**
     * A null substrate root is reported as the DEFAULT, never as degraded.
     *
     * <p>The discriminator is the advice: the block must not tell a machine with no root
     * to go and write a file under one. It names {@code record}, which is the thing that
     * actually works there.</p>
     */
    /**
     * A null substrate root is reported as the DEFAULT, and it is told how to add.
     *
     * <p><b>The first version of this test could not fail, and the way it could not is
     * worth keeping.</b> It asserted two ABSENCES over the whole stats rendering — no
     * "degraded", no "kind=reseed" — and both were already true of the code before this
     * sprint touched it, so it passed on the old build as readily as the new one. The
     * reason is structural rather than careless: {@code substrateBlock} RETURNS EARLY when
     * no row carries a file source, and the string those assertions were hunting lives
     * past that return. The branch this test's own fixture takes never emitted it.</p>
     *
     * <p>So the assertion is now POSITIVE and on the branch this fixture actually reaches:
     * the block names {@code record} as the way to add. That is D4's deliverable, and on a
     * machine with no story folder it is the only advice that can be followed — the note
     * this branch used to carry said "load a substrate first", which is both the
     * file-first instruction this release inverts and impossible for the reader it was
     * shown to.</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void a_missing_story_folder_is_reported_as_ordinary(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            record(tool, "a store with no folder behind it still holds what it is told");

            Map<String, Object> stats = data(tool.execute(args("stats")));
            Object block = stats.get("substrate");
            assertTrue(block instanceof Map,
                () -> "the control: stats must carry the substrate block at all, or every"
                    + " assertion below is about an absent object: " + stats);
            Map<String, Object> substrate = (Map<String, Object>) block;

            assertEquals(null, substrate.get("root"),
                () -> "the control: this fixture records, so nothing came from a file and"
                    + " the root is genuinely null — the branch under test: " + substrate);

            String advice = String.valueOf(substrate.get("howToAdd"));
            assertTrue(advice.contains("kind=record"),
                () -> "A MACHINE WITH NO STORY FOLDER MUST STILL BE TOLD HOW TO ADD, and"
                    + " `record` is the only answer that works there. This branch used to"
                    + " return before the advice was written, carrying a note that said to"
                    + " load a substrate first — advice its own reader cannot follow: "
                    + advice);
            assertFalse(advice.contains("kind=reseed"),
                () -> "and it must not name a verb this release retired: " + advice);

            String rendered = String.valueOf(stats);
            assertFalse(rendered.contains("degraded"),
                () -> "no folder is the ORDINARY state — D1 calls it every client — and"
                    + " must not be reported as a fault: " + rendered);
        }
    }
}
