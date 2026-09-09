package org.jawata.mcp.tools;

import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, Stage 2a — THE RESPONSE-SHAPE POPULATION, DERIVED.
 *
 * <p>Deliverable D2 asks for "one list of every response shape, each marked carries the
 * stamp or cannot degrade". That is unmeasurable until the set is derived, so this class
 * is the derivation and its committed output.</p>
 *
 * <h2>THE UNIT IS DOOR x PUBLISHED KIND, not the door</h2>
 *
 * <p>An earlier version of this class counted the nine refactoring doors and called that
 * the population. An audit refused it, correctly: a door DISPATCHES, and each kind it
 * publishes puts a different payload in {@code data}. Nine is not the number of shapes,
 * it is the number of dispatchers. The justification offered for nine — "every tool
 * returns one by its own signature" — proves the opposite of what it was used for: the
 * signature returns {@code ToolResponse} for all 42 published tools, so a type-system
 * argument yields ONE, never nine.</p>
 *
 * <p>The finer unit needs no new seam: {@link KindedTool#publishedKinds()} is a public
 * interface method the nine doors implement. The population is one loop further in.</p>
 *
 * <h2>WHAT THIS COUNTS, AND WHAT IT DOES NOT</h2>
 *
 * <p>It covers the kinds of the nine REFACTORING doors, because {@link RefactoringDoors}
 * is the only registration list a test can reach. The product publishes <b>42</b> tools;
 * the analysis family, the store, debug and profile register directly inside
 * {@code JawataApplication.registerTools()}, which is private with one caller and no
 * accessor. Completing the census needs that registration seam, already homed to the
 * plan's Stage 9.</p>
 *
 * <p><b>So Stage 2b marks against THIS number for the refactoring surface only</b>, and
 * the rest of the 42 tools' shapes are outside what any test can currently enumerate.
 * Stated because a count that measures a subset under a whole-population name is this
 * sprint's own recurring defect.</p>
 *
 * <h2>Two instruments that do NOT answer this, recorded with their numbers</h2>
 *
 * <ol>
 *   <li><b>Counting references to a response factory</b> measures WHERE a response is
 *       built. {@code ToolResponse#success} resolves to 12 production classes;
 *       {@code #invalidParameter} returns 757 references and truncates. A tool building
 *       its response through {@code error} rather than {@code success} still has its
 *       shapes.</li>
 *   <li><b>Asking the index for subtypes</b> answers <b>206</b> for the direct subtypes
 *       of {@code AbstractTool} — 103 distinct types listed twice, once at their source
 *       path and once under a workspace cache path. It is also only the DIRECT subtypes:
 *       {@code inspect(kind=type_hierarchy)} reports 169, because tools extending
 *       {@code AbstractRefactoringTool} are not in the 103. Both numbers are plausible,
 *       which is what makes them dangerous.</li>
 * </ol>
 */
class ResponseShapeCensusTest {

    /**
     * The doors the application registers, built exactly as it builds them.
     *
     * <p>Null dependencies are deliberate and safe: nothing here executes a tool, and
     * construction does not touch the service.</p>
     */
    private static List<AbstractTool> registeredDoors() {
        return RefactoringDoors.all(() -> null, new RefactoringChangeCache());
    }

    /** Door name to its published kinds — the enumeration, off the objects themselves. */
    private static Map<String, List<String>> shapesByDoor() {
        Map<String, List<String>> shapes = new TreeMap<>();
        for (AbstractTool door : registeredDoors()) {
            if (door instanceof KindedTool kinded) {
                shapes.put(door.getName(), new ArrayList<>(kinded.publishedKinds()));
            }
        }
        return shapes;
    }

    /** Every shape, as "door kind" — the flat population Stage 2b marks against. */
    private static Set<String> shapePopulation() {
        Set<String> flat = new LinkedHashSet<>();
        shapesByDoor().forEach((door, kinds) -> kinds.forEach(k -> flat.add(door + " " + k)));
        return flat;
    }

    /**
     * THE COMMITTED OUTPUT. Not just a count — the enumeration itself, so a reader sees
     * WHAT the population is without checking out the tree and breaking something.
     *
     * <p>Pinned per door rather than as one total: a total alone cannot say which door
     * moved, and the audit that refused the first version of this class refused exactly
     * that.</p>
     */
    private static final Map<String, Integer> EXPECTED_KINDS_PER_DOOR = new LinkedHashMap<>();
    static {
        EXPECTED_KINDS_PER_DOOR.put("extract", 11);
        EXPECTED_KINDS_PER_DOOR.put("inline", 5);
        EXPECTED_KINDS_PER_DOOR.put("move", 6);
        EXPECTED_KINDS_PER_DOOR.put("hierarchy", 7);
        EXPECTED_KINDS_PER_DOOR.put("generate", 7);
        EXPECTED_KINDS_PER_DOOR.put("refactor_to_pattern", 11);
        EXPECTED_KINDS_PER_DOOR.put("change_method_signature", 11);
        EXPECTED_KINDS_PER_DOOR.put("data", 11);
        EXPECTED_KINDS_PER_DOOR.put("apply_cleanup", 10);
    }

    /** 11+5+6+7+7+11+11+10+10. Summed here so the two cannot drift apart silently. */
    private static int expectedTotal() {
        return EXPECTED_KINDS_PER_DOOR.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    @DisplayName("the population is door x published kind, derived, and it names what moved")
    void thePopulationIsDerived() {
        Map<String, List<String>> derived = shapesByDoor();

        assertEquals(EXPECTED_KINDS_PER_DOOR.keySet(), new TreeSet<>(derived.keySet()),
            "the DOOR SET moved. Missing: "
                + missing(EXPECTED_KINDS_PER_DOOR.keySet(), derived.keySet())
                + " · unexpected: " + missing(derived.keySet(), EXPECTED_KINDS_PER_DOOR.keySet()));

        Map<String, Integer> counts = new TreeMap<>();
        derived.forEach((door, kinds) -> counts.put(door, kinds.size()));
        assertEquals(new TreeMap<>(EXPECTED_KINDS_PER_DOOR), counts,
            "a door's KIND COUNT moved; the map above names which and by how much");

        assertEquals(expectedTotal(), shapePopulation().size(),
            "the flat population must equal the per-door sum, or two doors publish a kind "
                + "of the same name and the flat set silently merges them");
    }

    /**
     * A door registered TWICE would be invisible to a set-based count, so the check is
     * List against Set — not Set against Set, which is an identity that cannot fail.
     */
    @Test
    @DisplayName("no door is registered twice")
    void noDoorIsRegisteredTwice() {
        List<AbstractTool> doors = registeredDoors();
        Set<String> distinct = new LinkedHashSet<>();
        for (AbstractTool d : doors) {
            distinct.add(d.getClass().getName());
        }
        assertEquals(doors.size(), distinct.size(),
            "a class appears twice in RefactoringDoors.all, so every set-based count of "
                + "the doors is silently short. Registered: " + doors.size()
                + ", distinct: " + distinct);
    }

    /**
     * Every door names itself, and the guard against a vacuous loop lives HERE — this is
     * the method that passes over an empty population, not the pinned one above.
     */
    @Test
    @DisplayName("every door names itself and publishes at least one kind")
    void everyDoorNamesItself() {
        Map<String, List<String>> derived = shapesByDoor();

        assertTrue(derived.size() >= 8,
            "PROOF OF LIFE: the loop below asserts nothing on an empty map. Got: " + derived);

        derived.forEach((name, kinds) -> {
            assertTrue(name != null && !name.isBlank(),
                "a door publishes no name, so its shapes cannot be attributed to it");
            assertTrue(!kinds.isEmpty(),
                name + " publishes no kinds, so it contributes no shape to the census");
        });
    }

    private static String missing(Set<String> from, Set<String> in) {
        Set<String> gone = new TreeSet<>(from);
        gone.removeAll(in);
        return gone.isEmpty() ? "(none)" : gone.toString();
    }
}
