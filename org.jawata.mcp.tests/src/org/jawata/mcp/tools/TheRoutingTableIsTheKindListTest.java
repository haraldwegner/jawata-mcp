package org.jawata.mcp.tools;

import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TWO ANSWERS AGREE — the migration gate for Stage 6a's M3b, and TEMPORARY BY DESIGN.
 *
 * <p>Before this step a front door answered "which kinds do you publish?" by walking its own
 * JSON schema, and answered "where does this kind go?" from a routing table. Two structures,
 * one fact. {@link KindedTool} makes the second the source of the first, and this asserts the
 * swap changed no answer: for every converted door, the enum a client reads out of the
 * published schema equals the key set of the routing table, in the same order.</p>
 *
 * <h2>Why it is written to be deleted</h2>
 *
 * <p>It compares two independent derivations, which is only meaningful while they ARE
 * independent. Once the description seam assembles the schema FROM the delegates, both sides
 * come from one place and this becomes a tautology — a test that reads as coverage while
 * checking that a thing equals itself. The migration step that lands the assembly deletes it,
 * and that deletion is part of the step rather than a tidy-up afterwards.</p>
 *
 * <p><b>Six doors, not nine.</b> {@code hierarchy}, {@code data} and
 * {@code change_method_signature} adopt the seam inside the lanes that grow them, so they are
 * deliberately absent — a gate demanding all nine here would fail for work that has not
 * started, and a test that fails for unstarted work teaches a reader to ignore it.</p>
 */
class TheRoutingTableIsTheKindListTest {

    /** The kinds a client reads — walked out of the published schema, the pre-M3b way. */
    private static List<String> schemaEnumOf(KindedTool door) {
        Object properties = door.getInputSchema().get("properties");
        assertTrue(properties instanceof Map, door.getName() + " publishes no properties");
        Object schema = ((Map<?, ?>) properties).get(door.discriminator());
        assertTrue(schema instanceof Map,
            door.getName() + " publishes no '" + door.discriminator() + "' property");
        Object values = ((Map<?, ?>) schema).get("enum");
        assertTrue(values instanceof java.util.Collection,
            door.getName() + " publishes no enum for its discriminator");
        List<String> kinds = new ArrayList<>();
        for (Object value : (java.util.Collection<?>) values) {
            kinds.add(String.valueOf(value));
        }
        return kinds;
    }

    @Test
    @DisplayName("every converted door's published enum equals its routing table's key set")
    void theSchemaAndTheRoutingTableAgree() {
        RefactoringChangeCache cache = new RefactoringChangeCache();
        java.util.function.Supplier<org.jawata.core.IJdtService> none = () -> null;

        Map<String, KindedTool> doors = new LinkedHashMap<>();
        doors.put("extract", new ExtractTool(none, cache));
        doors.put("inline", new InlineTool(none, cache));
        doors.put("move", new MoveTool(none, cache));
        doors.put("refactor_to_pattern", new RefactorToPatternTool(none, cache));
        doors.put("generate", new org.jawata.mcp.tools.codegen.GenerateTool(none, cache));
        doors.put("apply_cleanup", new ApplyCleanupTool(none, cache));

        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<String, KindedTool> entry : doors.entrySet()) {
            KindedTool door = entry.getValue();
            List<String> fromSchema = schemaEnumOf(door);
            List<String> fromTable = List.copyOf(door.delegates().keySet());
            compared += fromSchema.size();
            if (!fromSchema.equals(fromTable)) {
                problems.add(entry.getKey() + ": schema " + fromSchema + " != table " + fromTable);
            }
            // AND EACH DELEGATE AGREES WITH ITS OWN KEY. The key and kindName() are two
            // spellings of one fact — unavoidably so, because a field-holding door has no key
            // and a map-holding one has no need of the method. Asserting they match is what
            // keeps the duplication from being able to drift.
            door.delegates().forEach((kind, delegate) -> {
                if (!kind.equals(delegate.kindName())) {
                    problems.add(entry.getKey() + ": routed as '" + kind
                        + "' but calls itself '" + delegate.kindName() + "'");
                }
            });
        }

        // PROOF OF LIFE: six doors publishing nothing would satisfy every equality above.
        assertTrue(compared >= 40,
            "the six doors must publish their kinds here, or this compares empty lists and"
                + " passes. Compared: " + compared);
        assertTrue(problems.isEmpty(),
            "a door's published enum and its routing table are the same fact, and after M3b"
                + " the second is the source of the first:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("publishedKinds is the routing table's key set, not a stored list")
    void publishedKindsIsAView() {
        // The control for the assertion above: it compares the SCHEMA to the table, so it
        // would still pass if publishedKinds() had quietly stayed a third, stored copy.
        KindedTool extract = new ExtractTool(() -> null, new RefactoringChangeCache());
        assertEquals(List.copyOf(extract.delegates().keySet()), extract.publishedKinds(),
            "publishedKinds() is a view of the delegates, so it cannot disagree with them");
    }
}
