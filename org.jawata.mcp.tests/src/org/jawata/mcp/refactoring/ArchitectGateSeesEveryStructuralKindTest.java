package org.jawata.mcp.refactoring;

import org.jawata.mcp.tools.ExtractTool;
import org.jawata.mcp.tools.InlineTool;
import org.jawata.mcp.tools.MoveTool;
import org.jawata.mcp.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT THE ARCHITECT GATE WOULD ACTUALLY SEE, asked of the real tools.
 *
 * <p>{@link Tool#structuralKinds()} decides whether the architect-involvement gate fires on
 * an edit. A C6 audit derived, from the reference graph rather than by reading, that
 * <b>nothing read the value back</b>: {@code Tool#structuralKinds} had three references — a
 * javadoc, the production registration, and one test that fed it to a registry and then
 * asked the registry a different question — and {@code OperationRegistry#isStructural} was
 * exercised only against registries built from LITERALS in test files.</p>
 *
 * <p>So the declaration could be right, wrong, or absent and every suite stayed green. It
 * was absent: {@code inline} had no override at all, and the gate was therefore silent on
 * {@code subclass}, whose entire job is removing a class from a hierarchy. That is the shape
 * this file exists to make impossible — not the specific omission, which is fixed, but the
 * CLASS of it, which is a declaration nobody reads.</p>
 *
 * <h2>Asked the way the gate asks</h2>
 *
 * <p>{@link ArchitectGate} qualifies the kind before asking, because a bare {@code method}
 * is published by three front doors and means something different in each. This test asks
 * in the same spelling, so a change to the qualification would break it here rather than
 * silently in production.</p>
 */
class ArchitectGateSeesEveryStructuralKindTest {

    /** The three doors Stage 6 built on, each registered the way the application does. */
    private static OperationRegistry registryOfRealTools() {
        OperationRegistry registry = new OperationRegistry();
        for (Tool tool : List.<Tool>of(
                new ExtractTool(() -> null, new RefactoringChangeCache()),
                new InlineTool(() -> null, new RefactoringChangeCache()),
                new MoveTool(() -> null, new RefactoringChangeCache()))) {
            registry.register(tool.getName(), tool.publishedKinds(), tool.isMechanical(),
                tool.isStructural(), tool.structuralKinds(), "kind");
        }
        return registry;
    }

    @Test
    @DisplayName("every kind the three doors call structural is structural in the registry the gate reads")
    void whatTheToolsDeclareIsWhatTheGateSees() {
        OperationRegistry registry = registryOfRealTools();

        Set<String> declared = new LinkedHashSet<>();
        for (Tool tool : List.<Tool>of(
                new ExtractTool(() -> null, new RefactoringChangeCache()),
                new InlineTool(() -> null, new RefactoringChangeCache()),
                new MoveTool(() -> null, new RefactoringChangeCache()))) {
            for (String kind : tool.structuralKinds()) {
                declared.add(OperationRegistry.qualify(tool.getName(), kind));
            }
        }

        // PROOF OF LIFE. An empty declared set would make every assertion below vacuous,
        // and an absent declaration is exactly the defect this file was written for.
        assertTrue(declared.size() >= 10,
            "the three doors must declare structural kinds at all — an absent override reads"
                + " identically to a considered 'nothing here is structural', which is how"
                + " inline's went unnoticed through the stage that added three of them: "
                + declared);

        List<String> unseen = new ArrayList<>();
        for (String operation : declared) {
            if (!registry.isStructural(operation)) {
                unseen.add(operation);
            }
        }
        assertTrue(unseen.isEmpty(),
            "these kinds declare themselves structural and the gate would not fire on them: "
                + unseen);

        // THE ONE THE AUDIT FOUND SILENT, named rather than left to the loop — a loop over a
        // set the same code produced can pass while the set is wrong, and this row is the
        // reason the file exists.
        assertTrue(registry.isStructural(OperationRegistry.qualify("inline", "subclass")),
            "removing a class from a hierarchy must reach the architect gate");
        assertTrue(registry.isStructural(OperationRegistry.qualify("inline", "class")),
            "and so must folding a class away entirely");

        // AND THE OTHER SIDE. A set that swallowed everything would pass every assertion
        // above; the criterion is "changes a SIGNATURE or a HIERARCHY", and these do not.
        assertFalse(registry.isStructural(OperationRegistry.qualify("inline", "variable")),
            "replacing a local with its initializer changes no signature and no hierarchy");
        assertFalse(registry.isStructural(
                OperationRegistry.qualify("move", "statements_to_callers")),
            "moving statements rewrites call SITES but changes no signature and no"
                + " hierarchy — deliberately not structural, and asserted so that the"
                + " decision is visible rather than an omission");
    }

    @Test
    @DisplayName("the count each door declares is pinned, so a kind cannot be added without a ruling")
    void theDeclaredCountsArePinned() {
        assertEquals(6, new ExtractTool(() -> null, new RefactoringChangeCache())
                .structuralKinds().size(),
            "extract: superclass, interface, class, combine_functions, function_to_command,"
                + " split_phase");
        assertEquals(3, new InlineTool(() -> null, new RefactoringChangeCache())
                .structuralKinds().size(), "inline: class, subclass, middle_man");
        assertEquals(4, new MoveTool(() -> null, new RefactoringChangeCache())
                .structuralKinds().size(), "move: method, field, class, package");
    }
}
