package org.goja.mcp.domain;

import org.goja.core.IJdtService;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Sprint 16b/D — default {@link IGojaService}. The JDT bridge is supplied lazily
 * (the resident installs the workspace service asynchronously after startup), so
 * {@link #jdt()} resolves the current service on each call; the catalog is fixed
 * at construction and mutated by registration (Sprints 17–21).
 */
public final class GojaService implements IGojaService {

    private final Supplier<IJdtService> jdt;
    private final DetectorCatalog detectors;

    public GojaService(Supplier<IJdtService> jdt, DetectorCatalog detectors) {
        this.jdt = Objects.requireNonNull(jdt, "jdt supplier");
        this.detectors = Objects.requireNonNull(detectors, "detectors");
    }

    @Override
    public IJdtService jdt() {
        return jdt.get();
    }

    @Override
    public DetectorCatalog detectors() {
        return detectors;
    }
}
