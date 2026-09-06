package org.jawata.mcp.domain;

/**
 * Sprint 16b/D — the canonical quality/smell result of the JAWATA domain. A
 * {@link Detector} produces {@code Finding}s; {@link Findings#toResponse} renders
 * them to the MCP wire shape. This is the value type Sprint 17 (Fowler smells)
 * and Sprint 20 (SOLID) detectors emit — adding capability is registering a
 * detector that returns these, not adding a tool.
 *
 * <p>Coordinates are 1-based (JDT convention); use {@code -1} when N/A.</p>
 */
public record Finding(
    String kind,
    String filePath,
    int line,
    int column,
    String severity,
    String message,
    String symbol
) {
    // THE `cures` COMPONENT IS GONE, and its absence is the deliverable rather than a
    // simplification. S8b step 4 added it so a finding could carry executable steps, and
    // filled it from `AbstractAstDetector` — which meant the three detectors that do not
    // extend that class carried an empty list all the way to the caller, silently. C8b
    // measured that through the built product. The steps are now attached ON THE WIRE ROW,
    // once, on the dispatch path every detector reaches ({@link
    // org.jawata.mcp.tools.smell.Cures}), so there is no slot here for a producer to leave
    // unfilled. A detector's job is to find the thing, not to know what cures it, and this
    // record is now exactly that job.

    /** A warning-severity finding at a file/line with no column or symbol. */
    public static Finding warning(String kind, String filePath, int line, String message) {
        return new Finding(kind, filePath, line, -1, "warning", message, null);
    }
}
