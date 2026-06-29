package org.goja.mcp.tools.smell;

import org.goja.mcp.domain.DetectorCatalog;

/**
 * Sprint 17 — registers the Fowler smell {@link org.goja.mcp.domain.Detector}s
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
            .register(new ShotgunSurgeryDetector(), "fowler", "solid");
    }
}
