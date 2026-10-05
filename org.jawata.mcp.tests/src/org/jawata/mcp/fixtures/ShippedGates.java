package org.jawata.mcp.fixtures;

import java.util.List;

import org.jawata.mcp.refactoring.OperationRegistry;
import org.jawata.mcp.tools.CompileWorkspaceTool;
import org.jawata.mcp.tools.GetDiagnosticsTool;
import org.jawata.mcp.tools.RunTestsTool;
import org.jawata.mcp.tools.Tool;

/**
 * The shipped GATES, asked of the tools themselves — the fixture the enforcement lane's
 * tests need after Sprint 28g S9a M1 (jawata-mcp).
 *
 * <h2>Why a fixture exists at all</h2>
 *
 * <p>Gate-ness used to be a {@code Set.of("compile_workspace", "get_diagnostics",
 * "run_tests")} literal inside each of {@code EventTap}, {@code ServerChecks} and
 * {@code ToolExperienceRecorder}, so a test that constructed one of them inherited the
 * answer for free. The tools declare it now, through {@link Tool#isGate()}, and the registry
 * answers — which means a lane test has to WIRE the tools it names, exactly as the
 * application does at registration. {@code EventTapTest} already states that rule, in its
 * own words, about mechanical-ness: <em>"a test that exercises the tap has to wire the tool
 * it names, exactly as the application does at registration."</em></p>
 *
 * <p>The checks fail CLOSED on an empty registry — a seat outcome reads as ungated — so the
 * cost of not wiring is a red test rather than a silent pass. That direction is deliberate,
 * and it is why {@code ArchitectGate} refuses to take its registry from a global: <em>"hiding
 * that behind a singleton would leave the gate's own tests passing over an empty registry,
 * which is a differently-shaped version of the same blindness."</em></p>
 *
 * <h2>It spells no gate name, and that is the point</h2>
 *
 * <p>A fixture listing the three names would be the fifth copy of the list M1 deleted, and
 * would go stale the first time a gate is renamed — the exact defect. It constructs the three
 * shipped tools and asks each one, so a tool that stops declaring itself a gate takes its
 * gate-ness out of every test that reads this.</p>
 */
public final class ShippedGates {

    private ShippedGates() {
    }

    /**
     * The three shipped gate tools. Constructed with a null service supplier: nothing here
     * executes them, and {@link Tool#isGate()} and {@link Tool#getName()} need no service.
     */
    public static List<Tool> tools() {
        return List.of(
            new CompileWorkspaceTool(() -> null),
            new GetDiagnosticsTool(() -> null),
            new RunTestsTool(() -> null));
    }

    /**
     * A fresh registry holding the shipped gates, populated the way the application
     * populates it — through the same {@code register} call {@code OperationSurface} makes.
     *
     * <p>Fresh rather than the process singleton, so a lane test neither depends on what
     * another class left behind nor has to put anything back.</p>
     */
    public static OperationRegistry registry() {
        OperationRegistry registry = new OperationRegistry();
        into(registry);
        return registry;
    }

    /** Register the shipped gates into an existing registry, leaving the rest of it alone. */
    public static void into(OperationRegistry registry) {
        for (Tool tool : tools()) {
            registry.register(tool.getName(), tool.publishedKinds(), tool.isMechanical(),
                tool.isStructural(), tool.isGate(), tool.structuralKinds(), "kind");
        }
    }
}
