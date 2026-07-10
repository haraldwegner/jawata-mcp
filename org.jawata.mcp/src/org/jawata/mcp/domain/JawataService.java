package org.jawata.mcp.domain;

import org.jawata.core.IJdtService;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Sprint 16b/D — default {@link IJawataService}. The JDT bridge is supplied lazily
 * (the resident installs the workspace service asynchronously after startup), so
 * {@link #jdt()} resolves the current service on each call; the catalog is fixed
 * at construction and mutated by registration (Sprints 17–21).
 */
public final class JawataService implements IJawataService {

    private final Supplier<IJdtService> jdt;
    private final DetectorCatalog detectors;
    private final Advisor advisor;

    /** Convenience: a service with the default {@link NoOpAdvisor} (no knowledge store). */
    public JawataService(Supplier<IJdtService> jdt, DetectorCatalog detectors) {
        this(jdt, detectors, new NoOpAdvisor());
    }

    public JawataService(Supplier<IJdtService> jdt, DetectorCatalog detectors, Advisor advisor) {
        this.jdt = Objects.requireNonNull(jdt, "jdt supplier");
        this.detectors = Objects.requireNonNull(detectors, "detectors");
        this.advisor = Objects.requireNonNull(advisor, "advisor");
    }

    @Override
    public IJdtService jdt() {
        return jdt.get();
    }

    @Override
    public DetectorCatalog detectors() {
        return detectors;
    }

    @Override
    public Advisor advisor() {
        return advisor;
    }
}
