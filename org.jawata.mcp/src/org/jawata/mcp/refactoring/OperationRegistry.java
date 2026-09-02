package org.jawata.mcp.refactoring;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EVERY OPERATION THE PRODUCT PUBLISHES, and the only thing a cure step may name.
 *
 * <p>Sprint 28d-rescue, seam P1. Before this class, {@code CureCatalog} validated its
 * declared steps against {@code RefactorToPatternTool.publishedKinds()} — one front
 * door's list. That was correct while every runnable cure happened to be a pattern
 * kind, and it silently forbade the rest: a cure naming {@code move_method} or
 * {@code encapsulate_field} could not be declared at all, so four operations that
 * already shipped were unreachable from the smells whose own prose named them.</p>
 *
 * <h2>Why registration happens at the tool registry, not in eight constructors</h2>
 *
 * <p>{@link org.jawata.mcp.tools.ToolRegistry#register} already sees every tool the
 * application publishes, exactly once. Registering there means the operation list
 * cannot drift from the tool list: a tool that exists is registered, and a tool that
 * is removed takes its kinds with it. Eight constructors doing it themselves would be
 * eight places to forget.</p>
 *
 * <h2>What counts as an operation</h2>
 *
 * <p>Kinds are harvested only from the tools whose kinds are transformations — the
 * list is {@code ToolRegistry}'s own. A reporting tool's {@code kind} enum lists
 * questions rather than operations, and admitting them would let a cure step name
 * {@code god_class} and be called runnable.</p>
 *
 * <p>Both halves, because a cure step may name either: the TOOL NAME
 * ({@code move_method}, {@code encapsulate_field}) and, for a parametric front door,
 * every KIND it publishes ({@code extract}'s {@code class}, {@code refactor_to_pattern}'s
 * {@code refactor_to_state}). The kinds are read from the tool's own published schema
 * rather than copied, for the reason the front-door honesty test exists: a copy of a
 * published list is a second home for one fact.</p>
 *
 * <h2>The refusal moved from class-load to wiring, deliberately</h2>
 *
 * <p>{@code CureCatalog}'s step check used to run in a static initializer. It cannot
 * stay there: the registry is populated by tools, and a class-load that happens before
 * the tools exist would see an empty registry and refuse every step — a boot failure
 * caused by ordering rather than by a wrong table. So the check is now
 * {@code CureCatalog.validateAgainst}, called once from the application after the tools
 * are registered. It still THROWS, and boot still fails loudly on a table naming a step
 * nothing backs; it simply asks the question at the first moment the answer is true.</p>
 */
public final class OperationRegistry {

    private static final OperationRegistry DEFAULT = new OperationRegistry();

    /** operation name → the tool that publishes it, kept for the refusal's message. */
    private final Map<String, String> publishedBy = new ConcurrentHashMap<>();

    /** The registry the application wires; tests may construct their own. */
    public static OperationRegistry theRegistry() {
        return DEFAULT;
    }

    /**
     * Record a tool and every kind it publishes. Idempotent per name: re-registering
     * the same operation from the same tool is a no-op, which keeps a test that builds
     * a second registry over the same tools from tripping over itself.
     */
    public void register(String toolName, Collection<String> kinds) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        publishedBy.put(toolName, toolName);
        if (kinds == null) {
            return;
        }
        for (String kind : kinds) {
            if (kind != null && !kind.isBlank()) {
                publishedBy.put(kind, toolName);
            }
        }
    }

    /**
     * Whether anything has registered yet.
     *
     * <p>The distinction matters and its absence was a defect: an EMPTY registry means
     * nothing has been wired, which is not the same claim as "this operation does not
     * exist". A reader that treats the first as the second answers a question it could
     * not look up — the shape that turns a missing answer into a confident wrong one.
     * Callers that can be reached before wiring ask this first.</p>
     */
    public boolean isWired() {
        return !publishedBy.isEmpty();
    }

    /** Whether an operation of this name is published by anything. */
    public boolean has(String operation) {
        return operation != null && publishedBy.containsKey(operation);
    }

    /** Every published operation name — the set a cure step is validated against. */
    public Set<String> all() {
        return Set.copyOf(publishedBy.keySet());
    }

    /** The tool publishing an operation, or null when nothing does. */
    public String toolFor(String operation) {
        return operation == null ? null : publishedBy.get(operation);
    }

    /** Empty it. For tests that need a registry with known contents. */
    public void clear() {
        publishedBy.clear();
    }
}
