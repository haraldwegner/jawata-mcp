package org.jawata.mcp.refactoring;

import java.util.List;

/**
 * Sprint 18 — a staged multi-step refactoring plan held in the {@link PlanStore}.
 * Immutable except for {@link #appliedThrough()}, which the loop (Stage 5) advances
 * as steps commit so {@code inspect_plan} / {@code undo_plan} (Stage 6) can report
 * and unwind the applied prefix. {@code advice} carries any notes the {@link
 * org.jawata.mcp.domain.Advisor} returned at plan time (empty until Sprint 21 fills
 * the seam).
 */
public final class Plan {

    private final String planId;
    private final String kind;
    private final String target;
    private final List<PlanStep> steps;
    private final List<String> advice;
    private final long insertedAtMillis;
    private int appliedThrough = -1;
    private String undoChangeId;

    public Plan(String planId, String kind, String target, List<PlanStep> steps,
                List<String> advice, long insertedAtMillis) {
        this.planId = planId;
        this.kind = kind;
        this.target = target;
        this.steps = List.copyOf(steps);
        this.advice = advice == null ? List.of() : List.copyOf(advice);
        this.insertedAtMillis = insertedAtMillis;
    }

    public String planId() { return planId; }
    public String kind() { return kind; }
    public String target() { return target; }
    public List<PlanStep> steps() { return steps; }
    public List<String> advice() { return advice; }
    public long insertedAtMillis() { return insertedAtMillis; }

    /** Index of the last committed step ({@code -1} = nothing applied yet). */
    public int appliedThrough() { return appliedThrough; }
    public void appliedThrough(int index) { this.appliedThrough = index; }

    /** The composed plan-level undo handle, set by apply_plan on success ({@code null} until then / after undo). */
    public String undoChangeId() { return undoChangeId; }
    public void undoChangeId(String id) { this.undoChangeId = id; }
}
