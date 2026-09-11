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
    @Test
    void a_missing_story_folder_is_reported_as_ordinary(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            record(tool, "a store with no folder behind it still holds what it is told");

            Map<String, Object> stats = data(tool.execute(args("stats")));
            String rendered = String.valueOf(stats);

            assertFalse(rendered.contains("degraded"),
                () -> "no folder is the ORDINARY state — D1 calls it every client — and"
                    + " must not be reported as a fault: " + rendered);
            assertFalse(rendered.contains("kind=reseed"),
                () -> "and it must not name a verb this release retired: " + rendered);
        }
    }
}
