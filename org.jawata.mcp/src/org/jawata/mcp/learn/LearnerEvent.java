package org.jawata.mcp.learn;

/**
 * One immediate label signal for the injector's learners (Sprint 26). The
 * continuous-learning contract: labels arrive AS THEY HAPPEN — a tool error,
 * an undo, a mechanical touch, a gate call, a compile failure on a touched
 * file — never batched, never waiting for an offline cycle.
 *
 * @param sessionId WHO the signal belongs to, as a caller-identity id — a session the
 *     client supplied, one this resident minted per transport, or {@code "in-process"}
 *     for a direct call. NOT {@code "local"}: that literal is what the resident used to
 *     manufacture, and Sprint 28g S9a M4 removed it. A hired run (see
 *     {@link #KIND_HIRED_RUN}) puts its PLAN id here instead, because nobody was in a
 *     session at all — which is the case this field’s original wording could not describe.
 * @param kind one of the {@code KIND_*} constants
 * @param tool the tool whose call produced the signal
 * @param detailJson free-form JSON detail (small — counts, flags, file lists)
 */
public record LearnerEvent(String sessionId, String kind, String tool, String detailJson) {

    /** A tool returned a structured error (the resident's own failure — also a defect signal). */
    public static final String KIND_TOOL_ERROR = "tool_error";
    /** A refactoring undo was requested — the immediate revert label. */
    public static final String KIND_UNDO = "undo";
    /** A mechanical transform touched files (rename/move/… — the exempt set). */
    public static final String KIND_MECHANICAL_TOUCH = "mechanical_touch";
    /** A gate tool ran (compile/diagnostics/tests) — carries its outcome. */
    public static final String KIND_GATE_CALL = "gate_call";
    /** A gate reported errors while mechanically-touched files were pending — the compile-after-touch failure label. */
    public static final String KIND_COMPILE_AFTER_TOUCH_FAIL = "compile_after_touch_fail";
    /** A watch finding was emitted (Stage 2 writes these; fate arrives as a later event). */
    public static final String KIND_WATCH_FINDING = "watch_finding";
    /** An observed .java edit arrived (observe_edit) — held pending until its consequence. */
    public static final String KIND_EDIT_OBSERVED = "edit_observed";
    /**
     * Sprint 28g S9a M5a: a hired brain ran. The row is what makes an unattended
     * run answerable afterwards — nobody was watching it happen.
     */
    public static final String KIND_HIRED_RUN = "hired_run";

    /** Pending edits were resolved by their consequence (a gate outcome or an undo). */
    public static final String KIND_EDIT_RESOLVED = "edit_resolved";
    // Dogfood probe 2026-07-19: this comment exercises the live edit feed.
}
