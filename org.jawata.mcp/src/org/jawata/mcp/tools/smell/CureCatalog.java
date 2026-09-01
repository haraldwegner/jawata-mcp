package org.jawata.mcp.tools.smell;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE DECLARED CURES — which design answers which smell, stated once.
 *
 * <p>This is a table of LOOKUP KEYS, and it is deliberately not a table of
 * addresses. Each entry names the {@code operation} a catalogue row carries
 * ({@code design:<slug>}); the address that row lives at is read off the row by
 * {@link org.jawata.mcp.knowledge.CatalogueAddresses}. The split is the whole
 * point of Sprint 28d's arch step 4: a key that matches nothing yields NO cure,
 * whereas an address built from the same slug would yield a plausible one.</p>
 *
 * <p><b>The keys are LITERALS.</b> Not {@code "design:" + slug}, not
 * {@code slug.replace('_','-')} — every key is written out. A key assembled from
 * a naming convention is the composition this design forbids, one level up: it
 * would silently follow the fork's slug spelling and silently mis-spell the
 * samples' ({@code compose_method} the plan kind versus {@code compose-method}
 * the slug are one letter apart and neither is derivable from the other).</p>
 *
 * <h2>Why the recipe may be null</h2>
 * <p>A {@code recipe} is a runnable, parity-gated plan kind. Four of this
 * sprint's five principle kinds have no such transform — {@code cqs},
 * {@code coupling}, {@code composition_over_inheritance} and
 * {@code encapsulation} are cured by a design decision, not by an automated
 * rewrite. Their cure is still an ADDRESS a reader can open, so they belong
 * here with a null recipe rather than being left out and reading as "no cure
 * known".
 *
 * <h2>Sprint 28d Stage 6 / S7 — this is now the ONLY cure table</h2>
 *
 * <p>It previously said that {@code RecipeCatalog} "keeps its own, narrower
 * answer: what can be RUN. The two are different questions and this table does
 * not overwrite it." <b>That was false by the time it was written.</b> Every
 * mapping the recipe table held is present here with the same plan kind, and
 * {@code OcpCure} was not a peer table at all — the recipe table delegated the
 * two churn kinds to it and handled the rest, so it was a subset wrapped by a
 * view. What "can be RUN" is not a second question; it is THIS table filtered to
 * the entries whose recipe is non-null, which is what {@link #recipesFor} now
 * returns. Two tables answering one question is how the answers drift, and a
 * comment asserting they cannot is not a mechanism.</p>
 *
 * <h2>Why a cure may name a design, a recipe, or both</h2>
 *
 * <p>A cure is the route from a smell to a target state, and targets come in two
 * kinds. Some name a DESIGN to reach — become a State machine — and that is the
 * {@code operation}, an address a reader opens. Others are DEFINITIONAL: the end
 * state is the cure's own completion, as {@code inline_singleton} ends with the
 * singleton inlined and {@code compose_method} with the method composed. Those
 * need no separate target and their {@code operation} is a convenience for the
 * reader rather than a destination they must reach. So neither half is required:
 * a recipe with no design still runs, a design with no recipe still reads, and
 * an entry needs at least one to be worth declaring.</p>
 */
public final class CureCatalog {

    /**
     * One declared cure: the plan kind that performs it (or null when nothing
     * automates it), and the catalogue key its design lives under.
     */
    public record Cure(String recipe, String operation) {
    }

    /** The three designs that close a modification axis — OCP's answer, shared by its traces. */
    private static final List<Cure> OPEN_THE_AXIS = List.of(
        new Cure("refactor_to_state", "design:state"),
        new Cure("refactor_to_command_dispatcher", "design:command"),
        new Cure("form_template_method", "design:template-method"));

    private static final Map<String, List<Cure>> BY_KIND = byKind();

    private static Map<String, List<Cure>> byKind() {
        Map<String, List<Cure>> m = new LinkedHashMap<>();

        // --- the five principle kinds Sprint 28d adds ------------------------
        m.put("ocp", OPEN_THE_AXIS);
        m.put("cqs", List.of(
            new Cure(null, "design:command-query-responsibility-segregation")));
        m.put("coupling", List.of(
            new Cure(null, "design:dependency-injection"),
            new Cure(null, "design:mediator")));
        m.put("composition_over_inheritance", List.of(
            new Cure(null, "design:delegation"),
            new Cure(null, "design:strategy")));
        m.put("encapsulation", List.of(
            new Cure(null, "design:private-class-data")));

        // --- the traces and smells that already had recipes ------------------
        // Same designs as `ocp` because they ARE its traces: OcpDetector relabels
        // a trace finding, so the trace's cure and the principle's must be one
        // table or they drift the moment either is edited.
        m.put("divergent_change", OPEN_THE_AXIS);
        m.put("shotgun_surgery", OPEN_THE_AXIS);
        // The detector's own sentence says "Consider Replace Conditional with
        // Polymorphism", and Sprint 28d BUILT that operation — for this smell.
        // The table was never told, so until v4.0.2 a reader got one refactoring
        // named in the prose and a different one offered as runnable, in the same
        // message.
        //
        // REPLACED, not added, and the tier model is why. It derives PERFORM from
        // ONE route whose steps exist, and ADVISE from several with nothing to
        // choose between them. Keeping State alongside would have been defensible
        // as design — a switch on an int STATE field really is a State candidate —
        // and it would have downgraded this kind from a runnable instruction to
        // advice. The first attempt did exactly that and CureTierTest caught it:
        // expected PERFORM, got ADVISE. A second route is not free; it costs the
        // tier, and here the cost buys nothing the prose asked for.
        //
        // State stays reachable: OPEN_THE_AXIS routes ocp, divergent_change and
        // shotgun_surgery to it, so nothing lost an address.
        m.put("switch_statements", List.of(
            new Cure("replace_conditional_with_polymorphism", "design:strategy")));
        // UNCHANGED, deliberately. Sprint 28d's other new operation,
        // replace_constructor_with_factory, is referenced by no cure — and the
        // first attempt at this fix bolted it on here to satisfy a test asserting
        // that every shipped operation is reachable from some smell. That test was
        // wrong and is gone: no detector's prose asks for a factory, so the entry
        // would have served the check rather than a reader, and the second route
        // would have cost this kind its PERFORM tier as well.
        //
        // The honest state is that the operation exists with no smell recommending
        // it. That is recorded as a finding, not papered over with a row.
        m.put("type_code", List.of(
            new Cure("replace_type_code_with_class", "design:type-object")));
        m.put("singleton", List.of(
            new Cure("inline_singleton", "design:singleton")));
        m.put("long_method", List.of(
            new Cure("compose_method", "design:compose-method")));

        // Stage 11a — the table's two invariants are UNCONSTRUCTIBLE rather than
        // merely detectable (the same move C6 made for namespace collision):
        //   1. the pair (kind, operation) is the ENTRY IDENTITY — declared at
        //      most once, or two rows claim one route set;
        //   2. every recipe names a kind the front door PUBLISHES — a step no
        //      registered operation backs would read as runnable and refuse at
        //      the front door, which is the drift CureTier's missing-step branch
        //      exists to surface at lookup time. Here it cannot even load.
        // The registry is the tool's own list through its accessor, never a copy.
        java.util.Set<String> published = new java.util.HashSet<>(
            org.jawata.mcp.tools.RefactorToPatternTool.publishedKinds());
        for (Map.Entry<String, List<Cure>> e : m.entrySet()) {
            java.util.Set<String> ops = new java.util.HashSet<>();
            for (Cure c : e.getValue()) {
                if (!ops.add(c.operation())) {
                    throw new IllegalStateException("CureCatalog: kind '" + e.getKey()
                        + "' declares operation '" + c.operation() + "' twice — the pair"
                        + " is the entry identity");
                }
                if (c.recipe() != null && !published.contains(c.recipe())) {
                    throw new IllegalStateException("CureCatalog: kind '" + e.getKey()
                        + "' declares step '" + c.recipe() + "' and no registered"
                        + " operation backs it");
                }
            }
        }
        return Map.copyOf(m);
    }

    private CureCatalog() {
    }

    /** The declared cures for a smell kind, best-first; empty when none is declared. */
    public static List<Cure> curesFor(String kind) {
        if (kind == null) {
            return List.of();
        }
        return BY_KIND.getOrDefault(kind, List.of());
    }

    /**
     * The RUNNABLE plan kinds that cure {@code kind}, best-first; empty if none.
     *
     * <p>This is {@link #curesFor} filtered to entries that have a recipe — the
     * question {@code RecipeCatalog} used to answer from its own copy of the same
     * mappings. A view, not a table: there is nothing here to keep in step,
     * because there is nothing here to disagree with.</p>
     */
    public static List<String> recipesFor(String kind) {
        List<String> out = new java.util.ArrayList<>();
        for (Cure c : curesFor(kind)) {
            if (c.recipe() != null) {
                out.add(c.recipe());
            }
        }
        return List.copyOf(out);
    }

    /**
     * The OCP-cure pointer the churn detectors append to their messages.
     *
     * <p><b>DERIVED from the table, not written beside it.</b> It used to be a
     * constant that spelled out {@code refactor_to_state /
     * refactor_to_command_dispatcher / form_template_method} — which is exactly
     * {@code recipesFor("divergent_change")}. A hand-written copy of a list the
     * table already holds is a second home for one fact, and the copy is the one
     * that goes stale: adding a fourth design to {@code OPEN_THE_AXIS} would have
     * changed what the tool DOES while this sentence went on describing three.
     * Now the sentence cannot be wrong about the table, because it is read from
     * it. The wording is byte-identical to what it replaced.</p>
     *
     * <p>It carries no ADDRESS, and cannot: it names plan kinds, with nothing
     * behind them checked. That is why it is a pointer and the resolved cure —
     * read off a catalogue row by {@link CureLookup} — is the answer.</p>
     */
    public static String ocpHint() {
        return OCP_LEAD + " — refactor_to_pattern "
            + "kind=" + String.join(" / ", recipesFor("divergent_change")) + " "
            + "(or refactoring(action=plan, kind=<same>) then apply_plan for a parity-gated run).";
    }

    /**
     * The lead sentence both OCP messages open with.
     *
     * <p>Two branches emit it: this one when a cure was RESOLVED from a catalogue
     * row (the address follows), and {@link #ocpHint()} when nothing was declared
     * or resolved (plan kinds follow, with no address). They are different
     * messages for different situations — not one fact stated twice — but the
     * opening sentence was written out in both, so a reword would have changed
     * one and left the other saying something else about the same principle.</p>
     */
    public static String ocpLeadResolved() {
        return OCP_LEAD + ".";
    }

    private static final String OCP_LEAD =
        " OCP cure: introduce an abstraction at the modification axis";

    /**
     * Every DISTINCT cure key declared anywhere in this table — the set the
     * re-resolution check sweeps.
     *
     * <p>Distinct rather than per-kind, because the same design cures several
     * kinds and an audit counting {@code design:state} three times would report
     * a drift of three for one moved row.</p>
     */
    public static List<String> declaredOperations() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (List<Cure> cures : BY_KIND.values()) {
            for (Cure c : cures) {
                out.add(c.operation());
            }
        }
        return List.copyOf(out);
    }
}
