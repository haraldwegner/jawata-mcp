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
 * {@code coupling} and {@code composition_over_inheritance} are cured by a design
 * decision, not by an automated rewrite. ({@code encapsulation} was the fourth until
 * Sprint 28d-rescue routed it to {@code data}, which had shipped for
 * sprints and which this table could not name.) Their cure is still an ADDRESS a reader can open, so they belong
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
        // Row 60 is the LOCAL half of this smell and the only runnable one today:
        // a method that builds its answer in a mutable local stops doing so. It
        // carries the same design address the advice-only entry carried, because
        // the address IS the entry identity here — a second entry repeating it
        // is refused at load time, which is how this was caught. The cross-file
        // half is row 61, splitting a method whose answer AND whose mutation both
        // have callers; when it ships it is a DIFFERENT address, not a duplicate
        // of this one.
        m.put("cqs", List.of(
            new Cure("apply_cleanup kind=return_modified_value",
                "design:command-query-responsibility-segregation")));
        m.put("coupling", List.of(
            new Cure(null, "design:dependency-injection"),
            new Cure(null, "design:mediator")));
        m.put("composition_over_inheritance", List.of(
            new Cure(null, "design:delegation"),
            new Cure(null, "design:strategy")));
        // encapsulation's runnable route is declared with the other three below,
        // where the reason they were unreachable is written down once.

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

        // --- Sprint 28d-rescue, S0: the four fixes that already SHIPPED and could not
        // be offered. Not new operations — `move_method`, `extract` and
        // `data` (renamed from `encapsulate_field` in stage 1) have been in the product for sprints. They were
        // unreachable because this table could only name a `refactor_to_pattern` kind,
        // so the detector whose own prose says "move the method" offered nothing
        // runnable. feature_envy alone reports 713 findings on this repository.
        //
        // Each is ONE route, so CureTier derives PERFORM — but the gate must not
        // demand it: the rule derives, and a second route arriving later would
        // correctly make it ADVISE.
        //
        // THREE OF THE FOUR CARRY NO DESIGN ADDRESS, and that is the honest state
        // rather than an omission. This record's own contract says "a recipe with no
        // design still runs": the operation field points at a catalogue row a reader
        // can OPEN, and the catalogue holds no row for "move the method to the data"
        // or "split this class" — they are refactorings, not patterns. Writing
        // `design:feature-envy` invented an address: it resolved to nothing, and the
        // finding then rendered NO CATALOGUE ADDRESS, which is worse than offering the
        // runnable fix with no further reading. Encapsulation keeps the address it
        // already had, because that one exists.
        // QUALIFIED, and it has to be. `move_method` was folded into `move kind=method`
        // in stage 1, and the bare kind name `method` is published by `extract` and
        // `inline` as well — so a bare mention would name three different operations
        // and the registry refuses it. The qualified spelling is what a reader types.
        m.put("feature_envy", List.of(
            new Cure("move kind=method", null)));
        // QUALIFIED, for the reason the fold pointers are: `extract` publishes seven
        // kinds, and naming the bare front door leaves a reader to guess which one. The
        // architecture says extract(class) for both of these, and the registry publishes
        // that spelling as an unambiguous key.
        m.put("god_class", List.of(
            new Cure("extract kind=class", null)));
        m.put("temporary_field", List.of(
            new Cure("extract kind=class", null)));
        m.put("encapsulation", List.of(
            new Cure("data", "design:private-class-data")));

        // --- Sprint 28d-rescue, S8: the six kinds stage 8 adds. Batched here rather
        // than written as each detector landed, because this table has one owner and a
        // lane that edits it while another lane is also editing it is how two routes
        // for one kind get merged into a file nobody reviewed as a whole.
        //
        // FOUR of the six route to something runnable. Two do not, and that is the
        // honest state rather than an omission:
        //
        //   loops — WAS unroutable, and is not any more. Stage 3 built row 50, so the
        //     cure now names the operation that performs it. Leaving this unrouted after
        //     the operation shipped is the built-but-unwired state this sprint exists to
        //     remove: the finding would go on describing a fix the product performs and
        //     not offering it.
        //   alternative_classes — the cure is Rename Function until the two agree, then
        //     Extract Superclass. That is a JUDGEMENT about which names win, made once
        //     per pair by a person; `extract kind=superclass` is the last step of it and
        //     not the cure, and offering it as the cure would skip the renaming that
        //     makes the two substitutable in the first place.
        m.put("global_data", List.of(
            new Cure("data", "design:private-class-data")));
        m.put("mutable_data", List.of(
            new Cure("data", "design:private-class-data")));
        m.put("data_class", List.of(
            new Cure(null, "design:value-object")));
        // QUALIFIED, because `apply_cleanup` publishes many kinds and a bare front-door
        // name leaves a reader to guess which. No catalogue design: a pipeline is a
        // rewrite, not a pattern, and inventing an address for it is the failure the
        // re-resolution sweep caught on this very table one commit ago.
        m.put("loops", List.of(
            new Cure("apply_cleanup kind=loop_to_pipeline", null)));
        // The clearest route in this table: the finding says a private member is never
        // used, and the cure removes exactly that member. Qualified for the same reason
        // as the pipeline above, and with no catalogue design for the same reason too —
        // deleting unreachable code is a removal, not a pattern, and `design:dead-code`
        // is not a row that exists.
        m.put("unused", List.of(
            new Cure("apply_cleanup kind=remove_dead_code", null)));
        // commented_out_code has NO ENTRY AT ALL, and that is the honest state rather
        // than an omission. It has no runnable cure by ruling — commented-out code may
        // still carry meaning, so nothing may offer to remove it — and no catalogue
        // design either: reading the block and deciding is not a pattern, and
        // `design:comment` is not a row that exists. Writing it anyway is the invented
        // address this table already carries a warning about, and the re-resolution
        // sweep caught it within one run. The cure a reader needs is in the finding's
        // own message, which says to read it and then delete it or write down why it
        // stays.

        // INVARIANT 1, checkable here because it needs nothing outside the table:
        // the pair (kind, operation) is the ENTRY IDENTITY — declared at most once,
        // or two rows claim one route set.
        for (Map.Entry<String, List<Cure>> e : m.entrySet()) {
            java.util.Set<String> ops = new java.util.HashSet<>();
            for (Cure c : e.getValue()) {
                // A null address is "no design to read", not an identity — two of them
                // under one kind would be two distinct runnable routes, not a duplicate.
                if (c.operation() != null && !ops.add(c.operation())) {
                    throw new IllegalStateException("CureCatalog: kind '" + e.getKey()
                        + "' declares operation '" + c.operation() + "' twice — the pair"
                        + " is the entry identity");
                }
            }
        }
        // INVARIANT 2 — every recipe names a published operation — moved to
        // validateAgainst. See its javadoc for why it cannot live here.
        return Map.copyOf(m);
    }

    /**
     * OPERATIONS THAT SHIP AND THAT NO SMELL NAMES, with the reason for each.
     *
     * <p>The sprint's per-row contract says a row is "routed, or listed as unrouted with
     * the reason". Five of stage 3's six were neither, which reads as an oversight and
     * is not one — but a reader cannot tell those apart from an empty table, which is the
     * whole point of writing it down.</p>
     *
     * <p>This is NOT {@link #ADVICE_ONLY}. Those are refactorings we decline to automate;
     * these are automated and runnable, and simply have no detector whose finding calls
     * for them. That is a gap in the DETECTOR side, recorded here so it is visible from
     * the table a reader already opens.</p>
     */
    private static final Map<String, String> SHIPPED_BUT_UNROUTED = Map.of(
        // The two OLDER than stage 3, found by the guard rather than by the review that
        // prompted it. They have shipped unrouted since Sprint 15 and nobody had said why.
        "apply_cleanup kind=add_final",
        "no detector reports a missing `final`. It is hygiene applied in bulk rather than"
            + " a finding about a place — a detector for it would report thousands of"
            + " rows nobody would read one at a time.",
        "apply_cleanup kind=redundant_modifiers",
        "same shape as add_final: bulk hygiene, not a finding. JDT's own engine performs"
            + " it and the natural way to reach it is the sweep, not a report.",
        "apply_cleanup kind=guard_clauses",
        "no detector reports nested conditionals. `long_method` is the nearest, and it"
            + " already has one route — compose_method — which the tier model turns to"
            + " ADVISE the moment a second is added, so bolting this on would cost that"
            + " kind its runnable instruction to buy this one a mention.",
        "apply_cleanup kind=consolidate_conditional",
        "no detector reports repeated checks with one outcome. It would be a real"
            + " detector and it is not one of this sprint's six.",
        "apply_cleanup kind=control_flag_to_break",
        "no detector reports a control flag. The shape is narrow enough that a detector"
            + " for it would fire about as rarely as the rewrite does.",
        "apply_cleanup kind=slide_declaration",
        "no detector reports a declaration far from its use. It is a step INSIDE a"
            + " long-method cure rather than a finding of its own — which is why the"
            + " plan lists it as what makes Extract Function possible.",
        "apply_cleanup kind=split_loop",
        "no detector reports a loop doing two things. `long_method` does not, and a"
            + " loop-level detector is not among this sprint's six.",
        // NOT an apply_cleanup entry, and the first one here that is not. The gate that
        // reads this map is scoped to apply_cleanup on purpose, so nothing checks this
        // line — it is written because the per-row contract says routed OR unrouted with
        // the reason, and an unexplained gap and a forgotten one read identically.
        "refactor_to_pattern kind=decompose_conditional",
        "no detector reports a complicated conditional. `long_method` is the nearest and"
            + " already has one route — compose_method — which the tier model turns to"
            + " ADVISE the moment a second is added. And this one could not be run from a"
            + " finding even if the finding existed: it needs method NAMES from the caller,"
            + " which is the whole refactoring, and no finding carries those.");

    /** Why an operation that ships is reachable from no finding, or null if it is. */
    public static String unroutedReason(String operation) {
        return operation == null ? null : SHIPPED_BUT_UNROUTED.get(operation);
    }

    /**
     * REFACTORINGS WE DECLINE TO AUTOMATE, each pointing at where it is described.
     *
     * <p>Separate from {@link #BY_KIND} because these are not cures for a smell: no
     * detector asks for them, so there is no kind to key them under. Keyed by the
     * refactoring's own name instead.</p>
     *
     * <p>Change Value to Reference is here on Harald's ruling of 2026-09-02: which
     * field is the identity key, and where the shared instances live, are design
     * decisions a tool would have to guess. The entry keeps it REACHABLE — a reader
     * meeting it is pointed at the repository pattern — while it stays absent from
     * every runnable list, which is the whole content of "advice only".</p>
     */
    private static final Map<String, String> ADVICE_ONLY = Map.of(
        "change_value_to_reference", "design:repository");

    /**
     * Where a declined refactoring is described, or null if it is not declined.
     *
     * <p>Never returns a recipe: an advice entry has no runnable half by
     * construction, which is what {@link #recipesFor} not knowing about this map
     * enforces.</p>
     */
    public static String adviceFor(String refactoring) {
        return refactoring == null ? null : ADVICE_ONLY.get(refactoring);
    }

    private CureCatalog() {
    }

    /**
     * INVARIANT 2: every declared step names an operation something publishes.
     *
     * <p>A step no registered operation backs would read as runnable and then refuse at
     * the front door — the drift {@code CureTier}'s missing-step branch surfaces at
     * lookup time. This throws instead, so it cannot ship.</p>
     *
     * <p><b>Why this is not in the static initializer, where it used to be.</b> It used
     * to validate against one front door's published kinds, which is a constant and so
     * could be read at class-load. It now validates against
     * {@link org.jawata.mcp.refactoring.OperationRegistry}, which tools populate as they
     * register — so at class-load the registry may legitimately be empty, and a check
     * there would refuse every step for a reason that is about ORDERING rather than
     * about a wrong table. Called once from the application after registration, it asks
     * the same question at the first moment the answer is true, and it still THROWS:
     * boot fails loudly on a table naming a step nothing backs.</p>
     *
     * @throws IllegalStateException naming the kind, the step, and that nothing backs it
     */
    public static void validateAgainst(org.jawata.mcp.refactoring.OperationRegistry registry) {
        for (Map.Entry<String, List<Cure>> e : BY_KIND.entrySet()) {
            for (Cure c : e.getValue()) {
                if (c.recipe() == null) {
                    continue;
                }
                if (!registry.has(c.recipe())) {
                    throw new IllegalStateException("CureCatalog: kind '" + e.getKey()
                        + "' declares step '" + c.recipe() + "' and no registered"
                        + " operation backs it. Registered: " + registry.all());
                }
                // AMBIGUITY IS ALSO A BROKEN STEP, and it is the quieter half. Three
                // front doors publish a kind called `method`; two publish `class`. A
                // bare mention of one of those names resolves to whichever tool
                // registered last, so the cure sentence would offer a real invocation
                // against an arbitrary tool — wrong, runnable, and green. The qualified
                // form is always available and is what the table must declare.
                if (registry.ambiguous(c.recipe())) {
                    throw new IllegalStateException("CureCatalog: kind '" + e.getKey()
                        + "' declares step '" + c.recipe() + "', which is published by"
                        + " more than one tool: " + registry.toolsFor(c.recipe())
                        + ". Declare the qualified form instead, e.g. '"
                        + org.jawata.mcp.refactoring.OperationRegistry.qualify(
                            registry.toolsFor(c.recipe()).iterator().next(), c.recipe())
                        + "'.");
                }
            }
        }
    }

    /**
     * EVERY KIND THIS TABLE DECLARES A CURE FOR.
     *
     * <p>Exposed because two tests each kept their own copy of this list and validated
     * the table against it. Adding {@code loops} was invisible to both: the table gained
     * a cure, the tests swept a list that did not mention it, and the check that exists
     * to catch an unbacked step could not see the step. A list of the table's contents,
     * kept outside the table, is the second home this product spends its audits
     * removing.</p>
     */
    public static java.util.Set<String> declaredKinds() {
        return BY_KIND.keySet();
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
                if (c.operation() != null) {
                    out.add(c.operation());
                }
            }
        }
        return List.copyOf(out);
    }
}
