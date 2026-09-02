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
 * <h2>Step validation is NOT here yet, and that is deliberate</h2>
 *
 * <p>Checking each step against the {@link OperationRegistry} sounds free and is not:
 * the registry is process-wide and fills as tools register, so a check running in a
 * partially-wired process refuses recipes that are perfectly valid. It earns its place
 * when recipes are declared from outside this codebase; while the only recipe is built
 * by the tool that runs it, the check would fail on wiring order and prove nothing
 * about what executes.</p>
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
