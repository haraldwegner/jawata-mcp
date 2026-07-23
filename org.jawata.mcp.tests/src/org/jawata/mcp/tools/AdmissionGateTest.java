package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.ToolExperienceStore;
import org.jawata.mcp.learn.ToolExperience;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 27a D10 / Stage 4b — the admission ROUTING gate, tested THROUGH the
 * tool path ({@code experience(kind=record)}), never a validator class in
 * isolation: a directly-tested validator proves nothing about wiring (the
 * Sprint-27 lesson — the tests did the wiring the product lacked).
 *
 * <p>Fixture values are INVENTED lookalikes of the shapes the committed
 * derivation ({@code embed-goldens/derive_admission.py}) observed in the
 * private corpus — no corpus row is copied here.</p>
 */
class AdmissionGateTest {

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

    private ObjectNode record(String summary, String... symptoms) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "record");
        args.put("type", "lesson");
        args.put("summary", summary);
        if (symptoms.length > 0) {
            ArrayNode arr = args.putArray("symptoms");
            for (String s : symptoms) {
                arr.add(s);
            }
        }
        return args;
    }

    /** Each observed misplaced shape is refused, and the message carries the
     *  FOUR promised parts: the field, the rule, the redirect (where the
     *  content belongs) and a concrete rephrase. Nothing is stored. */
    @Test
    void each_misplaced_shape_is_refused_with_the_teaching_message() {
        Map<String, String> shapes = Map.of(
            "path", "client-app/docs/ordering-notes.md",
            "flag", "--enable-preview",
            "heading", "Root cause:",
            "code", "handleOrderTimeout()",
            "id", "9f8e7d6c5b4a39281",
            "tag", "project-example-migration");
        for (Map.Entry<String, String> shape : shapes.entrySet()) {
            ToolResponse resp = tool.execute(record(
                "a valid one-sentence lesson about the " + shape.getKey() + " case",
                shape.getValue()));
            assertFalse(resp.isSuccess(), "symptom shaped as " + shape.getKey()
                + " must be refused: " + shape.getValue());
            String msg = resp.getError().getMessage();
            assertTrue(msg.contains(shape.getValue()),
                "the message names the offending value: " + msg);
            assertTrue(msg.contains("RULE:"), "the message states the rule: " + msg);
            assertTrue(msg.contains("WHERE IT BELONGS"),
                "the message redirects to the right home: " + msg);
            assertTrue(msg.contains("REPHRASE:"),
                "the message offers a concrete rephrase: " + msg);
        }
        assertEquals(0L, store.count(), "refused records are not stored");
    }

    @Test
    void a_heading_shaped_summary_is_refused_naming_the_summary_field() {
        ToolResponse resp = tool.execute(record("Critical bugs found:"));
        assertFalse(resp.isSuccess(), "a heading is not an experience");
        String msg = resp.getError().getMessage();
        assertTrue(msg.contains("summary"), "the field is named: " + msg);
        assertTrue(msg.contains("REPHRASE:"), "a rephrase is offered: " + msg);
        assertEquals(0L, store.count());
    }

    @Test
    void a_genuine_experience_record_is_accepted_unchanged() {
        ToolResponse resp = tool.execute(record(
            "the retry loop must re-read the queue head before re-arming",
            "the same message was delivered twice after a reconnect",
            "deadlock"));
        assertTrue(resp.isSuccess(), "prose symptoms and a plain word are admitted");
        assertEquals(1L, store.count());
    }

    /** The refusal counts every misplaced item, not just the first. */
    @Test
    void multiple_misplaced_items_are_counted_in_one_refusal() {
        ToolResponse resp = tool.execute(record(
            "a valid lesson sentence",
            "src/main/java/example/Runner.java", "--dry-run", "Fix:"));
        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().getMessage().contains("2 more misplaced items"),
            "the tail names the remaining count: " + resp.getError().getMessage());
    }

    /** The gate guards NEW records only: an import (restore/backup) of rows
     *  that would be refused as new records round-trips untouched. */
    @Test
    void import_round_trips_rows_the_gate_would_refuse_as_new_records() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "import");
        ArrayNode entries = args.putArray("entries");
        ObjectNode e = entries.addObject();
        e.put("id", "0a1b2c3d-0000-4000-8000-000000000001");
        e.put("type", "lesson");
        e.put("summary", "an old row with harvested keyword symptoms");
        e.put("status", "accepted");
        ArrayNode symptoms = e.putArray("symptoms");
        symptoms.add("legacy/tools/build.sh");
        symptoms.add("--legacy-flag");
        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess(), "import is not admission-gated: "
            + (resp.getError() == null ? "" : resp.getError().getMessage()));
        assertEquals(1L, store.count(), "the restored row landed");
    }

    /** Change (1)'s own gate: the SERVED schema text teaches the routing —
     *  read back and asserted, so the teaching half cannot ship unbuilt. */
    @Test
    void the_served_schema_text_states_the_field_rules_and_the_routing() {
        Map<String, Object> schema = tool.getInputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        String summary = String.valueOf(((Map<?, ?>) props.get("summary")).get("description"));
        String symptoms = String.valueOf(((Map<?, ?>) props.get("symptoms")).get("description"));
        String details = String.valueOf(((Map<?, ?>) props.get("details")).get("description"));
        assertTrue(summary.contains("judgeable sentence"),
            "summary teaches the one-sentence rule: " + summary);
        assertTrue(summary.contains("refused"), "summary states the consequence");
        assertTrue(symptoms.contains("how the problem LOOKED"),
            "symptoms teach the prose rule: " + symptoms);
        assertTrue(symptoms.contains("tool lane"),
            "symptoms redirect tool output to its lane: " + symptoms);
        assertTrue(details.contains("paths"),
            "details names itself as the artifact home: " + details);
    }

    /** D10's retrievability probe: a tool-output record stored in the TOOL
     *  LANE is retrievable through the lane's own query path — tool artifacts
     *  are payload there, poison in experience symptoms. */
    @Test
    void a_tool_lane_record_is_retrievable_from_its_lane() {
        ToolExperienceStore lane = new ToolExperienceStore((H2ExperienceStore) store);
        lane.append(new ToolExperience("s1",
            "rename com.example.Widget#spin to rotate", "rename_symbol",
            ToolExperience.OUTCOME_COMPILED, null));
        List<ToolExperience> hits = lane.recentMatching("Widget", 5);
        assertEquals(1, hits.size(), "the lane's own query path finds the record");
        assertTrue(hits.get(0).situation().contains("com.example.Widget#spin"),
            "the artifact IS the payload in this lane");
    }
}
