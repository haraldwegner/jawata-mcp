package org.jawata.mcp.tools.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractTool;
import org.jawata.mcp.tools.ExtractTool;
import org.jawata.mcp.tools.InlineTool;
import org.jawata.mcp.tools.MoveTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * THE C9 PER-TOOL CONTRACT, checked against what the tools actually publish.
 *
 * <p>The plan's C9 clause names a kind count per tool and says why a ceiling would not do:
 * it "would pass a tool that finished two kinds short". So the three numbers below are the
 * CONTRACT, written into the plan before the work started, and this compares them against
 * the schemas a client reads. The left side is computed; the right side is quoted.</p>
 *
 * <h2>This is NOT the recomputation clause, and saying so is the point</h2>
 *
 * <p>The C3-to-C7 exit also asks for "the performed-refactoring count recomputed from the
 * shipped tool list, not asserted" — the sprint's headline figure, 62 of Fowler's 66. A C6
 * audit read this test's earlier javadoc as claiming to satisfy that, and the claim was too
 * broad: three hand-written per-tool integers are a contract check, not a recomputation.</p>
 *
 * <p><b>That clause was UNMET when this paragraph was written, and Stage 9 met it.</b> The
 * reason it stood open for four checkpoints was structural rather than an omission: nothing in
 * the code mapped a Fowler row to the kind that performs it, because that mapping lived only
 * in the spec's 90-row inventory, which is a document. {@code
 * org.jawata.mcp.tools.PerformedRefactoringCountTest} is now that table, and it counts 62 by
 * joining each row against what {@code RefactoringDoors} publishes.</p>
 *
 * <p><b>A C9 auditor found this paragraph still asserting UNMET</b> — a stale claim left
 * standing by the very commit that met it, and by one whose own subject was deleting a stale
 * exemption. It also found that the three counts below were the ONLY per-door counts gated
 * anywhere; the other five now live beside the recomputation, in
 * {@code PerformedRefactoringCountTest.everyDoorPublishesItsAssignedKindCount()}.</p>
 */
class Stage6ShippedCountTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
    }

    @Test
    @DisplayName("the three Stage 6 doors publish exactly the kind counts C9 contracts for")
    void theKindCountsAreWhatThePlanAssigns() {
        RefactoringChangeCache cache = new RefactoringChangeCache();
        Map<String, AbstractTool> doors = new LinkedHashMap<>();
        doors.put("extract", new ExtractTool(() -> service, cache));
        doors.put("inline", new InlineTool(() -> service, cache));
        doors.put("move", new MoveTool(() -> service, cache));

        Map<String, Integer> counted = new LinkedHashMap<>();
        doors.forEach((name, door) -> counted.put(name, door.publishedKinds().size()));

        assertEquals(Map.of("extract", 11, "inline", 5, "move", 6), counted,
            "the plan's C9 clause names a count per tool and says why a ceiling would not"
                + " do — it 'would pass a tool that finished two kinds short'. These are"
                + " read from the published schemas, so a kind that reaches the enum"
                + " without reaching a delegate still counts, and a kind that reaches a"
                + " delegate without reaching the enum does not. Counted: " + counted
                + ", kinds: " + doors.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue().publishedKinds()).toList());
    }
}
