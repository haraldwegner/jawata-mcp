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
        // QUALIFIED at C8, and it was not a style point — it was a BROKEN INSTRUCTION.
        // `data` is a registered, unambiguous operation name, so CureTier derived PERFORM and
        // the product told a reader to RUN it. DataTool's first branch refuses a call with no
        // `kind`: "kind is required; one of [...]". So the one route this smell had could not
        // run, and the tier said run it. Three smells carried the identical bare recipe —
        // this one, global_data and mutable_data — all three from before the door grew from
        // one operation to ten. Stage 5 recorded the qualification as merge work and named
        // the kinds for two of them; an architect watch measured the third and found the
        // failure. Same standard the god_class and loops entries above already state.
        m.put("encapsulation", List.of(
            new Cure("data kind=encapsulate_field", "design:private-class-data")));

        // --- Sprint 28d-rescue, S6: the four rows whose detector already exists. Stage 6
        // BUILT cures for smells this table had no row for at all, and leaving them out is
        // the built-but-unwired state the sprint exists to remove — the finding would go on
        // describing a fix the product performs and not offering it. A C6 audit found
        // exactly that and was right.
        //
        // lazy_class gets TWO routes and therefore ADVISE, which is the honest tier. A class
        // that has stopped earning its name is folded into its only user when it stands
        // beside one and into its parent when it stands under one, and which of those it is
        // is a fact about the hierarchy that the finding does not carry. Offering one as an
        // instruction would send half the readers at the wrong operation.
        m.put("lazy_class", List.of(
            new Cure("inline kind=class", null),
            new Cure("inline kind=subclass", null)));
        // ONE route, so PERFORM, and it is the clearest instruction in this table after
        // remove_dead_code: the finding says a class does nothing but forward, and the cure
        // removes exactly the forwarding it counted. No catalogue design — Remove Middle Man
        // is a refactoring, and `design:middle-man` is not a row that exists.
        m.put("middle_man", List.of(
            new Cure("inline kind=middle_man", null)));
        // Move Field, on the smell that reports two classes reaching into each other's
        // state. NOT on shotgun_surgery, though the plan names both: shotgun surgery is
        // "one change touches many classes", and moving a single field almost never settles
        // it — routing it there would turn a design finding into a one-field instruction.
        m.put("inappropriate_intimacy", List.of(
            new Cure("move kind=field", null)));

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
        // QUALIFIED at C8, and this is the one of the three whose KIND was genuinely open —
        // Stage 5 recorded it as "UNDECIDED … a question, not a transcription", because this
        // smell's subject is STATIC mutable state, which row 9 refuses by name.
        //
        // It is `encapsulate_field`, and that is what this detector's own message already asks
        // for: "Consider Encapsulate Variable — put it behind a function so the writers can be
        // counted." Nine of the door's ten kinds are excluded by their own subject, and the
        // tenth, encapsulate_collection, declines a static field explicitly rather than by
        // omission. The tier does not move: one runnable route before, one after.
        //
        // THE PREMISE IS MEASURED, NOT ASSUMED. Nothing established that the JDT engine
        // underneath accepts a static field at all, and if it refused, this route would be an
        // instruction the product declines — exactly the state the qualification is fixing.
        // GlobalDataRoutePremiseTest runs it on a public static field and asserts it succeeds
        // AND rewrites the file, so the route goes red the day that stops being true.
        //
        // WHAT THIS ROUTE DOES NOT CURE, recorded because a reader would otherwise assume it
        // does: the detector reports two shapes — a static field that is not final (the
        // reference is reassignable) and a static FINAL field of a mutable type (the contents
        // are). This cures the first. For the second the reference is already fixed, so a
        // generated accessor handing back the same list reduces the writers by none — and row
        // 9, which WOULD close it, declines static state. The two detectors partition by
        // staticness, the two cures by what leaks, and that shape falls between them. If it
        // turns out to be a material fraction of real findings, it belongs in PARTIAL_ROUTES
        // with the number rather than downgraded by hand.
        m.put("global_data", List.of(
            new Cure("data kind=encapsulate_field", "design:private-class-data")));
        // QUALIFIED at C8 with the rest — see the note on `encapsulation`. The KIND was
        // already decided by Stage 5, which recorded it in the plan and left the table to the
        // merge: this smell reports a class handing its own mutable collection out, and row 9
        // is the cure that returns a read-only view and puts the mutators on the class.
        m.put("mutable_data", List.of(
            new Cure("data kind=encapsulate_collection", "design:private-class-data")));
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
    private static final Map<String, String> SHIPPED_BUT_UNROUTED = mapOf(
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
        // reads this map covered only apply_cleanup when this was written; C6 widened it to
        // four doors, and refactor_to_pattern is the one still outside — so this line is
        // still unchecked, but for a narrower reason than it used to claim. It is written
        // because the per-row contract says routed OR unrouted with the reason, and an
        // unexplained gap and a forgotten one read identically.
        "refactor_to_pattern kind=decompose_conditional",
        "no detector reports a complicated conditional. `long_method` is the nearest and"
            + " already has one route — compose_method — which the tier model turns to"
            + " ADVISE the moment a second is added. And this one could not be run from a"
            + " finding even if the finding existed: it needs method NAMES from the caller,"
            + " which is the whole refactoring, and no finding carries those."
            + " NO FORK DEMONSTRATION EITHER, and the reason is the same sentence: every"
            + " other row is demonstrated by pointing a sweep at foreign code and reading"
            + " what changed, and this one cannot be, because it does nothing until a human"
            + " supplies three names. A fork slice for it would be a fixture with names"
            + " chosen by us — which is the very thing 'code we did not author' excludes.",

        // --- Sprint 28d-rescue Stage 6. FIVE of its twelve rows route: lazy_class takes
        // two (17, 38), middle_man and inappropriate_intimacy one each (36, 23), and row 24
        // reaches move kind=method through the pre-existing feature_envy route — which a C6
        // audit counted and this comment had missed. These six do not, and a
        // C6 audit was right that neither routed nor written down reads as an oversight.
        //
        // FIVE OF THE SIX SHARE ONE REASON and it is worth stating once: they need a NAME
        // from the caller — the new class, the command, the query, the phase boundary —
        // and a finding carries no names. That is the same reason row 8 is here, and it is
        // not a gap in the detector side that a detector would close.
        "extract kind=combine_functions",
        "no detector reports functions that should be a class, and one could not route here"
            + " anyway: WHICH functions belong together is the decision this carries out,"
            + " and a finding that already knew would have done the refactoring.",
        "extract kind=function_to_command",
        "no detector reports a function that wants to be an object. `long_method` is the"
            + " nearest — a long function whose locals thread through every extraction is"
            + " exactly the case — but it already has one route, compose_method, which the"
            + " tier model turns to ADVISE the moment a second is added. And the command's"
            + " NAME is the caller's; every call site reads it.",
        "extract kind=split_phase",
        "no detector reports a function doing two jobs in sequence, and the operation's"
            + " whole input is the BOUNDARY between them — a judgement about meaning that"
            + " nothing in the syntax marks. A finding could say `long_method` and could"
            + " not say where the seam is.",
        "extract kind=temp_to_query",
        "no detector reports a temp that should be a query. It is a step INSIDE a"
            + " long-method cure rather than a finding of its own, in the same way"
            + " slide_declaration is.",
        "move kind=statements_into_function",
        "no detector reports a statement that always accompanies a call. Finding one means"
            + " comparing every call site of every method against its neighbours, which is"
            + " the operation's own precondition check run over the whole workspace — a"
            + " real detector, and not one of this sprint's six.",
        "move kind=statements_to_callers",
        "the inverse, and unroutable for the inverse reason: nothing reports a method whose"
            + " first or last statement has stopped being every caller's business. That is"
            + " a judgement about what the method is FOR, which no count reaches.",

        // ROW 49's TWO KINDS. Both predate this sprint and both were CHANGED by it — the
        // row landed as `replaceDuplicates` on kind=method and as the fold of
        // `replace_duplicates` onto kind=replace_inline_code — so "it shipped earlier" is
        // no longer a reason to leave them unexamined. A C6 audit found them shielded by
        // an exemption keyed on first-shipped date, which is the wrong key.
        "extract kind=method",
        "no detector reports a statement range that should be a method. `long_method` names"
            + " the enclosing method and routes to compose_method, which is this operation"
            + " driven by a recipe — so the fix IS reachable from a finding, one level up."
            + " What no finding can supply is the name and the RANGE, which are the whole"
            + " input here.",
        "extract kind=replace_inline_code",
        "reachable from find_duplicate_code, which is a VERIFICATION tool rather than a"
            + " smell detector — it takes a cloneGroupId that only that tool produces, and"
            + " the cure table keys on smell kinds. So it is routed in practice and"
            + " unroutable in this table's terms, which is worth stating rather than"
            + " leaving as a blank that reads like an oversight.",

        // --- Sprint 28d-rescue Stage 4, the ten kinds on change_method_signature. NONE of
        // them routes, and until a C4 audit counted it none was written down either — the
        // two states a reader cannot tell apart, which is this table's entire reason to
        // exist. Stage 3 and Stage 6 each learned that at their own checkpoint; Stage 4 is
        // the third, and the guard below was scoped past this door so nothing said so.
        //
        // TWO OF THE TEN ARE DIFFERENT IN KIND from the other eight, and the difference is
        // the whole reason they are HERE rather than routed: a detector already names them
        // in its message prose, so the gap is on the cure-table side rather than the
        // detector side. Wiring either one is a ROUTE, and this plan's own rule is that a
        // lane records the routes its rows need and leaves the table to the merge — because
        // the first RUNNABLE route a smell gains is exactly the input CureTier reads to
        // derive PERFORM, which turns a suggestion into an instruction. That is the same
        // lever the Stage 3 architect finding is about for `loops`, where one runnable route
        // made the product instruct while the rewriter accepted 2 of 21 candidates.
        "change_method_signature kind=introduce_parameter_object",
        "ROUTE AVAILABLE, DELIBERATELY NOT TAKEN HERE. `long_parameter_list` names this"
            + " refactoring in every one of its messages — 'Consider Introduce Parameter"
            + " Object' — and so does `primitive_obsession`. Both would gain their first"
            + " RUNNABLE route, which is what CureTier turns into PERFORM, so wiring it is a"
            + " tier decision for the route merge rather than a transcription.",
        "change_method_signature kind=separate_query_from_modifier",
        "ROUTE AVAILABLE, DELIBERATELY NOT TAKEN, and the consequence is the OPPOSITE of the"
            + " entry above — which is why it is spelled out rather than grouped with it."
            + " `cqs` is NOT advice-only: it already declares one cure whose recipe is"
            + " `apply_cleanup kind=return_modified_value`, that step IS registered, and"
            + " CureTier therefore derives PERFORM today. Adding this row would make TWO"
            + " runnable routes, and CureTier's own rule for that is ADVISE — 'nothing"
            + " mechanical chooses between them'. So wiring the MORE precise cure would COST"
            + " this smell its runnable instruction. That is a real trade for the route merge"
            + " to make, and it runs the other way from every other row here.",
        "change_method_signature kind=replace_query_with_parameter",
        "no detector reports a method that asks a question it could be told the answer to."
            + " The operation's input is the CALL to stop making, which a finding does not"
            + " carry, and its real precondition is that the expression's text means the same"
            + " thing at every call site — a fact about the callers rather than the method.",
        "change_method_signature kind=replace_parameter_with_query",
        "the inverse of the above and unrouted for a different reason: the query IS"
            + " derivable, from the call sites, so a detector could in principle name this."
            + " None does. Nothing reports a parameter every caller derives the same way.",
        "change_method_signature kind=parameterize_function",
        "no detector reports two near-identical methods differing by one constant."
            + " `find_duplicate_code` is the nearest and reports clone GROUPS, not the"
            + " literal that separates them — and the literal is this operation's input.",
        "change_method_signature kind=replace_exception_with_precheck",
        "UNROUTABLE rather than unrouted, and measured rather than assumed:"
            + " find_quality_issue(kind=catches) is a SEARCH — it takes an exception name and"
            + " returns the sites that catch it — so it emits no finding and names no cure."
            + " No detector reports a try/catch that a test could replace.",
        "change_method_signature kind=preserve_whole_object",
        "no detector reports several arguments taken off one object. `long_parameter_list` is"
            + " the nearest and names Introduce Parameter Object instead, which is a different"
            + " row on this same door — it BUILDS an object where this one passes an object"
            + " the caller already holds.",
        "change_method_signature kind=remove_flag_argument",
        "no detector reports a boolean parameter that selects behaviour. It could not be run"
            + " from a finding even if one existed: the two new methods' NAMES are the whole"
            + " point of the change, and no finding carries a name.",
        "change_method_signature kind=replace_command_with_function",
        "no detector reports a command class that could be a function, and a census says one"
            + " would mostly report cases this row must refuse: over the fork's 1354 main"
            + " sources, 33 classes have exactly one command-shaped public method and 27 of"
            + " them declare it because a SUPERTYPE does, which is dispatch rather than"
            + " ceremony.",
        "change_method_signature kind=replace_error_code_with_exception",
        "no detector reports a sentinel return, and one could not carry this row's input: the"
            + " ERROR VALUE is the caller's to name. Measured over the fork's 1354 main"
            + " sources, the sentinel-shaped returns are 43 `return null`, 38 `return false`"
            + " and 3 `return -1`, and almost every one is ordinary control flow — which is"
            + " why the row requires the value rather than inferring it.",

        // --- Sprint 28d-rescue Stage 7, the five kinds on hierarchy. FOURTH stage, same
        // omission: the guard was scoped past this door too, so its silence said nothing
        // about these five, and a C7 audit made the finding the C6 and C4 audits had
        // already made word for word. The lesson this file states one paragraph up — the
        // scope widens in the SAME change that adds the kinds — is now earned three times.
        // TWO of these five are NOT gaps on the detector side. They are routes deliberately
        // NOT taken, because taking them would change a smell's TIER, and that is a product
        // decision rather than a transcription. Both are spelled out below.
        //
        // THE `kind=` IN THESE KEYS IS A LIE ABOUT THIS DOOR, and it is spelled that way on
        // purpose so the keys match the registry. `OperationRegistry.qualify` hard-codes
        // "<tool> kind=<kind>" and its javadoc says that is "what a reader types" — but
        // `hierarchy` selects on `direction`, and a call passing `kind` is refused with
        // "direction is required". So every qualified address for this door renders an
        // instruction that does not run. The defect PREDATES Stage 7 (it was already true of
        // up/down) and Stage 7 multiplies it from two operations to seven. Not fixed here:
        // `qualify` takes a tool NAME rather than the tool, so teaching it the discriminator
        // means changing the operation-naming surface, which Stage 9's M6c/M10 is already
        // deriving. Recorded where a reader meets the address rather than left to be found.
        "hierarchy kind=pull_up_constructor_body",
        "no detector reports a subclass constructor assigning fields its SUPERCLASS declares."
            + " The nearest is `duplicated_code`, which compares bodies and would name the"
            + " assignments rather than the ownership — and ownership is this row's entire"
            + " rule: it moves a leading run of assignments to fields the parent declares,"
            + " which is a fact about who declares what and not about text repeating.",
        "hierarchy kind=replace_type_code_with_subclasses",
        "ROUTABLE AND DELIBERATELY NOT ROUTED. `type_code` reports 9 findings over this"
            + " bundle's 457 files (projectKey=jawata-mcp), and the SCOPE is stated with the"
            + " count because without it the number is not reproducible: an unscoped call"
            + " answers 10 over 466, the extra finding being a fixture in ANOTHER repository"
            + " that happens to be loaded in the same workspace. Two earlier versions of this"
            + " sentence blamed the difference on the fixture project growing over time —"
            + " wrong, and a two-command experiment settles it: this bundle measures 9 today,"
            + " exactly as it did then. Nothing grew; the two calls asked different questions."
            + " Every one of the nine messages names the SIBLING cure — refactor_to_pattern"
            + " kind=replace_type_code_with_class, which shipped in Stage 3 and is that"
            + " smell's ONE runnable route, so CureTier derives PERFORM. Adding this row as a"
            + " second runnable route derives ADVISE by the same rule, so wiring the more"
            + " specific cure would COST the smell its runnable instruction. That trade was"
            + " established at C4 for `cqs`; this is its second measured instance, which is"
            + " what makes it a property of the tier model rather than a quirk of one smell.",
        "hierarchy kind=replace_superclass_with_delegate",
        "ROUTABLE AND NOT YET ROUTED, and it is the MIRROR of the entry above rather than"
            + " another gap. `composition_over_inheritance` names this row in its own finding"
            + " text, under Fowler's earlier title Replace Inheritance with Delegation, and"
            + " declares TWO cures that are both design-only — so it derives ADVISE today and"
            + " this row would be its FIRST runnable route, deriving PERFORM. That is an"
            + " upgrade rather than a cost, which is exactly why it is a decision and not a"
            + " transcription: it changes what the product INSTRUCTS on that smell. Held for"
            + " the route batch with both directions of the trade now measured. The demand is"
            + " real and entirely external — the smell reports 0 over this bundle's 457 files"
            + " (projectKey=jawata-mcp) and 9 over the fork. The population is stated for the"
            + " same reason as the entry above: an earlier version said '460 files', which is"
            + " neither the scoped figure nor the unscoped one and so was reproducible from"
            + " nothing. A round-5 audit found it one entry below the one that had just been"
            + " corrected for exactly that.",
        "hierarchy kind=replace_subclass_with_delegate",
        "no detector reports a subclass whose variation is what it OVERRIDES."
            + " `composition_over_inheritance` is the nearest and reports the opposite case —"
            + " a subclass that overrides NOTHING, which is the sibling row above. A detector"
            + " for this one would have to judge that a second axis of variation is WANTED,"
            + " which is a design intention and not a property of the code.",
        "hierarchy kind=collapse_hierarchy",
        "no detector reports a hierarchy level that is not earning itself. `lazy_class` is the"
            + " nearest and routes to inline kind=subclass, which is this row's COMPLEMENT —"
            + " that row folds a leaf and refuses a class with subtypes, naming this one. So a"
            + " lazy_class finding on a middle level already points here through that row's"
            + " refusal, which is reachable in practice and not a table entry.");

    /** Map.of caps at ten pairs; this table passed it at Stage 6. */
    private static Map<String, String> mapOf(String... pairs) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (m.put(pairs[i], pairs[i + 1]) != null) {
                throw new IllegalStateException(
                    "CureCatalog: '" + pairs[i] + "' is listed unrouted twice");
            }
        }
        return Map.copyOf(m);
    }

    /**
     * ROUTES THAT RUN, AND COMMONLY DECLINE — the gap between a finder and its fix.
     *
     * <p>{@link CureTier} derives PERFORM from route COUNT: one runnable route, run it.
     * That reads as an instruction, and it is the right derivation when the route acts on
     * what the finding names. It is the wrong one when the two disagree, because the user
     * follows an instruction and gets an honest no-op — and learns to distrust the next
     * instruction too.</p>
     *
     * <p>An entry here is a MEASUREMENT, not a hunch. It says the route was run against
     * its own finder's candidate set and how much of it the route accepted. The tier then
     * reports ADVISE and carries the number, so the reader decides.</p>
     *
     * <p>This is a stopgap and worth naming as one. The structural answer is for a routed
     * cleanup kind's detector to BE the rule's own applicability scan, so finder and
     * rewriter cannot disagree by construction — that changes what a Fowler smell reports,
     * which belongs to the detector stage rather than here.</p>
     */
    private static final Map<String, String> PARTIAL_ROUTES = Map.of(
        "apply_cleanup kind=loop_to_pipeline",
        "measured over the java-design-patterns corpus, 1884 distinct source paths"
            + " aggregated from its modules (the fork holds more; the rest collide on path"
            + " when the modules are flattened into one root): find_modernization"
            + " (loop_to_stream), which is this smell's finder, names 29 candidates in 18"
            + " files, and this rewriter changes 2 of them. It refuses arrays, any"
            + " break/continue/return, a body doing more than one thing, and a list not"
            + " declared empty directly above — all correctly, and the finder applies none"
            + " of those tests. So run it if you like, but expect most findings to be ones"
            + " it declines, and read the loop yourself for the rest.");

    /** Why a runnable route commonly declines, or null when it acts on what it is given. */
    public static String partialReason(String operation) {
        return operation == null ? null : PARTIAL_ROUTES.get(operation);
    }

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
