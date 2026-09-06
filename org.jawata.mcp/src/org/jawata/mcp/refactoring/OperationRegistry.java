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

    /**
     * What each operation IS, as its own tool declares it.
     *
     * <p>Two controls used to answer these from {@code Set.of(...)} literals of tool
     * NAMES, in two other packages: the coverage advisory's exemption list and the
     * architect-involvement gate's structural list. Stage 1's fold retired six names and
     * walked past both — the gate stopped firing for pull-up, push-down and the method
     * move, and the advisory started asking for tests after a field encapsulation. Both
     * went quiet with nothing failing, because a list in another package cannot know
     * that a name it holds has stopped existing.</p>
     *
     * <p>Here the classification arrives WITH the registration, so an operation that
     * exists is classified and an operation that is retired takes its classification
     * with it. There is no list to update and nothing to forget.</p>
     */
    private final Set<String> mechanical = ConcurrentHashMap.newKeySet();
    private final Set<String> structural = ConcurrentHashMap.newKeySet();

    /**
     * tool name → THE PARAMETER THAT SELECTS ITS KINDS, as the door itself declares it.
     *
     * <p>The registry's KEY stays {@code "<tool> kind=<kind>"} for every door, because a key
     * only has to be unique and a cure table already declares it that way. What varies is how
     * a reader is told to CALL it: eight doors select on {@code kind} and {@code hierarchy}
     * selects on {@code direction}, so rendering {@code "hierarchy kind=up"} produced an
     * instruction the product itself refuses — measured across all seven of that door's
     * operations. Holding the discriminator here is what lets {@link #invocationOf} spell the
     * call the way the door will actually accept it.</p>
     *
     * <p>It arrives WITH the registration for the same reason the classification above does:
     * a door that is retired takes its discriminator with it, and there is no second table to
     * go stale.</p>
     */
    private final Map<String, String> discriminatorOf = new ConcurrentHashMap<>();

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
        register(toolName, kinds, false, false, Set.of(), "kind");
    }

    /**
     * Record a tool, its kinds, and WHAT THEY ARE.
     *
     * @param isMechanical    every operation of this tool preserves behaviour
     * @param isStructural    every operation of this tool changes a signature or hierarchy
     * @param structuralKinds the kinds that are structural when the tool as a whole is not
     * @param discriminator   the parameter this tool selects its kinds on — {@code kind} for
     *                        every door but {@code hierarchy}, which uses {@code direction}.
     *                        It changes how {@link #invocationOf} SPELLS a call and nothing
     *                        about the keys stored here; a tool publishing no kinds has
     *                        nothing to select, so its value is never read
     */
    public void register(String toolName, Collection<String> kinds, boolean isMechanical,
                         boolean isStructural, Set<String> structuralKinds, String discriminator) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        publish(toolName, toolName);
        classify(toolName, isMechanical, isStructural);
        if (discriminator != null && !discriminator.isBlank()) {
            discriminatorOf.put(toolName, discriminator);
        }
        if (kinds == null) {
            return;
        }
        Set<String> structuralOnes = structuralKinds == null ? Set.of() : structuralKinds;
        for (String kind : kinds) {
            if (kind == null || kind.isBlank()) {
                continue;
            }
            publish(kind, toolName);
            // The unambiguous spelling, always available even when the bare kind is
            // shared. Registering it here rather than composing it at the point of
            // use means a cure table can declare it and be validated against it.
            String qualified = qualify(toolName, kind);
            publish(qualified, toolName);
            boolean kindIsStructural = isStructural || structuralOnes.contains(kind);
            // The QUALIFIED form only, for structural-ness. A bare `method` is published
            // by three tools and means something different in each; classifying the bare
            // name would make `extract kind=method` structural because `move kind=method`
            // is, which is the ambiguity this class exists to refuse.
            classify(qualified, isMechanical, kindIsStructural);
            if (isMechanical) {
                mechanical.add(kind);
            }
        }
    }

    private void classify(String operation, boolean isMechanical, boolean isStructural) {
        if (isMechanical) {
            mechanical.add(operation);
        }
        if (isStructural) {
            structural.add(operation);
        }
    }

    /**
     * Is this operation behaviour-preserving — a refactoring rather than new code?
     *
     * <p>False for an operation nothing registered, which is the same answer as "not a
     * refactoring" and is the safe direction: the coverage advisory then ASKS for a test
     * rather than silently exempting something it knows nothing about.</p>
     */
    public boolean isMechanical(String operation) {
        return operation != null && mechanical.contains(operation);
    }

    /** Does this operation change a signature or a hierarchy? */
    public boolean isStructural(String operation) {
        return operation != null && structural.contains(operation);
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
     *
     * <p><b>IT SPELLS THE DOOR'S OWN SELECTOR, and until S8b step 6 it did not.</b> A key
     * that already read {@code "<tool> kind=<kind>"} was returned untouched, which is right
     * for eight doors and wrong for {@code hierarchy} — it selects on {@code direction}, so
     * every address the product printed for its seven operations was an instruction the
     * product refuses with {@code "direction is required"}. The key is unchanged; only the
     * rendering asks {@link #discriminatorOf} how the door is actually called.</p>
     */
    public String invocationOf(String operation) {
        String tool = toolFor(operation);
        if (tool == null || tool.equals(operation)) {
            return operation;
        }
        // The stored key is always "<tool> kind=<kind>"; strip it back to the kind and
        // re-spell it with the selector the door declared.
        int marker = operation.indexOf(" kind=");
        String kind = marker < 0 ? operation : operation.substring(marker + " kind=".length());
        return tool + " " + discriminatorOf.getOrDefault(tool, "kind") + "=" + kind;
    }

    /** Empty it. For tests that need a registry with known contents. */
    /**
     * Everything this registry holds, as a value that can be handed back to
     * {@link #restore(Snapshot)}.
     *
     * <p>This exists because the default registry is a GLOBAL and tests write to it. Every
     * previous attempt to put it back was a hand-written copy of what the application
     * registers, and each drifted: one restored the KEYS and destroyed the attribution, its
     * replacement restored three front doors out of the thirty-nine the application
     * registers — each under a comment claiming the singleton was left as found. A borrow
     * is only safe when the thing borrowed can be returned exactly, so the registry hands
     * out its own state rather than asking a caller to reconstruct it.</p>
     */
    public record Snapshot(Map<String, Set<String>> publishedBy, Set<String> mechanical,
                           Set<String> structural) {
    }

    /** Capture the whole state, deeply enough that later writes cannot reach it. */
    public Snapshot snapshot() {
        Map<String, Set<String>> published = new java.util.LinkedHashMap<>();
        publishedBy.forEach((key, value) -> published.put(key, new java.util.LinkedHashSet<>(value)));
        return new Snapshot(published, new java.util.LinkedHashSet<>(mechanical),
            new java.util.LinkedHashSet<>(structural));
    }

    /** Put a captured state back, discarding whatever is there now. */
    public void restore(Snapshot snapshot) {
        clear();
        snapshot.publishedBy().forEach((key, value) -> {
            Set<String> tools = ConcurrentHashMap.newKeySet();
            tools.addAll(value);
            publishedBy.put(key, tools);
        });
        mechanical.addAll(snapshot.mechanical());
        structural.addAll(snapshot.structural());
    }

    public void clear() {
        publishedBy.clear();
        mechanical.clear();
        structural.clear();
    }
}
