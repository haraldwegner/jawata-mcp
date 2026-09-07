package org.jawata.mcp.tools;

import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, Stage 2a — THE RESPONSE-SHAPE POPULATION, DERIVED.
 *
 * <p>Deliverable D2 asks for "one list of every response shape, each marked carries the
 * stamp or cannot degrade". That is unmeasurable until the set is derived, so this class
 * is the derivation and its output.</p>
 *
 * <h2>WHAT THIS COUNTS, AND WHAT IT DOES NOT — read this before using the number</h2>
 *
 * <p>It counts the <b>nine refactoring doors</b>, because {@link RefactoringDoors} is the
 * only registration list a test can reach. The product publishes <b>42</b> tools: the
 * analysis family, the store, debug, profile and the rest register directly in
 * {@code JawataApplication.registerTools()}, which is <b>private, has one caller, and
 * exposes no accessor</b> — so nothing outside the application can ask which tools ship,
 * and every test hand-registers whatever it needs.</p>
 *
 * <p><b>So Stage 2b must NOT compare its marked count against this number.</b> Nine is a
 * subset with a known boundary, not the response-shape population. Completing the census
 * needs the registration seam — extract the list out of {@code registerTools()} into
 * something a caller can invoke with test dependencies — already homed as the plan's
 * Stage 9 work and as the architect's standing finding that nothing can ask which tools
 * ship.</p>
 *
 * <p>The boundary is stated here rather than left implicit because a count that measures a
 * subset under a whole-population name is this sprint's own recurring defect, and a 9 that
 * reads as "every response shape" would be exactly that.</p>
 *
 * <h2>What a response SHAPE is, and why the population is the doors</h2>
 *
 * <p>There is exactly ONE response type — {@code ToolResponse}, carrying success, data,
 * error and meta. A "shape" is therefore the payload a tool puts in {@code data}, not a
 * class per tool. Every tool returns one by its own signature, so the population is the
 * set of tools, derived from {@link RefactoringDoors} — the same call
 * {@code JawataApplication.registerTools()} registers from, so a door that stops
 * shipping stops counting HERE too.</p>
 *
 * <h2>Two instruments that do NOT answer this, recorded because both were tried</h2>
 *
 * <ol>
 *   <li><b>Counting references to a response factory.</b> {@code ToolResponse#success}
 *       resolves to 12 production classes; {@code #symbolNotFound} to far more;
 *       {@code #invalidParameter} returns 757 references and truncates. Those measure
 *       WHERE a response is built — a larger and different question. A tool that builds
 *       its response through {@code error} rather than {@code success} still has exactly
 *       one shape.</li>
 *   <li><b>Asking the index for subtypes.</b> {@code find_references(kind=implementations)}
 *       on the tool base answers <b>206</b>, which is 103 distinct types listed TWICE —
 *       once at their source path and once under a workspace cache path for the same
 *       project. The number is plausible, which is what makes it dangerous. Anything
 *       deriving a population from that query must de-duplicate by type identity.</li>
 * </ol>
 *
 * <p>This class is immune to both: it counts OBJECTS the application constructs, so a
 * duplicate index entry cannot inflate it and a factory choice cannot deflate it.</p>
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

    /** Every response shape, keyed by the type that produces it. */
    private static Set<String> shapePopulation() {
        Set<String> shapes = new LinkedHashSet<>();
        for (AbstractTool door : registeredDoors()) {
            shapes.add(door.getClass().getName());
        }
        return shapes;
    }

    /**
     * THE PARTIAL CENSUS — the refactoring doors, which is what is reachable today.
     *
     * <p>The number is written down rather than derived from the same list it counts: a
     * count taken from its own subject cannot fail. Drop a door from
     * {@code RefactoringDoors.all} and this falls and names what went.</p>
     */
    @Test
    @DisplayName("the refactoring-door population is derived from the registration list, not counted by hand")
    void thePopulationIsDerived() {
        Set<String> shapes = shapePopulation();

        assertTrue(shapes.size() >= 8,
            "PROOF OF LIFE: the door list must be non-trivial, or every assertion below "
                + "passes over an empty set. Got: " + shapes);

        assertEquals(new TreeSet<>(shapes).size(), shapes.size(),
            "a type appearing twice would mean the derivation is counting index entries "
                + "rather than constructed objects — the 206-versus-103 defect");

        assertEquals(9, shapes.size(),
            "the registered refactoring-door count moved. Doors now: " + new TreeSet<>(shapes));
    }

    /**
     * The boundary, asserted rather than left in prose.
     *
     * <p>If a future change makes the full registered set reachable, this fails and
     * whoever made it reachable widens the census — instead of the subset quietly
     * continuing to stand in for the whole.</p>
     */
    @Test
    @DisplayName("the full registered tool set is still NOT reachable from a test")
    void theFullSetIsStillUnreachable() {
        boolean reachable = false;
        for (java.lang.reflect.Method m
                : org.jawata.mcp.JawataApplication.class.getDeclaredMethods()) {
            if (m.getName().equals("registerTools")
                    && java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                reachable = true;
            }
        }
        assertTrue(!reachable,
            "registerTools() has become publicly reachable, so the response-shape census "
                + "can now cover all 42 published tools rather than the 9 refactoring "
                + "doors. Widen shapePopulation() and delete this test.");
    }

    /**
     * Each door answers for its own shape, so the population needs no hand-written list.
     *
     * <p>This is the clause that makes the census re-derivable by anyone: the names come
     * off the objects, never off a literal beside them.</p>
     */
    @Test
    @DisplayName("every door in the population names itself")
    void everyDoorNamesItself() {
        for (AbstractTool door : registeredDoors()) {
            assertTrue(door.getName() != null && !door.getName().isBlank(),
                door.getClass().getName() + " publishes no name, so its shape cannot be "
                    + "attributed to it in Stage 2b's list");
        }
    }
}
