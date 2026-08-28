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
     */
    public static DetectorCatalog registerInto(DetectorCatalog catalog) {
        return registerInto(catalog, () -> null);
    }

    /**
     * The store-aware form: the same registration, with the experience store the
     * cure-carrying detectors resolve their addresses from.
     *
     * <p>Sprint 28d. A detector's cure is an ENTRY in the pattern catalogue, so
     * a detector that cannot reach the store can only state its cure as text.
     * That degraded answer is honest and tested, but it is not the product —
     * and it is what production shipped until this overload existed, because
     * the one-argument form above was the only registration path and it had no
     * store to give.</p>
     *
     * <p>A supplier, not a store: the catalog is built during application
     * assembly, before the store field is guaranteed assigned. See
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
            // whose cures are the Kerievsky recipes RecipeCatalog already maps.
            // Registered `fowler` so a fowler sweep carries it alongside the two
            // traces it re-labels, exactly as those traces are carried.
            .register(new OcpDetector(store), "fowler")
            // Sprint 28d — broken encapsulation, promoted from the on-demand
            // analyze(kind="encapsulation") audit to a sweep kind. `fowler`
            // because the cures are Encapsulate Field / Remove Setting Method.
            .register(new EncapsulationDetector(), "fowler");
    }
}
