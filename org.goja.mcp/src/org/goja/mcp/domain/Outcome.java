package org.goja.mcp.domain;

import java.util.List;

/**
 * Sprint 18 — the value type the orchestration loop emits at the end of a
 * refactoring cycle: what was attempted, on what, how it ended, and any notes
 * (purity flags, reclassifications, surprises). This is the <b>write</b> side of
 * the learning seam ({@link Advisor#record}): Sprint 18 emits it; Sprint 21's
 * knowledge store will consume it. Deliberately minimal — no persistence, no
 * schema; just the emit point.
 *
 * <p>{@code status} is one of {@link #APPLIED} / {@link #ROLLED_BACK} /
 * {@link #FLAGGED}. {@code undoChangeId} is {@code null} when nothing was left
 * applied (planning-only, or rolled back to a clean base).</p>
 */
public record Outcome(
    String operation,
    String kind,
    String target,
    String status,
    List<String> filePaths,
    String undoChangeId,
    List<String> notes
) {
    /** The plan applied cleanly and (unless undone) remains in the workspace. */
    public static final String APPLIED = "applied";
    /** A step failed the parity/purity gate; the whole plan was rolled back. */
    public static final String ROLLED_BACK = "rolled_back";
    /** The purity check surfaced a concern for the caller to resolve. */
    public static final String FLAGGED = "flagged";

    public Outcome {
        filePaths = filePaths == null ? List.of() : List.copyOf(filePaths);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
