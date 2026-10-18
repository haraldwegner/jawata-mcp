package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.Occasion;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ARCH-C22-5 — the engine owns the table of which lanes each occasion asks, and a call may carry
 * the occasion instead of a lane list.
 *
 * <p>The rows below are the contract: they are the hook crate's table
 * ({@code jawata_hook::lanes}), written out once more here so a change to either side is a change
 * somebody has to make on purpose. A client that sends only the occasion spells no lane, so it
 * cannot drift from these rows; the lane list still works and the two forms exclude each other.</p>
 */
class OccasionsTheEngineOwnsTest {

    private static final Map<String, List<String>> TABLE = Map.of(
        "typed_message", List.of("experience"),
        "tool_call", List.of("experience", "code"),
        "recall_gate", List.of("experience", "domain"),
        "after_compaction", List.of("transcript"),
        "design_question", List.of("experience", "patterns"),
        "does_it_exist", List.of("code"));

    private H2ExperienceStore store;
    private ExperienceTool tool;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static Set<String> sorted(List<String> names) {
        return new TreeSet<>(names);
    }

    private ObjectNode nominate() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "nominate");
        a.put("question", "how should a retry that gives up be reported?");
        return a;
    }

    @SuppressWarnings("unchecked")
    private String laneOf(ToolResponse r) {
        assertTrue(r.isSuccess(), "got " + r.getError());
        return String.valueOf(((Map<String, Object>) r.getData()).get("lane"));
    }

    @Test
    void everyOccasionAsksExactlyTheLanesOfItsRow() {
        assertEquals(TABLE.keySet(), new TreeSet<>(java.util.Arrays.stream(Occasion.values())
                .map(Occasion::wire).toList()),
            "the engine's occasions are exactly the table's rows, none more and none fewer");
        for (Occasion o : Occasion.values()) {
            assertEquals(sorted(TABLE.get(o.wire())), sorted(o.scope().wires()),
                o.wire() + " asks the lanes of its row");
        }
    }

    @Test
    void anOccasionAnswersWithTheSameLanesAsTheListItStandsFor() {
        // The discussion is searched with recall, never nominated, so its occasion is left out.
        for (Occasion o : java.util.EnumSet.complementOf(java.util.EnumSet.of(Occasion.AFTER_COMPACTION))) {
            ObjectNode byOccasion = nominate();
            byOccasion.put("occasion", o.wire());
            ObjectNode byLanes = nominate();
            com.fasterxml.jackson.databind.node.ArrayNode names = byLanes.putArray("lanes");
            TABLE.get(o.wire()).forEach(names::add);

            assertEquals(laneOf(tool.execute(byLanes)), laneOf(tool.execute(byOccasion)),
                o.wire() + ": the occasion must search what the list it stands for searches");
        }
    }

    /**
     * THE CONTROL that makes the case above discriminate: a nomination naming neither lanes nor an
     * occasion is refused, so an `occasion` the engine ignored could not answer the same lanes.
     */
    @Test
    void aCallNamingNeitherLanesNorAnOccasionIsRefused() {
        assertFalse(tool.execute(nominate()).isSuccess(),
            "no default scope exists, so the occasion form is the only thing that can have answered");
    }

    @Test
    void theOccasionNameIsReadWithoutRegardForCaseOrSpacing() {
        ObjectNode a = nominate();
        a.put("occasion", "  Design_Question ");

        assertEquals("experience,patterns", laneOf(tool.execute(a)),
            "the name is stripped and lower-cased before it is looked up");
    }

    @Test
    void aBlankOccasionIsNoOccasionAndTheLaneFormStillAnswers() {
        ObjectNode a = nominate();
        a.put("occasion", "   ");
        a.put("lane", "experience");

        assertEquals("experience", laneOf(tool.execute(a)),
            "a blank occasion says nothing, so the lane the call named is the one that counts");
    }

    @Test
    void recallTakesAnOccasionToo() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "recall");
        a.put("symptom", "a retry that gives up is reported twice");
        a.put("occasion", "typed_message");
        ToolResponse byOccasion = tool.execute(a);
        assertTrue(byOccasion.isSuccess(), "got " + byOccasion.getError());

        a.put("occasion", "on_a_whim");
        assertFalse(tool.execute(a).isSuccess(), "and an unknown occasion is refused there as well");
    }

    @Test
    void anOccasionBesideALaneListIsRefusedBecauseTheCallSaidTwoThings() {
        ObjectNode both = nominate();
        both.put("occasion", "design_question");
        both.put("lane", "experience");
        ToolResponse r = tool.execute(both);

        assertFalse(r.isSuccess(), "an occasion and a lane together must not be answered");
        assertTrue(String.valueOf(r.getError()).contains("not both"), "got " + r.getError());

        ObjectNode withList = nominate();
        withList.put("occasion", "design_question");
        withList.putArray("lanes").add("experience");
        assertFalse(tool.execute(withList).isSuccess(), "nor an occasion beside a lanes list");
    }

    @Test
    void anUnknownOccasionIsRefusedAndTheKnownOnesAreListed() {
        ObjectNode a = nominate();
        a.put("occasion", "on_a_whim");
        ToolResponse r = tool.execute(a);

        assertFalse(r.isSuccess());
        String message = String.valueOf(r.getError());
        for (String wire : TABLE.keySet()) {
            assertTrue(message.contains(wire), "the refusal teaches the occasion " + wire + ": " + message);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaOffersTheEnginesOccasionsAndNoOthers() {
        Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
        Map<String, Object> occasion = (Map<String, Object>) props.get("occasion");

        assertEquals(new TreeSet<>(TABLE.keySet()),
            new TreeSet<>(new ArrayList<>((List<String>) occasion.get("enum"))),
            "the schema's enum is derived from the engine's table, not typed a second time");
    }
}
