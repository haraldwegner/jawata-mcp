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
    String symbol,
    java.util.List<org.jawata.mcp.models.NextStep> cures
) {
    /**
     * The seven-argument form every detector uses — cures are attached LATER.
     *
     * <p>A detector's job is to find the thing, not to know what cures it: the cure
     * table is one place and {@code AbstractAstDetector.withCures} is the one site that
     * consults it. So a detector constructs without cures and the base fills them in,
     * which is also why widening this record touched no detector.</p>
     */
    public Finding(String kind, String filePath, int line, int column, String severity,
                   String message, String symbol) {
        this(kind, filePath, line, column, severity, message, symbol, java.util.List.of());
    }

    /** The same finding with its resolved next steps attached. */
    public Finding withCures(java.util.List<org.jawata.mcp.models.NextStep> steps) {
        return new Finding(kind, filePath, line, column, severity, message, symbol, steps);
    }

    /** A warning-severity finding at a file/line with no column or symbol. */
    public static Finding warning(String kind, String filePath, int line, String message) {
        return new Finding(kind, filePath, line, -1, "warning", message, null);
    }
}
