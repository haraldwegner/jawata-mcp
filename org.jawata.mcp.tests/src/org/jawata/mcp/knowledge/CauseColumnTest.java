package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c (v15) — the CAUSE is first-class: the diagnosis behind an entry,
 * distinct from the symptoms that reveal it.
 *
 * <p>The triad is situation → complication → solution, and the complication had
 * no column: it lived inside summary/details prose, unqueryable — ruled
 * first-class on 2026-08-27 ("this should not be buried somewhere"). One
 * symptom maps to many causes (a fast heartbeat: running, a heart attack, a
 * virus) and the solution binds to the cause — so a symptom-recall returning
 * several entries is a differential, and this column discriminates it.</p>
 *
 * <p>Every test drives the production writers and readers — the record verb,
 * the md ingest, export/import, the recall render — never hand-set state.</p>
 */
class CauseColumnTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "call failed: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    /** The record verb writes it; the row carries it; the recall renders it. */
    @Test
    void a_recorded_cause_lands_in_the_row_and_reaches_the_reader() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "failure_mode");
        a.put("summary", "poll the order status over REST when the websocket ack does not arrive");
        a.put("situation", "when an order ack has not arrived within the expected window");
        a.put("cause", "websocket delivery is not guaranteed; REST GET is the truth");
        a.put("verdict", "worked");
        execute(a);

        StoredEntry row = store.all().get(0);
        assertEquals("websocket delivery is not guaranteed; REST GET is the truth",
            row.facets().cause(),
            "the diagnosis must BE IN THE ROW, queryable — not buried in prose");
    }

    /** A row recorded without one stays honestly null — never derived. */
    @Test
    void an_entry_without_a_cause_carries_null_not_a_derived_sentence() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "domain_fact");
        a.put("summary", "the settlement window closes an hour before the venue");
        execute(a);
        assertNull(store.all().get(0).facets().cause());
    }

    /** The md ingest reads `cause:` — and `complication:`, the triad's own word. */
    @Test
    void the_ingest_reads_cause_and_complication_frontmatter(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("factory.md"),
            "---\nname: factory\ndescription: \"use a factory when construction varies\"\n"
            + "type: reference\nsituation: constructing a new object\n"
            + "cause: the class has many variations to construct\n---\nbody\n");
        Files.writeString(dir.resolve("builder.md"),
            "---\nname: builder\ndescription: \"use a builder for many-field construction\"\n"
            + "type: reference\nsituation: constructing a new object\n"
            + "complication: many fields must be initialized during construction\n---\nbody\n");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        execute(a);

        List<StoredEntry> rows = store.all();
        assertTrue(rows.stream().anyMatch(e ->
                "the class has many variations to construct".equals(e.facets().cause())),
            "cause: frontmatter must land in the column");
        assertTrue(rows.stream().anyMatch(e ->
                "many fields must be initialized during construction".equals(e.facets().cause())),
            "complication: is the same key — the triad's own vocabulary");
    }

    /**
     * THE DIFFERENTIAL, end to end: two entries sharing ONE situation are told
     * apart by their causes — which is the whole reason the column exists.
     */
    @Test
    void same_situation_entries_are_discriminated_by_cause(@TempDir Path dir) throws Exception {
        the_ingest_reads_cause_and_complication_frontmatter(dir);
        List<StoredEntry> rows = store.all().stream()
            .filter(e -> "constructing a new object".equals(e.facets().situation()))
            .toList();
        assertEquals(2, rows.size(), "one situation, two entries");
        assertEquals(2, rows.stream().map(e -> e.facets().cause()).distinct().count(),
            "and the cause is what tells them apart — without it the reader has"
                + " two identical triggers and no way to choose");
    }

    /** Export → wipe → import identity, on a row that CARRIES one — the only
     *  fixture that can detect the loss. */
    @Test
    void the_cause_survives_an_export_import_round_trip() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", "read the broker state over REST when the stream goes quiet");
        a.put("situation", "when the websocket has delivered nothing for the expected interval");
        a.put("cause", "the stream drops silently under reconnect storms");
        a.put("verdict", "worked");
        execute(a);

        List<Map<String, Object>> exported = store.exportEntries(null, null);
        store.wipe();
        store.importEntries(exported);
        assertEquals("the stream drops silently under reconnect storms",
            store.all().get(0).facets().cause(),
            "a column an export carries and an import cannot bind is lost on"
                + " every backup — silently, and permanently on the orphan path");
    }
}
