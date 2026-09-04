package org.jawata.mcp.tools;

import org.jawata.mcp.refactoring.OperationRegistry;

import java.util.List;
import java.util.Set;

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
 * <p>Both clauses are name lists today, and Stage 9's M10 replaces the first with
 * {@code tool instanceof KindedTool} — which is possible only because {@code refactoring}
 * deliberately implements {@link FrontDoor} and not {@link KindedTool}, so the second clause
 * becomes redundant at the same moment rather than needing a rule of its own.</p>
 */
public final class OperationSurface {

    /**
     * The tools whose published kinds ARE operations a cure step may name. Everything else
     * registers its own name and no kinds — a tool is an operation, but a reporting tool's
     * kind enum is a list of questions, not of transformations.
     */
    private static final Set<String> REFACTORING_FRONT_DOORS = Set.of(
        "extract", "inline", "move", "hierarchy", "data", "apply_cleanup",
        "refactor_to_pattern", "generate");

    /** See the class note: harvesting this door's kinds cost a boot. */
    private static final String LIFECYCLE_FRONT_DOOR = "refactoring";

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
        if (LIFECYCLE_FRONT_DOOR.equals(tool.getName())
                || !REFACTORING_FRONT_DOORS.contains(tool.getName())) {
            // The lifecycle door is named EXPLICITLY rather than merely left out of the set.
            // Left out, the exclusion is invisible: re-adding the name would silently
            // republish six operations a second time, which is the boot failure above. It
            // still registers its own NAME as an operation, which publish() does anyway.
            return List.of();
        }
        return tool.publishedKinds();
    }
}
