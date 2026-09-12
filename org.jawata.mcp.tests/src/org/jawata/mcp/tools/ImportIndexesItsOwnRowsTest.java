package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.knowledge.EmbeddingService;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28f E5 — the {@code import} verb indexes the rows it wrote, before it answers.
 *
 * <p><b>This test exists because a C3 audit found the behaviour shipped with none.</b>
 * {@code import} and {@code wipe_and_import} gained the embed pass and two response keys, and
 * nothing anywhere drove them: deleting both calls left the whole suite green. That matters
 * more here than anywhere else in the stage, because IMPORTED rows are the defect the stage
 * was opened for — the meaning index held the catalogue and nothing else, so recall answered a
 * question about a scheduler retry loop with a design pattern.</p>
 *
 * <p><b>Why the existing tests could not catch it, and were not wrong to miss it.</b> The two
 * classes that need unembedded rows route deliberately AROUND the verb, through the store,
 * because the verb now indexes and so cannot leave the state they want. Their comments say so.
 * The end-to-end gate cannot see it either: it waits for the store to report itself fully
 * indexed before asking anything, so it is insensitive by construction to WHICH writer did the
 * indexing.</p>
 *
 * <p><b>The control is the store-side write in the same test.</b> Identical rows through
 * {@code store.importEntries} must be left unembedded — that is what a restore does. Without
 * it, "the verb left nothing pending" is equally true of a machine where every write indexes
 * by accident, and the assertion would say nothing about the verb.</p>
 */
class ImportIndexesItsOwnRowsTest {

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

    /** Three rows in the export shape the import verb accepts. */
    private static List<Map<String, Object>> rows(String tag) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            out.add(Map.of(
                "id", "9a8b7c6d-" + tag + "-4000-8000-00000000000" + i,
                "type", "lesson",
                "summary", "a " + tag + " lesson number " + i + " about draining a backlog",
                "status", "accepted"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> importThrough(String verb, List<Map<String, Object>> rows) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", verb);
        ArrayNode arr = args.putArray("entries");
        for (Map<String, Object> row : rows) {
            ObjectNode o = arr.addObject();
            row.forEach((k, v) -> o.put(k, String.valueOf(v)));
        }
        args.put("confirm", true);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> verb + " failed: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private Object pending() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "stats");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> block =
            (Map<String, Object>) ((Map<String, Object>) r.getData()).get("embedding");
        assertNotNull(block, "stats must carry the embedding block");
        return block.get("unembedded");
    }

    @Test
    void the_verb_leaves_nothing_waiting_while_the_store_path_does() {
        Map<String, Object> report = importThrough("import", rows("verb"));

        // The response says what it did, on every machine — these keys are the caller's
        // only way to know whether the rows it just handed over are findable yet.
        assertNotNull(report.get("embedded"),
            () -> "the import must report how many rows it indexed: " + report);
        assertNotNull(report.get("unembedded"),
            () -> "and what it left behind, which is the number a caller acts on: " + report);

        if (!EmbeddingService.shared().available()) {
            System.out.println("=== import: no embedder — the count assertions below cannot"
                + " run, and the response-shape assertions above did ===");
            return;
        }

        assertEquals(0L, ((Number) pending()).longValue(),
            () -> "THE CLAIM: a caller handed rows back must be able to FIND them. This is"
                + " the defect the stage was opened for — imported rows sat outside the"
                + " meaning index while the catalogue sat inside it, so every recall for"
                + " them answered with design patterns. Pending was: " + pending());

        // THE CONTROL. The same rows written straight through the store — what a restore
        // does — must be left waiting. Without this the zero above is equally true of a
        // machine where every write happens to index, and says nothing about the verb.
        ((H2ExperienceStore) store).importEntries(rows("store"));
        assertTrue(((Number) pending()).longValue() > 0,
            () -> "the store path must NOT index, or the assertion above is not about the"
                + " verb at all. Pending was: " + pending());
    }
}
