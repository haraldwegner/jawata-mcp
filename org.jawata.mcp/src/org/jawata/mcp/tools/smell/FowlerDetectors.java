package org.jawata.mcp.tools.smell;

import org.jawata.mcp.domain.DetectorCatalog;

/**
 * Sprint 17 — registers the Fowler smell {@link org.jawata.mcp.domain.Detector}s
 * into a {@link DetectorCatalog}. SRP-separate from {@code QualityDetectors}
 * (which adapts the eight legacy lexical/structural analyzers); the
 * {@code find_quality_issue} front door projects the combined catalog, so each
 * smell registered here becomes a new {@code kind} with no new tool.
 *
 * <p>Detectors here implement {@link AbstractAstDetector}, so they receive the
 * project-scoped {@code IJdtService} at {@code detect(...)} time and need no
 * service supplier at construction.</p>
 */
public final class FowlerDetectors {

    private FowlerDetectors() {
    }

    /**
     * Register every Fowler detector into {@code catalog} (family {@code fowler});
     * returns it for chaining. Four kinds are also tagged {@code solid} (Sprint 20),
     * because the SOLID lens re-frames them rather than re-detecting:
     * {@code incomplete_delegation} (SRP — §7 unfinished encapsulation),
     * {@code refused_bequest} (LSP), and {@code divergent_change}/{@code shotgun_surgery}
     * (the OCP trace).
     *
     * <p>Sprint 28d added {@code store}: a detector's cure is an ENTRY in the
     * pattern catalogue, so a detector that cannot reach the store can only
     * state its cure as text. That degraded answer is honest and tested, but it
     * is not the product — and it is what production shipped for as long as a
     * storeless registration path existed. There is deliberately no
     * one-argument overload: it would be the silent default all over again, and
     * a caller with no store passes one that yields null, visibly.</p>
     *
     * <p>A supplier, not a store — for the idiom and for the shutdown read, NOT
     * because the store is unassigned at this point; it is assigned one line
     * before tools are registered. See
     * {@link OcpDetector#OcpDetector(java.util.function.Supplier)}.</p>
     */
    public static DetectorCatalog registerInto(
            DetectorCatalog catalog,
            java.util.function.Supplier<org.jawata.mcp.knowledge.ExperienceStore> store) {
        return catalog
            .register(new LongMethodDetector(), "fowler")
            .register(new GodClassDetector(), "fowler")
            .register(new LongParameterListDetector(), "fowler")
            .register(new DataClumpsDetector(), "fowler")
            .register(new FeatureEnvyDetector(), "fowler")
            .register(new MessageChainsDetector(), "fowler")
            .register(new InappropriateIntimacyDetector(), "fowler")
            .register(new MiddleManDetector(), "fowler")
            .register(new PrimitiveObsessionDetector(), "fowler")
            .register(new SwitchStatementsDetector(), "fowler")
            .register(new RefusedBequestDetector(), "fowler", "solid")
            .register(new TemporaryFieldDetector(), "fowler")
            .register(new LazyClassDetector(), "fowler")
            .register(new SpeculativeGeneralityDetector(), "fowler")
            .register(new ParallelInheritanceDetector(), "fowler")
            .register(new IncompleteDelegationDetector(), "fowler", "solid")
            .register(new DivergentChangeDetector(), "fowler", "solid")
            .register(new ShotgunSurgeryDetector(), "fowler", "solid")
            // Sprint 28d — Command Query Separation. Registered `fowler` because
            // its cure IS a Fowler refactoring (Separate Query from Modifier),
            // even though the principle it enforces is Meyer's.
            .register(new CqsDetector(), "fowler")
            // Sprint 28d — coupling reported as connascence. Registered `fowler`
            // for the same reason as `cqs`: the principle is Page-Jones's, but
            // its cures are Fowler refactorings (Move Method / Move Class /
            // Extract Class), which is what a reader of a finding reaches for.
            .register(new CouplingDetector(), "fowler")
            // Sprint 28d — prefer composition over inheritance. Same reasoning
            // again: the principle is the GoF's / Bloch's, the cure is Fowler's
            // Replace Inheritance with Delegation. Adjacent to refused_bequest
            // (also `fowler`), which owns the per-method reading.
            .register(new CompositionOverInheritanceDetector(), "fowler")
            // Sprint 28d — Open/Closed, as the NAME over measurements that
            // already existed: an aggregation of switch_statements + type_code,
            // whose cures are the Kerievsky recipes CureCatalog already maps.
            // Registered `fowler` so a fowler sweep carries it alongside the two
            // traces it re-labels, exactly as those traces are carried.
            .register(new OcpDetector(store), "fowler")
            // Sprint 28d — broken encapsulation, promoted from the on-demand
            // analyze(kind="encapsulation") audit to a sweep kind. `fowler`
            // because the cures are Encapsulate Field / Remove Setting Method.
            .register(new EncapsulationDetector(), "fowler")
            // Sprint 28d-rescue — Global Data (2nd ed. ch.3). Java has no global
            // variables, so the smell arrives as static mutable state, including the
            // static final collection whose reference is fixed and whose contents are
            // not. `fowler` because the cure is Encapsulate Variable.
            .register(new GlobalDataDetector(), "fowler")
            // Sprint 28d-rescue — Mutable Data, in the shape that has no setter to
            // find: an accessor that hands out a mutable field. Distinct from
            // `encapsulation`, which SEARCHES for external writers; this reads what a
            // class publishes, and reports the leak whether or not anyone took it.
            .register(new MutableDataDetector(), "fowler")
            // Sprint 28d-rescue — Loops and Data Class. Both finders already shipped
            // behind find_modernization, keyed by a MODERNIZATION name rather than by
            // the smell's, so a sweep looking for smells found neither. Adapted rather
            // than re-implemented: a second Data Class detector beside class_to_record
            // would be two answers to one question.
            .register(org.jawata.mcp.tools.ModernizationSmells.loops(), "fowler")
            .register(org.jawata.mcp.tools.ModernizationSmells.dataClass(), "fowler")
            // Sprint 28d-rescue — commented-out code, on Harald's ruling of 2026-09-02.
            // It reports a CANDIDATE and never dead code: commented-out code can still
            // carry meaning, and nothing removes it without a human yes.
            .register(new CommentedOutCodeDetector(), "fowler")
            // Sprint 28d-rescue — Alternative Classes with Different Interfaces. Nothing
            // else in the catalogue looks for it: a duplicate-code check compares BODIES,
            // and these two classes may share no code at all. Four conditions hold
            // together to keep it from reporting most of a codebase; see the class.
            .register(new AlternativeClassesDetector(), "fowler");
    }
}
