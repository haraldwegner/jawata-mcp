package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 7 deliverable 3 — the describing queue REACHED THROUGH THE DOOR.
 *
 * <p>{@code DescribedUnitsTest} proves the ledger; this proves an agent can get at it. The
 * two are not the same claim, and this sprint has now closed the gap between them three
 * times: a lane constant nothing assigned, a store folder with no way to switch it on, an
 * allowlist naming eight doors while a ninth existed. A ledger with no verb is that shape.</p>
 */
class DescribeVerbTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("compile-clean");
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> service, store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private ObjectNode args(String action) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", action);
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> unitsOf(ToolResponse r) {
        return (List<Map<String, Object>>) dataOf(r).get("units");
    }

    @Test
    @DisplayName("next hands out the units in scope, each with the hash done needs")
    void next_hands_out_the_units_in_scope() {
        ObjectNode a = args("next");
        a.put("scope", "com.example");
        ToolResponse response = tool.execute(a);

        assertTrue(response.isSuccess(), "got " + response.getError());
        Map<String, Object> data = dataOf(response);
        List<Map<String, Object>> units = unitsOf(response);

        assertFalse(units.isEmpty(),
            "PROOF OF LIFE: the fixture has source in com.example, so the queue is not"
                + " empty — without this the resume case below could pass against a verb"
                + " that always answers nothing: " + data);
        Map<String, Object> first = units.get(0);
        assertNotNull(first.get("unit"), "each unit says where it is: " + first);
        assertNotNull(first.get("contentHash"),
            "and carries the hash done() requires, so an agent never has to invent one: "
                + first);
        assertEquals("com.example", first.get("package"),
            "with the JDT facts needed to start reading it: " + first);
        assertNotNull(first.get("types"), "including what it declares: " + first);
        assertEquals(units.size(), data.get("returned"));
        assertTrue(((Number) data.get("outstanding")).intValue() >= units.size(),
            "and how much is left overall, so an empty batch cannot be confused with a"
                + " finished bundle: " + data);
    }

    @Test
    @DisplayName("a described unit stops being offered")
    void a_described_unit_stops_being_offered() {
        ObjectNode first = args("next");
        first.put("scope", "com.example");
        List<Map<String, Object>> before = unitsOf(tool.execute(first));
        assertFalse(before.isEmpty(), "proof of life");
        String unit = String.valueOf(before.get(0).get("unit"));
        String hash = String.valueOf(before.get(0).get("contentHash"));

        ObjectNode done = args("done");
        done.put("unit", unit);
        done.put("contentHash", hash);
        done.put("bundle", "compile-clean");
        ToolResponse recorded = tool.execute(done);
        assertTrue(recorded.isSuccess(), "got " + recorded.getError());
        assertEquals(Boolean.TRUE, dataOf(recorded).get("recorded"),
            "the verb says whether the row was WRITTEN rather than merely accepted: "
                + dataOf(recorded));

        List<Map<String, Object>> after = unitsOf(tool.execute(first));
        assertFalse(after.stream().anyMatch(u -> unit.equals(u.get("unit"))),
            "the described unit is gone from the queue: " + after);
        assertEquals(before.size() - 1, after.size(),
            "and exactly one unit left it — a queue that emptied entirely would satisfy"
                + " the assertion above while losing the rest of the bundle");
    }

    /**
     * THE OVER-CLAIM GUARD. {@code done} could quietly re-read the file and record today's
     * text, which would look helpful and be false: the agent described the text it was GIVEN,
     * and if the file has changed since, recording the new hash retires the unit for good
     * with a description nobody wrote. The parameter is required, and the refusal says where
     * the value comes from rather than only that it is missing.
     */
    @Test
    @DisplayName("done REFUSES without the hash, and says where to get it")
    void done_refuses_without_the_hash() {
        ObjectNode done = args("done");
        done.put("unit", "src/main/java/com/example/Clean.java");

        ToolResponse response = tool.execute(done);

        assertFalse(response.isSuccess(), "a missing hash is refused, not filled in");
        String message = String.valueOf(response.getError());
        assertTrue(message.contains("action=next"),
            "the refusal names where the value comes from: " + message);
        assertTrue(message.contains("contentHash"),
            "and which parameter it is: " + message);
    }

    @Test
    @DisplayName("an unknown action is refused by name, with the allowed ones")
    void an_unknown_action_is_refused_by_name() {
        ToolResponse response = tool.execute(args("describe_everything"));

        assertFalse(response.isSuccess());
        String message = String.valueOf(response.getError());
        assertTrue(message.contains("next") && message.contains("done"),
            "an unknown action names the ones that exist rather than failing blankly: "
                + message);
    }

    /**
     * PROGRESS REACHES A READER. A ledger whose counts nothing renders is the same shape as
     * a verb nothing dispatches — and {@code describedPerBundle} had no caller until this.
     */
    @Test
    @DisplayName("stats reports describing progress, in its own block")
    void stats_reports_describing_progress() {
        ObjectNode next = args("next");
        next.put("scope", "com.example");
        List<Map<String, Object>> units = unitsOf(tool.execute(next));
        assertFalse(units.isEmpty(), "proof of life");

        ObjectNode done = args("done");
        done.put("unit", String.valueOf(units.get(0).get("unit")));
        done.put("contentHash", String.valueOf(units.get(0).get("contentHash")));
        done.put("bundle", "compile-clean");
        assertTrue(tool.execute(done).isSuccess());

        ObjectNode statsArgs = json.createObjectNode();
        statsArgs.put("kind", "stats");
        Map<String, Object> stats = dataOf(tool.execute(statsArgs));

        @SuppressWarnings("unchecked")
        Map<String, Object> describing = (Map<String, Object>) stats.get("describing");
        assertNotNull(describing, "stats must carry the describing block: " + stats.keySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> perBundle =
            (Map<String, Object>) describing.get("describedPerBundle");
        assertEquals(1L, perBundle.get("compile-clean"),
            "the unit just described is counted under its bundle: " + describing);
        assertNotNull(stats.get("catalogue"),
            "and the PATTERN catalogue block is still its own section — two sections of one"
                + " name meaning different things is what the rename avoids, and folding"
                + " these together would reintroduce it where a reader compares them");
    }

    /**
     * The parameters are PUBLISHED. The schema is the only thing an agent can see, so a
     * parameter the code reads and the schema omits is usable and undiscoverable — the
     * sibling of the defect {@code ExperienceTool.KINDS}' own javadoc records for verbs, and
     * one this store shipped once already.
     */
    @Test
    @DisplayName("the queue's parameters are in the published schema")
    void the_queues_parameters_are_published() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props =
            (Map<String, Object>) tool.getInputSchema().get("properties");

        for (String name : List.of("action", "unit", "contentHash", "bundle")) {
            assertTrue(props.containsKey(name),
                "describe reads '" + name + "' and the schema must say so; published: "
                    + props.keySet());
        }
        assertTrue(String.valueOf(props.get("scope")).contains("describe"),
            "and a parameter SHARED with another verb says which verbs use it, or its"
                + " description is true of one caller and silent about the other");
    }
}
