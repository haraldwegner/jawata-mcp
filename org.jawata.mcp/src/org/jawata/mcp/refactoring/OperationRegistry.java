package org.jawata.mcp.refactoring;

import java.util.Collection;
import java.util.LinkedHashSet;
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
 * ({@code encapsulate_field}) and, for a parametric front door, every KIND it publishes
 * ({@code extract}'s {@code class}, {@code refactor_to_pattern}'s
 * {@code refactor_to_state}). The kinds are read from the tool's own published schema
 * rather than copied, for the reason the front-door honesty test exists: a copy of a
 * published list is a second home for one fact.</p>
 *
 * <h2>A KIND NAME IS NOT UNIQUE, and pretending it was lost information</h2>
 *
 * <p>The first version of this class kept one map from operation to tool and wrote
 * every kind into it. Three front doors publish a kind called {@code method} —
 * {@code extract}, {@code inline} and {@code move} — and two publish {@code class}, so
 * the last tool to register silently won and {@code toolFor("method")} depended on
 * registration order. Nothing failed; a cure naming {@code method} would simply have
 * rendered an invocation against whichever tool happened to be constructed last.</p>
 *
 * <p>So an operation now maps to the SET of tools publishing it, and the three
 * questions a caller can ask are kept apart:</p>
 *
 * <ul>
 *   <li>{@link #has} — is this published at all;</li>
 *   <li>{@link #toolFor} — which tool, when exactly one does, and {@code null} when
 *       several do. Null here means AMBIGUOUS, and a caller must not read it as
 *       absent;</li>
 *   <li>{@link #invocationOf} — how a reader actually CALLS it, which is the only one
 *       most callers want.</li>
 * </ul>
 *
 * <p>The ambiguity is not resolved by guessing: {@link #ambiguous} names it, and a cure
 * table declaring a bare ambiguous kind is refused with the list of tools that publish
 * it. The way to write such a cure is the QUALIFIED form — {@code "move kind=method"} —
 * which is registered alongside the bare name and is unambiguous by construction.</p>
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

    /** operation name → every tool publishing it (usually one; see the class note). */
    private final Map<String, Set<String>> publishedBy = new ConcurrentHashMap<>();

    /** The registry the application wires; tests may construct their own. */
    public static OperationRegistry theRegistry() {
        return DEFAULT;
    }

    /**
     * The qualified spelling of a front door's kind: {@code "<tool> kind=<kind>"}. It is
     * how an ambiguous kind is named unambiguously, and it is what a reader types.
     */
    public static String qualify(String toolName, String kind) {
        return toolName + " kind=" + kind;
    }

    /**
     * Record a tool and every kind it publishes. Idempotent: re-registering the same
     * operation from the same tool is a no-op, which keeps a test that builds a second
     * registry over the same tools from tripping over itself.
     */
    public void register(String toolName, Collection<String> kinds) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        publish(toolName, toolName);
        if (kinds == null) {
            return;
        }
        for (String kind : kinds) {
            if (kind != null && !kind.isBlank()) {
                publish(kind, toolName);
                // The unambiguous spelling, always available even when the bare kind is
                // shared. Registering it here rather than composing it at the point of
                // use means a cure table can declare it and be validated against it.
                publish(qualify(toolName, kind), toolName);
            }
        }
    }

    private void publish(String operation, String toolName) {
        publishedBy.computeIfAbsent(operation, k -> new LinkedHashSet<>()).add(toolName);
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

    /**
     * The tool publishing an operation — {@code null} when nothing does AND when several
     * do. Ask {@link #ambiguous} to tell those apart; most callers want
     * {@link #invocationOf} instead.
     */
    public String toolFor(String operation) {
        Set<String> tools = operation == null ? null : publishedBy.get(operation);
        return tools != null && tools.size() == 1 ? tools.iterator().next() : null;
    }

    /** Every tool publishing this operation, in registration order; empty when none. */
    public Set<String> toolsFor(String operation) {
        Set<String> tools = operation == null ? null : publishedBy.get(operation);
        return tools == null ? Set.of() : Set.copyOf(tools);
    }

    /** True when more than one tool publishes this name, so a bare mention is unclear. */
    public boolean ambiguous(String operation) {
        Set<String> tools = operation == null ? null : publishedBy.get(operation);
        return tools != null && tools.size() > 1;
    }

    /**
     * HOW A READER ACTUALLY CALLS IT.
     *
     * <p>An operation that IS a tool is called by its own name. A kind is called through
     * its front door, {@code move kind=method}. An operation nothing publishes is
     * returned unchanged — this renders text, and inventing a front door for an unknown
     * name would be the confident wrong answer the class note warns about. An ambiguous
     * bare kind is likewise returned unchanged, because there is no single right answer
     * and the cure table should have declared the qualified form.</p>
     */
    public String invocationOf(String operation) {
        String tool = toolFor(operation);
        if (tool == null || tool.equals(operation)) {
            return operation;
        }
        // Already qualified (the "<tool> kind=<kind>" key) — leave it alone.
        return operation.contains(" kind=") ? operation : qualify(tool, operation);
    }

    /** Empty it. For tests that need a registry with known contents. */
    public void clear() {
        publishedBy.clear();
    }
}
