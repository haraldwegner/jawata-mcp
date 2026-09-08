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
     * automates it), the catalogue key its design lives under, and the
     * DISCRIMINATOR — the sentence that tells this cure from the others declared
     * for the same smell.
     *
     * <p>The discriminator exists because the verdict stopped being a function of
     * how MANY cures a smell has. Under the old rule a second runnable cure demoted
     * the smell from "run this" to "consider these", so the product instructed less
     * the more it could do. Harald overturned it: <i>"You have 3 alternatives. Pick
     * the most appropriate one and perform."</i> An agent can only pick if each
     * alternative says what tells it from its neighbours, ANCHORED TO A FACT THE
     * FINDING CARRIES — otherwise the ranking is a list and the choice is a guess.</p>
     *
     * <p><b>Null is legal and means "this smell declares only one runnable cure".</b>
     * There is nothing to tell apart, so demanding a sentence would be demanding
     * prose for its own sake. INVARIANT 3 in {@link #validate} is what keeps that
     * honest: the moment a kind declares a SECOND runnable cure, every one of them
     * must carry a discriminator or the table does not load.</p>
     */
    public record Cure(String recipe, String operation, String discriminator,
                       java.util.List<String> needs) {

        /**
         * {@code needs} is never null — an empty list and "declares nothing" are the same
         * answer, and letting both spellings exist would make every reader check for two.
         */
        public Cure {
            needs = needs == null ? java.util.List.of() : java.util.List.copyOf(needs);
        }

        /**
         * The two-argument form, for the ~40 single-cure rows that need no
         * discriminator.
         *
         * <p>It is a convenience and not a loophole, and the difference is INVARIANT
         * 3: a row written this way is fine alone and REFUSES TO LOAD the moment a
         * second runnable cure joins its kind. Widening all forty call sites instead
         * would have put a literal {@code null} on every row that legitimately has
         * nothing to say.</p>
         */
        public Cure(String recipe, String operation) {
            this(recipe, operation, null, java.util.List.of());
        }

        /** Discriminated, and needing nothing the finding does not already carry. */
        public Cure(String recipe, String operation, String discriminator) {
            this(recipe, operation, discriminator, java.util.List.of());
        }
    }

    /**
     * The three designs that close a modification axis — OCP's answer, shared by its traces.
     *
     * <p>Each carries the sentence that tells it from the other two. They are not ranked
     * against each other here and must not be: which one fits is a property of the code at
     * the address the finding names, so the agent reads the branching and picks. Before the
     * tier reversal this list made its three smells say "consider" — three good answers
     * counted as doubt.</p>
     */
    private static final List<Cure> OPEN_THE_AXIS = List.of(
        new Cure("refactor_to_state", "design:state",
            "when the branching turns on a FIELD holding one of a fixed set of values, and"
                + " the object changes that value as it runs — the axis is the object's own"
                + " lifecycle, so each value becomes a class that knows what comes next"),
        new Cure("refactor_to_command_dispatcher", "design:command",
            "when the branches are NAMED ACTIONS a caller asks for, and a new one arrives as"
                + " a new request rather than as a new state — the axis is the request set,"
                + " so each action becomes a command the dispatcher looks up"),
        new Cure("form_template_method", "design:template-method",
            "when the branches share ONE SKELETON and differ only at named steps inside it —"
                + " the axis is the steps, so the skeleton is written once and each variant"
                + " supplies its own steps"));

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
                "design:command-query-responsibility-segregation",
                "when the mutation is LOCAL — the method builds its answer in a mutable"
                    + " local and can simply return the value"),
            new Cure("change_method_signature kind=separate_query_from_modifier", null,
                "when the mutation is a FIELD the method wrote and callers read the answer"
                    + " too — the command and the query split, and call sites split with"
                    + " them")));
        m.put("coupling", List.of(
            new Cure(null, "design:dependency-injection"),
            new Cure(null, "design:mediator")));
        m.put("composition_over_inheritance", List.of(
            new Cure("hierarchy kind=replace_superclass_with_delegate", null,
                "when the subclass overrides NOTHING and inherited only to reuse — the"
                    + " parent becomes a field it holds"),
            new Cure(null, "design:delegation",
                "the design this names, for the cases the operation refuses"),
            new Cure(null, "design:strategy",
                "when what varies is an ALGORITHM rather than a whole parent")));
        // encapsulation's runnable route is declared with the other three below,
        // where the reason they were unreachable is written down once.

        // --- the traces and smells that already had recipes ------------------
        // Same designs as `ocp` because they ARE its traces: OcpDetector relabels
        // a trace finding, so the trace's cure and the principle's must be one
        // table or they drift the moment either is edited.
        // …AND ONE MORE, which is why this row stopped BEING OPEN_THE_AXIS. Row 64 Split
        // Phase is the only refactoring the inventory's "Finds it" column gives to
        // divergent_change, and it is not an OCP design: the three above open an axis by
        // introducing an abstraction, while Split Phase cuts a method that does two jobs in
        // sequence into two. It is offered LAST because those three reshape a class and this
        // one reshapes a method.
        List<Cure> divergentChange = new java.util.ArrayList<>(OPEN_THE_AXIS);
        divergentChange.add(new Cure("extract kind=split_phase", null,
            "when the divergence is SEQUENTIAL — the class does one job and then another with"
                + " its result, so the two phases separate rather than an abstraction being"
                + " introduced",
            List.of("line")));
        m.put("divergent_change", List.copyOf(divergentChange));
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
            new Cure("replace_type_code_with_class", "design:type-object",
                "when the code only needs to stop being an int — a type-safe enum, and"
                    + " nothing branches on it"),
            new Cure("hierarchy kind=replace_type_code_with_subclasses", null,
                "when BEHAVIOUR branches on the code — each value becomes a subclass that"
                    + " carries its own behaviour"),
            new Cure("data kind=replace_primitive", null,
                "when the code is really a VALUE with rules of its own — validation, a"
                    + " format, arithmetic — rather than one of a fixed set")));
        m.put("singleton", List.of(
            new Cure("inline_singleton", "design:singleton")));
        // FIVE ROUTES AT S8b STEP 9, and every one of them was refused before it on the SAME
        // false ground. The unrouted-reason table said, of guard_clauses and of
        // decompose_conditional and of function_to_command: "`long_method` already has one
        // route — compose_method — which the tier model turns to ADVISE the moment a second is
        // added, so bolting this on would cost that kind its runnable instruction." That was a
        // true reading of the old rule and it is why the routes were withheld. Step 3 reversed
        // the rule; the sentences became false; and what they had been protecting was a smell
        // with 276 findings on this repository offering exactly one answer.
        m.put("long_method", List.of(
            new Cure("compose_method", "design:compose-method",
                "when the method reads as a SEQUENCE OF NAMED STEPS once you say them out"
                    + " loud — the whole body becomes a short list of calls",
                List.of("sections")),
            new Cure("apply_cleanup kind=guard_clauses", null,
                "when the length is NESTING rather than volume — conditionals inside"
                    + " conditionals, where the real work is at the bottom"),
            new Cure("refactor_to_pattern kind=decompose_conditional", null,
                "when ONE conditional carries the complexity and its parts want names",
                List.of("line", "column", "conditionName", "thenName", "elseName")),
            new Cure("extract kind=temp_to_query", null,
                "when the body is long with TEMPORARIES that each explain one thing; each"
                    + " becomes a query and the method shortens around them",
                List.of("line", "column")),
            new Cure("extract kind=function_to_command", null,
                "when the locals THREAD THROUGH everything, so no range extracts cleanly —"
                    + " the method becomes an object and its locals become fields",
                List.of("newTypeName"))));

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
            new Cure("move kind=method", null,
                "when ONE method is envious — it moves to the class whose data it prefers"),
            new Cure("extract kind=combine_functions", null,
                "when SEVERAL methods envy the same data — they and the data become one"
                    + " class, rather than moving one at a time",
                List.of("functions", "newTypeName"))));
        // QUALIFIED, for the reason the fold pointers are: `extract` publishes seven
        // kinds, and naming the bare front door leaves a reader to guess which one. The
        // architecture says extract(class) for both of these, and the registry publishes
        // that spelling as an unambiguous key.
        // mcp#71: `extract kind=class` cannot run on an address alone, and now says so.
        //
        // Its schema documents WHY, deliberately: "fields[] has no default on purpose — WHICH
        // state travels together is the design decision this carries out", and newTypeName is
        // the same shape of answer. Both are DECISIONS, so no finding can carry them and the
        // door is right to require them. What was wrong is upstream: the finding rendered
        // "TIER: RUN — run extract kind=class" over {symbol, filePath}, and following it
        // verbatim earns "INVALID_PARAMETER 'newTypeName'".
        //
        // Naming them keeps the instruction rather than downgrading it — the step now reads as
        // runnable once the agent supplies two named things, which is true, instead of
        // runnable, which was not.
        m.put("god_class", List.of(
            new Cure("extract kind=class", null, null,
                List.of("newTypeName", "fields"))));
        m.put("temporary_field", List.of(
            // mcp#71: the SAME door, so the same two decisions — a discriminator says which
            // cure to pick, and needs says what the picked one still wants.
            new Cure("extract kind=class", null,
                "when the field and the methods that use it form a COHERENT JOB — they leave"
                    + " together as a class of their own",
                List.of("newTypeName", "fields")),
            new Cure("data kind=special_case", null,
                "when the field is empty for a RECOGNISABLE CASE and every reader checks for"
                    + " it — the case becomes a subclass that answers for itself")));
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
            new Cure("data kind=encapsulate_field", "design:private-class-data",
                "when ONE field is exposed and its type is a plain value — the accessor pair"
                    + " goes on and the field goes private"),
            new Cure("data kind=encapsulate_collection", null,
                "when the exposed field is a COLLECTION — a getter alone still hands the"
                    + " contents out, so the view is read-only and the mutators move here"),
            new Cure("data kind=encapsulate_record", null,
                "when SEVERAL public fields are exposed at once — the whole record is"
                    + " encapsulated in one pass rather than a field at a time"),
            new Cure("data kind=remove_setting_method", null,
                "when the field is settled at construction and the setter is what breaks"
                    + " that — the setter goes and the field becomes final")));

        // --- Sprint 28d-rescue, S6: the four rows whose detector already exists. Stage 6
        // BUILT cures for smells this table had no row for at all, and leaving them out is
        // the built-but-unwired state the sprint exists to remove — the finding would go on
        // describing a fix the product performs and not offering it. A C6 audit found
        // exactly that and was right.
        //
        // lazy_class gets TWO routes and therefore ADVISE, which is the honest tier. A class
        // that has stopped earning its name is folded into its only user when it stands
        // beside one and into its parent when it stands under one.
        //
        // THAT SENTENCE USED TO END "...which the finding does not carry. Offering one as an
        // instruction would send half the readers at the wrong operation." Both halves were
        // true and the CONCLUSION no longer follows. The finding does not carry the
        // hierarchy, so nothing here can pick — but nothing here has to: the verdict is now
        // RUN with BOTH cures ranked, each carrying the sentence that tells it apart, and the
        // agent reads the type the finding names and picks. What would send half the readers
        // wrong is offering one cure; offering two undescribed ones is the other half of the
        // same mistake, and it is what INVARIANT 3 now refuses.
        //
        // A NOTE ON "anchored to a fact the finding carries" (v4 P4), because this row is
        // where that clause meets its edge: the discriminating fact here is READABLE AT THE
        // ADDRESS the finding carries, not present as a field on it. That is the honest
        // reading — a discriminator's job is to tell the agent what to go and look at — and
        // it is recorded rather than silently widened.
        m.put("lazy_class", List.of(
            new Cure("inline kind=class", null,
                "when the class stands BESIDE its user — a collaborator with no supertype of"
                    + " its own; it folds into the caller that holds it"),
            new Cure("inline kind=subclass", null,
                "when it stands UNDER a parent and overrides nothing; it folds into that"
                    + " parent, and inline kind=class would refuse it")));
        // ONE route, so PERFORM, and it is the clearest instruction in this table after
        // remove_dead_code: the finding says a class does nothing but forward, and the cure
        // removes exactly the forwarding it counted. No catalogue design — Remove Middle Man
        // is a refactoring, and `design:middle-man` is not a row that exists.
        m.put("middle_man", List.of(
            new Cure("inline kind=middle_man", null,
                "when MOST of the class forwards — the forwarders go and callers reach the"
                    + " delegate directly",
                List.of("delegateField")),
            new Cure("inline kind=method", null,
                "when only ONE method forwards and the class otherwise earns its place")));
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
        // THE MEASUREMENT USED TO BE A DEMOTION, and is now the discriminator. This route
        // commonly declines on its own finder's candidates, and the old rule answered by
        // pulling the kind down to "consider" and withholding the step. That told a reader
        // less: they lost the instruction AND still had to find out why. Now they get the
        // step and the number, and decide.
        m.put("loops", List.of(
            new Cure("apply_cleanup kind=loop_to_pipeline", null,
                "expect it to decline most of what this finding names, and read the loop"
                    + " yourself for the rest — measured over the java-design-patterns"
                    + " corpus, 1884 distinct source paths aggregated from its modules:"
                    + " find_modernization (loop_to_stream), which is this smell's finder,"
                    + " names 29 candidates in 18 files, and this rewriter changes 2 of"
                    + " them. It refuses arrays, any break/continue/return, a body doing"
                    + " more than one thing, and a list not declared empty directly above"
                    + " — all correctly, and the finder applies none of those tests")));
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

        // --- S8b step 9: THE SEVEN SMELLS THAT HAD NO ROW AT ALL --------------------
        //
        // Each of these detectors ships, fires, and named its cure in its own message
        // prose while this table offered nothing runnable — the built-but-unwired state
        // the sprint exists to remove, one level up from the kinds it was found on.
        //
        // THEY COULD NOT HAVE BEEN WRITTEN BEFORE STEP 3. Under the old rule a second
        // runnable cure demoted the smell from "run this" to "consider these", so a row
        // with four good answers was worth LESS than a row with one — and the honest
        // move was to withhold three of them. Every row below carries several, each with
        // the sentence that tells it from its neighbours, which is only possible because
        // the verdict stopped counting.
        m.put("long_parameter_list", List.of(
            new Cure("change_method_signature kind=introduce_parameter_object", null,
                "when the parameters name PARTS OF ONE THING — a start and an end, an x"
                    + " and a y — and a name for that thing suggests itself",
                List.of("newTypeName")),
            new Cure("change_method_signature kind=preserve_whole_object", null,
                "when the caller already HOLDS an object and is taking it apart to pass"
                    + " the pieces; pass the object it already has",
                List.of("parameters")),
            new Cure("change_method_signature kind=replace_parameter_with_query", null,
                "when one parameter can be DERIVED from another the call already passes,"
                    + " so every caller computes the same thing",
                List.of("parameter")),
            new Cure("change_method_signature kind=change_signature", null,
                "when a parameter is simply unused, or the list only needs reordering —"
                    + " the smallest answer, and the one to rule out first")));
        m.put("data_clumps", List.of(
            new Cure("change_method_signature kind=introduce_parameter_object", null,
                "when the clump travels through SIGNATURES and has no home yet",
                List.of("newTypeName")),
            new Cure("change_method_signature kind=preserve_whole_object", null,
                "when the clump is already an object's fields and the caller is unpacking"
                    + " them at the call",
                List.of("parameters")),
            new Cure("extract kind=combine_functions", null,
                "when the clump is accompanied by the FUNCTIONS that work on it — then the"
                    + " data and its behaviour become one class rather than one parameter",
                List.of("functions", "newTypeName"))));
        m.put("speculative_generality", List.of(
            new Cure("hierarchy kind=collapse_hierarchy", null,
                "when the unearned generality is a LEVEL in a hierarchy — the middle class"
                    + " nobody needed folds into its parent"),
            new Cure("apply_cleanup kind=remove_dead_code", null,
                "when it is a member nothing reaches rather than a level — the compiler"
                    + " proves it unused and the sweep deletes it")));
        // NO DESIGN ADDRESS, and the first draft of this row had one. `design:law-of-demeter`
        // reads perfectly and the catalogue holds no such row — the re-resolution audit caught
        // it within one run, which is exactly the failure this file's own warning describes:
        // an invented key resolves to nothing, and the finding then renders NO CATALOGUE
        // ADDRESS, which is worse for a reader than offering the runnable fix with no further
        // reading. Hide Delegate is a refactoring, not a pattern.
        m.put("message_chains", List.of(
            new Cure("data kind=hide_delegate", null)));
        m.put("primitive_obsession", List.of(
            new Cure("data kind=replace_primitive", "design:value-object")));
        m.put("refused_bequest", List.of(
            new Cure("hierarchy kind=down", null,
                "when only SOME subclasses refuse the bequest — the unwanted members are"
                    + " pushed down to the ones that do want them, and the hierarchy stays"),
            new Cure("hierarchy kind=replace_subclass_with_delegate", null,
                "when the subclass is refusing but its OVERRIDES are the point — its"
                    + " varying behaviour becomes a delegate the parent holds"),
            new Cure("hierarchy kind=replace_superclass_with_delegate", null,
                "when the subclass overrides NOTHING and inherited only to reuse — the"
                    + " parent becomes a field, and substitutability goes with it")));
        // THE DUPLICATED-CODE KIND, decided in the plan rather than parked: the clone
        // groups become findings the way ModernizationSmells reshaped find_modernization's
        // candidates, so the finder is unchanged and the smell row stays "have".
        m.put("duplicated_code", List.of(
            new Cure("extract kind=replace_inline_code", null,
                "when the clones are STATEMENT RANGES that already exist as a method"
                    + " somewhere — the group's own id names them",
                List.of("cloneGroupId")),
            new Cure("change_method_signature kind=parameterize_function", null,
                "when the clones differ only by a LITERAL — one function takes it as a"
                    + " parameter and the copies collapse into it",
                List.of("literal"))));

        // INVARIANT 1, checkable here because it needs nothing outside the table:
        // the pair (kind, operation) is the ENTRY IDENTITY — declared at most once,
        // or two rows claim one route set.
        validate(m);
        // INVARIANT 2 — every recipe names a published operation — moved to
        // validateAgainst. See its javadoc for why it cannot live here.
        return Map.copyOf(m);
    }

    /**
     * THE INVARIANTS THE TABLE CAN CHECK ABOUT ITSELF, before anything else exists.
     *
     * <p>Extracted from {@code byKind()} so the checks have a name and a home; the
     * one that needs the operation registry stays in {@link #validateAgainst},
     * because at class-load that registry is legitimately empty.</p>
     *
     * <p><b>The extraction emitted a TAB-indented body into this space-indented
     * file</b> — the defect Stage 3 recorded against {@code extract} and which is
     * still open. Re-indented by hand here; the finding is not new and is not
     * silently absorbed.</p>
     *
     * <p>PACKAGE-VISIBLE rather than private, and that is the seam the invariants are
     * tested through. Run only against the shipped table they are unfalsifiable: the
     * table satisfies them, so a check that had rotted would look exactly like a check
     * that held. A test in this package plants a table that BREAKS one and requires the
     * throw — which is the same argument {@code CureTier}'s registry parameter makes for
     * the derivation next door.</p>
     */
    static void validate(Map<String, List<Cure>> m) {
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

        // INVARIANT 3: a smell with TWO OR MORE runnable cures must be able to tell
        // them apart. The verdict no longer falls back to "consider" when there are
        // several — it stays RUN and hands over a ranked list — so the sentence that
        // distinguishes each one is now load-bearing rather than decorative. A list
        // of equally-described alternatives is exactly the "pick one" the ruling
        // refuses to leave to chance.
        //
        // EVERY offender is collected before throwing, deliberately. A first-wins
        // throw makes a table with four bad rows take four builds to fix, and each
        // run would look like a new defect rather than the same one.
        java.util.List<String> undiscriminated = new java.util.ArrayList<>();
        for (Map.Entry<String, List<Cure>> e : m.entrySet()) {
            List<Cure> runnable = e.getValue().stream()
                .filter(c -> c.recipe() != null).toList();
            if (runnable.size() < 2) {
                continue;
            }
            for (Cure c : runnable) {
                if (c.discriminator() == null || c.discriminator().isBlank()) {
                    undiscriminated.add(e.getKey() + " -> " + c.recipe());
                }
            }
        }
        if (!undiscriminated.isEmpty()) {
            throw new IllegalStateException("CureCatalog: these kinds declare more than"
                + " one runnable cure and cannot tell them apart, so an agent asked to"
                + " pick the most appropriate one would be guessing: " + undiscriminated);
        }
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
        // SIXTEEN ENTRIES WERE DELETED FROM THIS TABLE AT S8b STEP 9, and they were not
        // tidied away — a guard measured them. `apply_cleanup kind=guard_clauses` was the
        // first: it said "no detector reports nested conditionals" and argued that routing it
        // to `long_method` would cost that smell its runnable instruction, because a second
        // runnable cure demoted the verdict. Step 3 reversed that rule and step 9 took the
        // route, so the sentence became false the moment the route landed — and nothing said
        // so, because the routing guard's question was "routed OR explained" and an operation
        // that is both satisfies it. `EveryShippedKindIsRoutedOrExplainedTest` now refuses
        // the conjunction and NAMES every offender, which is how these sixteen were found
        // rather than remembered.
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
        // `refactor_to_pattern kind=decompose_conditional` WAS HERE and is routed now, on
        // `long_method`, with `needs=[line, column, conditionName, thenName, elseName]`. Its
        // entry made two claims and only one has survived: the tier argument is retired with
        // the rule it rested on, while the observation that the row needs three NAMES from
        // the caller is still true — and v4.1's `needs[]` is what turned that from a reason
        // not to route into a declared input the agent supplies. Its other half — that the
        // same sentence is why the row has no fork demonstration — moved to the row's own
        // record in the plan, where a reader of the fork clause meets it.

        // --- Sprint 28d-rescue Stage 5's three rows that stay unrouted after step 9's
        // routing table, each for its OWN reason rather than a shared shrug.
        "data kind=split_variable",
        "no detector reports a variable serving two purposes — and one could not route here"
            + " even if it existed. The operation's whole input is the NEW NAME for the"
            + " second value, which is what saying the two purposes apart MEANS; a finding"
            + " carries no names. That makes this unroutABLE rather than merely unrouted,"
            + " which is a stronger statement than the rows around it.",
        "data kind=replace_derived_variable",
        "no detector reports a field always recomputed from other fields. It would be a real"
            + " detector — every writer's expression compared against every other — and it is"
            + " not one of this sprint's six.",
        "data kind=reference_to_value",
        "nothing reports that a class SHOULD BE A VALUE, and the plan's own C2 clause says so:"
            + " seven of the eight composed rows carry a detector and this is the one that"
            + " does not. Whether shared mutable identity is the point or the bug is a"
            + " modelling decision about the domain, not a shape in the code.",

        // --- Sprint 28d-rescue Stage 6. FIVE of its twelve rows route: lazy_class takes
        // two (17, 38), middle_man and inappropriate_intimacy one each (36, 23), and row 24
        // reaches move kind=method through the pre-existing feature_envy route — which a C6
        // audit counted and this comment had missed. These six do not, and a
        // C6 audit was right that neither routed nor written down reads as an oversight.
        //
        // FOUR OF THESE SIX WERE DELETED AT STEP 9 — combine_functions, function_to_command,
        // split_phase and temp_to_query — and the paragraph that stood here is why they could
        // be. It said five of the six "need a NAME from the caller … and a finding carries no
        // names", and treated that as disqualifying. v4.1's `needs[]` is the answer: the cure
        // DECLARES what the agent must supply, the finding supplies the address, and the two
        // together are a runnable instruction. What is left below are the rows where nothing
        // reports the shape at all — a detector-side gap, which no `needs[]` closes.
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
        // `extract kind=replace_inline_code` WAS HERE, and its entry described its own cure.
        // It said the row is "reachable from find_duplicate_code, which is a VERIFICATION
        // tool rather than a smell detector … so it is routed in practice and unroutable in
        // this table's terms". Step 9 closed exactly that gap: the clone finder is now
        // registered as the smell `duplicated_code`, so the table's terms and practice agree
        // and the row routes like any other, with the group id as a declared `needs[]`.

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
        // THE TWO "ROUTE AVAILABLE, DELIBERATELY NOT TAKEN" ENTRIES ARE GONE, and the route
        // is taken. They were the clearest statements of the rule step 3 reversed, and they
        // ran in opposite directions: introduce_parameter_object would have given
        // `long_parameter_list` its FIRST runnable route (an upgrade), while
        // separate_query_from_modifier would have made `cqs` declare a SECOND (a demotion,
        // under the old rule, of the smell that could do more). Both were correct readings of
        // the rule and both are now moot: the verdict no longer counts, so
        // `long_parameter_list` declares four cures and `cqs` two, each ranked with the
        // sentence that tells it from its neighbour. THREE MORE change_method_signature
        // entries went with them — parameterize_function, replace_parameter_with_query and
        // preserve_whole_object — each of which said no detector reports its shape while a
        // detector's own message named it.
        "change_method_signature kind=replace_query_with_parameter",
        "no detector reports a method that asks a question it could be told the answer to."
            + " The operation's input is the CALL to stop making, which a finding does not"
            + " carry, and its real precondition is that the expression's text means the same"
            + " thing at every call site — a fact about the callers rather than the method.",
        "change_method_signature kind=replace_exception_with_precheck",
        "UNROUTABLE rather than unrouted, and measured rather than assumed:"
            + " find_quality_issue(kind=catches) is a SEARCH — it takes an exception name and"
            + " returns the sites that catch it — so it emits no finding and names no cure."
            + " No detector reports a try/catch that a test could replace.",
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
        // THE `kind=` IN THESE KEYS IS THE REGISTRY'S SPELLING, NOT THE DOOR'S, and that is
        // deliberate: `OperationRegistry.qualify` keys every operation as "<tool> kind=<kind>"
        // so a cure's key matches what the registry holds, while `hierarchy` DISPATCHES on
        // `direction`.
        //
        // THE SECOND HALF OF THIS PARAGRAPH WAS TRUE AND IS NOT — corrected at C8b, where an
        // audit found it still standing. It said every qualified address for this door
        // "renders an instruction that does not run", and that it was "not fixed here". S8b
        // step 6 fixed it: the door's own `discriminator()` travels with its registration, so
        // `invocationOf` RENDERS `direction=` for this door while the KEY above stays
        // `kind=`. `EveryRenderedInvocationIsAcceptedByItsDoorTest` drives every door with
        // what the product renders and fails on any door that would refuse it — it failed on
        // exactly these seven before step 6 and passes now.
        "hierarchy kind=pull_up_constructor_body",
        "no detector reports a subclass constructor assigning fields its SUPERCLASS declares."
            + " The nearest is `duplicated_code`, which compares bodies and would name the"
            + " assignments rather than the ownership — and ownership is this row's entire"
            + " rule: it moves a leading run of assignments to fields the parent declares,"
            + " which is a fact about who declares what and not about text repeating.");

    // FOUR hierarchy ENTRIES WERE DELETED AT S8b STEP 9 —
    // replace_type_code_with_subclasses, replace_superclass_with_delegate,
    // replace_subclass_with_delegate and collapse_hierarchy — because all four are routed
    // now, on type_code, composition_over_inheritance, refused_bequest and
    // speculative_generality. Only pull_up_constructor_body is left above, and it is the one
    // where nothing reports the shape.
    //
    // TWO OF THE FOUR WERE THIS SPRINT'S MEASURED INSTANCES OF THE TIER TRADE, one in each
    // direction, and the measurements survive their verdicts:
    //
    //   * replace_type_code_with_subclasses — `type_code` reports 9 findings over this
    //     bundle's 457 files (projectKey=jawata-mcp). The SCOPE belongs with the count: an
    //     unscoped call answers 10 over 466, the extra finding being a fixture in ANOTHER
    //     repository loaded in the same workspace. Two earlier versions of that sentence
    //     blamed fixture growth over time; a two-command experiment settled it — this bundle
    //     measures 9 today, exactly as it did then, and the two calls simply asked different
    //     questions. Every one of the nine messages names the SIBLING cure, so routing this
    //     one made a SECOND runnable cure, which under the old rule cost the smell its
    //     runnable instruction. That was the entry's whole argument, and step 3 retired it.
    //
    //   * replace_superclass_with_delegate — the mirror. `composition_over_inheritance`
    //     names this row in its own finding text, under Fowler's earlier title Replace
    //     Inheritance with Delegation, and declared TWO design-only cures, so it derived
    //     CONSIDER and this row was its FIRST runnable one. An upgrade where the entry above
    //     was a demotion. Demand real and entirely external: 0 over this bundle's 457 files,
    //     9 over the fork.
    //
    // Having both directions measured is what made the cost of a route legible rather than
    // anecdotal — and what made reversing the rule, rather than choosing between them, the
    // answer.

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
        // READ OFF `ocp`, NOT OFF `divergent_change`, and the difference stopped being
        // academic at S8b step 9. The three rows shared one constant, so either key rendered
        // the same sentence and the choice was arbitrary; then divergent_change gained row 64
        // (Split Phase), which is an `extract` kind and not a design on this axis — so
        // rendering from that row would have printed "refactor_to_pattern kind=… / extract
        // kind=split_phase", a call that names the wrong tool. `ocp` is the row whose members
        // ARE the OCP designs, which is what this sentence is about.
        return OCP_LEAD + " — refactor_to_pattern "
            + "kind=" + String.join(" / ", recipesFor("ocp")) + " "
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
