package org.jawata.mcp.fixtures;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.jawata.mcp.ProjectLoadingState;
import org.jawata.mcp.ShippedTools;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ToolRegistry;

/**
 * The shipped tools, built the way the application builds them but with nothing behind the wiring
 * (Sprint 28g C9a-1). A test that wants to know which tools ship asks this instead of keeping a
 * list of its own, which is how the lists drifted.
 *
 * <p>Nothing is registered: {@code ToolRegistry.register} publishes into a process-wide operation
 * registry, so a test that only wants names must not register.</p>
 */
public final class InertShippedTools {

    private InertShippedTools() {
    }

    /** The wiring an application that has booted would give, with nothing behind it. */
    public static ShippedTools.Wiring wiring(ToolRegistry registry) {
        return new ShippedTools.Wiring(
            () -> null,
            service -> { },
            () -> false,
            () -> 0,
            () -> ProjectLoadingState.NOT_LOADED,
            () -> null,
            () -> Path.of(System.getProperty("java.io.tmpdir")),
            registry::inFlight,
            new RefactoringChangeCache(),
            null,
            () -> null,
            () -> null,
            new RuntimeSessionRegistry(),
            () -> null,
            () -> registry,
            () -> null,
            List::of);
    }

    /** Every tool the product ships, unregistered. */
    public static List<Tool> tools() {
        return ShippedTools.build(wiring(new ToolRegistry())).tools();
    }

    /** The names of every tool the product ships. */
    public static Set<String> names() {
        Set<String> names = new TreeSet<>();
        for (Tool tool : tools()) {
            names.add(tool.getName());
        }
        return names;
    }
}
