package org.goja.mcp.domain;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;

/**
 * Sprint 16b/D — a registered quality/smell analysis, addressed by {@code kind}.
 * Detectors are the unit of capability behind the Smell front door: the
 * {@code find_quality_issue} tool's {@code kind} enum and dispatch are a
 * <em>projection</em> of the {@link DetectorCatalog}, so Sprint 17/20 add a
 * detector (a kind), never a new tool or service.
 *
 * <p>Returns a {@link ToolResponse} for transport. New (Fowler/SOLID) detectors
 * compute {@code List<Finding>} internally and return
 * {@link Findings#toResponse}; the built-in detectors adapt the existing narrow
 * analyzers.</p>
 */
public interface Detector {

    /** The unique kind key, e.g. {@code "naming"}. Drives the tool's enum + dispatch. */
    String kind();

    /** One-line human description, surfaced in the tool's documentation. */
    String description();

    /** Run the analysis against the (already project-scoped) service. */
    ToolResponse detect(IJdtService service, JsonNode arguments);
}
