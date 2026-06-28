package org.goja.mcp.domain;

import org.goja.core.IJdtService;

/**
 * Sprint 16b/D — GOJA's own engineering abstraction, the seam tools call instead
 * of reaching for raw JDT. It exposes the low-level JDT bridge ({@link #jdt()})
 * plus the domain registries (the {@link DetectorCatalog}; transform/target
 * catalogs land in later sprints). This is the layer that lets a future
 * clean-room of the residual MIT base be a swap, and the place Sprints 17–21
 * extend by registering kinds rather than adding tools.
 */
public interface IGojaService {

    /** The low-level, project-scoped JDT bridge. */
    IJdtService jdt();

    /** The quality/smell detector registry projected by the Smell front door. */
    DetectorCatalog detectors();
}
