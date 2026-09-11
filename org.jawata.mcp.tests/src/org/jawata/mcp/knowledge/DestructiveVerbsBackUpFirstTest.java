package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

/**
 * Sprint 28f D2 — the copy is taken BY THE VERB, and this is the test that can
 * tell.
 *
 * <p><b>Why this class exists beside {@code StoreBackupsTest}.</b> That one
 * drives {@code StoreBackups} directly, so it proves the mechanism works and
 * proves NOTHING about whether the verbs use it: delete the call from any verb
 * and every one of its cases still passes. The property the spec actually
 * states is about the verbs — <i>"first takes a file copy of the store,
 * server-side at the verb so no caller can bypass it, and returns the copy's
 * path in its own response"</i> — and only a test that goes through the front
 * door can hold it. This is the one C1's mutation is aimed at.</p>
 *
 * <p>Each case asserts three things, and the third is the one that is easy to
 * leave out: the response NAMES the copy, the named file EXISTS, and it was
 * created by THIS call rather than being some earlier copy still lying about.</p>
 */
class DestructiveVerbsBackUpFirstTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode args(String kind) {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", kind);
        return n;
    }

    private static String put(H2ExperienceStore store, String summary) {
        return store.put(SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
            .symbol("com.example.Target").build());
    }

    /**
     * The copy this call left, or a failure naming the verb.
     *
     * <p>Reads the response's own {@code backup} field rather than listing the
     * directory: the spec says the verb RETURNS the path, and a test that went
     * looking on disk would pass against a verb that took a copy and never said
     * so — which is half the deliverable missing.</p>
     */
    @SuppressWarnings("unchecked")
    private static Path copyNamedBy(ToolResponse response, String verb) {
        assertTrue(response.isSuccess(), verb + " must succeed here: " + response);
        Map<String, Object> data = (Map<String, Object>) response.getData();
        Object named = data.get("backup");
        assertNotNull(named,
            verb + " must name the copy it took, in its own response. The response carried: "
                + data.keySet());
        Path copy = Path.of(String.valueOf(named));
        assertTrue(Files.isRegularFile(copy),
            verb + " named a copy that is not there: " + copy);
        assertTrue(copy.getFileName().toString().endsWith("-" + verb + ".zip"),
            verb + " must name its copy for itself, so a human choosing one can tell which"
                + " decision it preceded. Got: " + copy.getFileName());
        return copy;
    }

    @Test
    void wipe_copies_first(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "the row the wipe is about to take");
            copyNamedBy(tool.execute(args("wipe")), "wipe");
            assertEquals(0L, store.count(), "the control: the wipe really happened");
        }
    }

    @Test
    void prune_copies_first(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "a row prune will not take, but a copy is owed anyway");
            ObjectNode a = args("prune");
            a.put("days", 0);
            copyNamedBy(tool.execute(a), "prune");
        }
    }

    @Test
    void importing_copies_first(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            String id = put(store, "a row an import could overwrite");
            List<Map<String, Object>> entries = store.exportByIds(List.of(id));
            ObjectNode a = args("import");
            a.set("entries", JSON.valueToTree(entries));
            copyNamedBy(tool.execute(a), "import");
        }
    }

    @Test
    void delete_copies_first(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            String id = put(store, "the row named for deletion");
            ObjectNode a = args("delete");
            a.set("ids", JSON.valueToTree(List.of(id)));
            copyNamedBy(tool.execute(a), "delete");
            assertEquals(0L, store.count(), "the control: the delete really happened");
        }
    }

    @Test
    void the_rebuild_copies_first(@TempDir Path dir, @TempDir Path from) throws Exception {
        Files.writeString(from.resolve("a-story.md"),
            "---\nname: a-story\ndescription: a story the rebuild can read\n"
                + "type: domain_fact\n---\n\nThe body.\n");
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "a row the rebuild is about to work around");
            ObjectNode a = args("reseed");
            a.put("confirm", true);
            a.put("path", from.toString());
            copyNamedBy(tool.execute(a), "reseed");
        }
    }

    /**
     * A restore is destructive over the wire too, and reports it in the same field.
     *
     * <p>Not one of the four verbs the spec enumerates — this is a WIDENING under
     * that same sentence's universal, "every destructive verb". Restoring throws
     * away every row written since the copy was taken, which is the hazard the
     * clause exists for, in the one verb a caller reaches for when something has
     * already gone wrong.</p>
     */
    @Test
    void restore_copies_first(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "the row the copy holds");
            Path copy = new StoreBackups(() -> store).before("wipe");
            assertNotNull(copy);
            put(store, "the row the restore is about to throw away");

            ObjectNode a = args("restore");
            a.put("name", copy.getFileName().toString());
            copyNamedBy(tool.execute(a), "restore");
            assertEquals(1L, store.count(), "the control: the restore really happened");
        }
    }

    /**
     * An in-memory resident says the absence out loud.
     *
     * <p>"No copy was taken" and "a copy was taken and I did not mention it" must
     * not read alike, which is this codebase's own recorded top defect class. So
     * the response carries the field with a null and a reason rather than
     * omitting it.</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void an_in_memory_resident_reports_that_there_is_no_copy() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            put(store, "a row with no safety net behind it");
            ToolResponse response = tool.execute(args("wipe"));
            assertTrue(response.isSuccess());
            Map<String, Object> data = (Map<String, Object>) response.getData();
            assertTrue(data.containsKey("backup"),
                "the field must be PRESENT and null, not absent — an absent field reads as"
                    + " 'nothing to report' rather than 'there is no copy'");
            assertEquals(null, data.get("backup"));
            assertNotNull(data.get("backupNote"), "and the absence carries its reason");
        }
    }
}
