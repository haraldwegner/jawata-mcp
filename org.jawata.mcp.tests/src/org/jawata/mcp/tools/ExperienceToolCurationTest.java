package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21a Stage 5 (item G) — curation verbs: export / import / list. */
class ExperienceToolCurationTest {

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    private String record(String type, String summary, String symbol, String language,
            String symptom) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", type);
        a.put("summary", summary);
        if (symbol != null) {
            a.put("symbol", symbol);
        }
        if (language != null) {
            a.put("language", language);
        }
        if (symptom != null) {
            a.putArray("symptoms").add(symptom);
        }
        return (String) data(tool.execute(a)).get("id");
    }

    private ToolResponse exec(String kind, java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        fill.accept(a);
        return tool.execute(a);
    }

    @Test
    @SuppressWarnings("unchecked")
    void export_wipe_import_roundtrips_losslessly() {
        String javaId = record("lesson", "java lesson", "com.example.Foo", null, "flaky test");
        String rustId = record("failure_mode", "rust lesson", "gateway::forward", "rust", null);
        // Promote one so the CURRENT status (not the frozen body one) must survive.
        exec("promote", a -> a.put("id", javaId));

        Map<String, Object> export = data(exec("export", a -> { }));
        assertEquals(2, export.get("count"));
        List<Map<String, Object>> entries = (List<Map<String, Object>>) export.get("entries");

        exec("wipe", a -> { });
        assertEquals(0L, store.count());

        ObjectNode importArgs = mapper.createObjectNode();
        importArgs.put("kind", "import");
        importArgs.set("entries", mapper.valueToTree(entries));
        Map<String, Object> imported = data(tool.execute(importArgs));
        assertEquals(2, imported.get("imported"));
        assertEquals(0, imported.get("duplicates"));

        assertEquals(2L, store.count());
        var afterJava = store.listEntries(null, "accepted", null, null, 10);
        assertEquals(1, afterJava.size(), "promoted status survived the round-trip");
        assertEquals(javaId, afterJava.get(0).id());
        var afterRust = store.listEntries(null, null, null, "rust", 10);
        assertEquals(1, afterRust.size(), "language facet survived");
        assertEquals(rustId, afterRust.get(0).id());
        // Symptoms travelled: recall by symptom still hits.
        assertFalse(store.query(new org.jawata.mcp.knowledge.RecallQuery(
            null, null, null, "flaky test", null)).isEmpty());

        // Importing the same entries again only counts duplicates.
        Map<String, Object> again = data(tool.execute(importArgs));
        assertEquals(0, again.get("imported"));
        assertEquals(2, again.get("duplicates"));
    }

    @Test
    void export_and_import_via_file(@TempDir Path dir) {
        record("lesson", "to file", null, null, null);
        Path file = dir.resolve("export.json");
        Map<String, Object> export = data(exec("export", a -> a.put("path", file.toString())));
        assertEquals(true, export.get("written"));
        assertTrue(Files.isRegularFile(file));

        exec("wipe", a -> { });
        Map<String, Object> imported = data(exec("import", a -> a.put("path", file.toString())));
        assertEquals(1, imported.get("imported"));
        assertEquals(1L, store.count());
    }

    @Test
    void export_filters_by_status() {
        String keep = record("lesson", "stays candidate", null, null, null);
        String promoted = record("lesson", "gets accepted", null, null, null);
        exec("promote", a -> a.put("id", promoted));
        Map<String, Object> export = data(exec("export", a -> a.put("status", "candidate")));
        assertEquals(1, export.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) export.get("entries");
        assertEquals(keep, entries.get(0).get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_filters_and_includes_superseded() {
        record("lesson", "a candidate", "com.example.A", null, null);
        String gone = record("lesson", "superseded one", "com.example.B", null, null);
        exec("promote", a -> { a.put("id", gone); a.put("status", "superseded"); });

        Map<String, Object> candidates = data(exec("list", a -> a.put("status", "candidate")));
        assertEquals(1, candidates.get("count"));

        Map<String, Object> superseded = data(exec("list", a -> a.put("status", "superseded")));
        assertEquals(1, superseded.get("count"), "curation sees what recall hides");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) superseded.get("entries");
        assertEquals(gone, rows.get(0).get("id"));

        Map<String, Object> scoped = data(exec("list", a -> a.put("scope", "com.example")));
        assertEquals(2, scoped.get("count"), "package-prefix scope filter");
    }

    @Test
    void list_format_text_renders_flat_lines() {
        record("lesson", "line one", "com.example.A", null, null);
        ToolResponse r = exec("list", a -> a.put("format", "text"));
        assertTrue(r.isSuccess());
        String text = (String) r.getData();
        assertTrue(text.contains("[lesson/candidate] line one @ com.example.A"), text);
    }

    @Test
    void import_requires_entries_or_path() {
        assertFalse(exec("import", a -> { }).isSuccess());
    }
}
