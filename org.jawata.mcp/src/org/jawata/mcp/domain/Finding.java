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
    /** A warning-severity finding at a file/line with no column or symbol. */
    public static Finding warning(String kind, String filePath, int line, String message) {
        return new Finding(kind, filePath, line, -1, "warning", message, null);
    }
}
