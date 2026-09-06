package org.jawata.mcp.tools;

import java.util.List;

import org.jawata.mcp.refactoring.OperationRegistry;

/**
 * THE ONE PLACE A TOOL BECOMES AN OPERATION — seam D of Stage 6a.
 *
 * <p>Registering a tool and publishing its operations are two different jobs that happened
 * to share a method. {@code ToolRegistry.register} put the tool in a map, decided which of
 * its kinds count as operations, and wrote them to {@link OperationRegistry}, all in four
 * lines — so a test that wanted to ask "what does this tool publish?" either re-derived the
 * answer or built a whole registry to find out. Both happened, which is how the reader
 * acquired four copies.</p>
 *
 * <p>This class is the second job, extracted and made public. Its whole content is the
 * POLICY: which tools' kinds are operations a cure step may name. That was never a fact
 * about a tool — {@code find_quality_issue} knows its own kinds perfectly well and has no
 * opinion about whether a cure may name {@code god_class} — so it does not belong on
 * {@link Tool}, and {@link Tool#publishedKinds()} deliberately does not carry it.</p>
 *
 * <h2>What the filter is, and what replaces it</h2>
 *
 * <p>Two clauses, and each is written out rather than folded together:</p>
 *
 * <ul>
 *   <li><b>Refactoring front doors only.</b> A {@code kind} enum is a common shape.
 *       {@code analyze} publishes {@code method}, {@code inspect} publishes {@code source},
 *       {@code find_quality_issue} publishes {@code naming}. Harvesting all of them put
 *       hundreds of non-operations into one flat namespace, where a cure step naming
 *       {@code god_class} validated and was called runnable.</li>
 *   <li><b>Not the lifecycle door.</b> {@code refactoring}'s {@code kind} enum names which of
 *       {@code refactor_to_pattern}'s operations can be run as a parity-gated PLAN, and every
 *       one of them is already published by that tool. Harvesting it registered six operations
 *       twice; when the registry learned to REFUSE an ambiguous operation, the next boot threw
 *       on {@code compose_method}. A view of another tool's operations is not a second
 *       publisher of them.</li>
 * </ul>
 *
 * <p><b>BOTH CLAUSES WERE NAME LISTS, AND M10 HAS NOW REPLACED THEM WITH ONE TYPE TEST</b> —
 * {@code tool instanceof KindedTool}. The paragraph that stood here predicted exactly that,
 * and it was right about the mechanism: {@code refactoring} deliberately implements
 * {@link FrontDoor} and not {@link KindedTool}, so the second clause became redundant in the
 * same moment rather than needing a rule of its own. The two bullets above are kept as the
 * REASONING — they say what the filter is for, which a reader still needs — but they no
 * longer describe two constants, because there are none.</p>
 *
 * <p><b>It ran at C8 rather than at Stage 9, and the reason is a defect the list had already
 * shipped.</b> The allowlist named eight doors; nine implement {@link KindedTool}. The missing
 * one was {@code change_method_signature}, which became a door ONE DAY after this list was
 * written and was never added — so eleven shipped refactorings were published as no operation
 * at all, and a cure naming any of them would have thrown at boot. That is what a name list
 * does when the thing it names changes: it fails OPEN, and it fails silently, because no
 * reference-updating refactoring touches a string literal.</p>
 */
public final class OperationSurface {

    private OperationSurface() {
    }

    /**
     * Publish a tool's operations. The single production write to {@link OperationRegistry},
     * and the reason this class exists rather than four lines inside a map insert.
     */
    public static void publish(Tool tool) {
        OperationRegistry.theRegistry().register(tool.getName(), operationKindsOf(tool),
            tool.isMechanical(), tool.isStructural(), tool.structuralKinds());
    }

    /**
     * The kinds a tool publishes THAT ARE OPERATIONS — the policy, asked of the surface
     * rather than derived by whoever needs it.
     *
     * <p>The tool answers what it dispatches on ({@link Tool#publishedKinds()}); this answers
     * whether those are operations. Keeping them apart is what lets the filter be replaced at
     * Stage 9 without touching how any tool reports its own dispatch.</p>
     */
    public static List<String> operationKindsOf(Tool tool) {
        // M10 (Stage 9's step, run at C8): the TYPE answers this, not a list of names.
        //
        // Both clauses used to be name lists — an eight-name allowlist and an explicit
        // exclusion for the lifecycle door — and the class note above predicted both would
        // collapse into this one test. They did, and BOTH halves are discharged by it:
        // `refactoring` deliberately implements FrontDoor and NOT KindedTool, so its verbs
        // never enter the operation namespace without a rule of their own.
        //
        // WHY IT COULD NOT WAIT FOR STAGE 9, which is where the plan had put it. The
        // allowlist was written on 2026-09-04 and `change_method_signature` became the ninth
        // door on 2026-09-05 — one day later, and the list was never widened. So ELEVEN
        // shipped refactorings were published as no operation at all, and any cure naming one
        // would have thrown at boot. Nothing could see it: the routing guard examines that
        // door and PASSES, because the gap this list created is what makes its silence
        // correct. A list of names fails OPEN, and it fails silently.
        //
        // MEASURED BEFORE RUNNING, because the risk that decides it is a name collision
        // making an operation ambiguous, which IS a boot failure: across the whole tools tree
        // only `method` (x3), `class` (x3) and `variable` (x2) are shared names, each of the
        // eleven occurs exactly once, and none matches any bare recipe the cure table
        // declares. Both branches of the boot check are unreachable by this widening.
        //
        // AND IT DOES NOT GO ALONE. Publishing a kind also CLASSIFIES it, so this would have
        // registered eleven signature-changing operations as non-structural and silenced the
        // architect gate on all of them. KindedTool.structuralKinds() lands in the same
        // change for exactly that reason.
        if (!(tool instanceof KindedTool)) {
            return List.of();
        }
        return tool.publishedKinds();
    }
}
