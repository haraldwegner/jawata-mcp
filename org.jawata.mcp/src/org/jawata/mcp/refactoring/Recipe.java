package org.jawata.mcp.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.core.IJdtService;

import java.util.ArrayList;
import java.util.List;

/**
 * A REFACTORING EXPRESSED AS DATA: an ordered list of operations the product already
 * publishes, run one after another with a parity check between each.
 *
 * <p>Sprint 28d-rescue, seam P2. {@link RecipeEngine} has always been able to run a
 * dependent sequence — apply-reparse, a per-step undo, atomic rollback — but it took
 * an opaque list of closures, and exactly one caller ever built them
 * ({@code ComposeMethodTool}). Eight of this sprint's rows are chains of operations we
 * already have, and writing eight more closure-builders would have been eight copies
 * of one idea. A recipe names its steps instead.</p>
 *
 * <h2>The dependency direction, which decides the shape</h2>
 *
 * <p>Nothing in {@code refactoring} may depend on {@code tools}. A recipe therefore
 * cannot hold tools, and cannot resolve an operation name by itself. It takes a
 * {@link StepBuilder} — supplied by the tools layer, which is the layer that knows how
 * to turn (operation, arguments) into a {@link Change}. That keeps the arrow pointing
 * one way and makes the resolution testable with a builder that resolves nothing.</p>
 *
 * <h2>Steps are validated before anything is applied</h2>
 *
 * <p>A recipe naming an operation nothing publishes is a defect in the recipe, not a
 * runtime surprise: {@link #validate} refuses it up front, so no partial application
 * happens and no rollback is needed to discover it.</p>
 */
public record Recipe(String name, List<RecipeStep> steps) {

    public Recipe {
        steps = List.copyOf(steps);
    }

    /**
     * Turns one named operation and its arguments into a change, against the workspace
     * as it stands when the step runs. Supplied by the tools layer.
     */
    @FunctionalInterface
    public interface StepBuilder {
        /** @return the change to perform, or null to abort the recipe. */
        Change build(String operation, JsonNode arguments) throws Exception;
    }

    /**
     * Every step names an operation the registry publishes.
     *
     * @return the steps naming nothing published, in order; empty when the recipe is
     *         runnable. Returned rather than thrown so a caller can name all of them
     *         at once instead of one per run.
     */
    public List<String> validate(OperationRegistry registry) {
        List<String> unknown = new ArrayList<>();
        for (RecipeStep step : steps) {
            if (!registry.has(step.operation())) {
                unknown.add(step.operation());
            }
        }
        return List.copyOf(unknown);
    }

    /**
     * Run the steps in order through {@link RecipeEngine}: each is built against the
     * workspace the previous step left, and the whole recipe rolls back if any fails.
     */
    public RecipeEngine.Result run(StepBuilder builder, IJdtService service) {
        List<RecipeEngine.Step> engineSteps = new ArrayList<>();
        for (RecipeStep step : steps) {
            engineSteps.add(() -> builder.build(step.operation(), step.arguments()));
        }
        return RecipeEngine.run(name, engineSteps, service);
    }
}
