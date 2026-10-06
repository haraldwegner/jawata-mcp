package com.example;

import com.example.sink.Port;

/**
 * Sprint 28g S5 / D9 row 2 — a moved body that STILL REACHES the type it came from.
 *
 * <p>The signed spec scopes this change at <strong>sixteen</strong> hand edits, covering the
 * package-rule half with it; the census attributes <strong>eleven</strong> to this cause
 * specifically, in its own summary figure — <em>"the moved body's access to its old owner
 * decided silently (11)"</em>. This is the shape they are counting, and that
 * <strong>no fixture in this repository had</strong>, which is why the defect could ship:
 * {@link Mover#reset} — the only move fixture here — has a body that reaches nothing of
 * {@code Mover}, so the engine's {@code needsTargetNode()} answers false and the question is
 * never asked. The census names the defect in the tool's own verdict line: <em>"how the moved
 * body reaches its old owner is MoveInstanceMethodProcessor's SILENT choice"</em>, and the cost
 * beside it — JDT adds the old owner as a hidden parameter, because <em>"`execution` may not
 * name `pipeline`"</em>.</p>
 *
 * <p>{@link Mover} is left byte-identical on purpose: its four tests are the control that the
 * default-refuse added here changes nothing for a body that never needed its old owner.</p>
 */
public class Pipeline {

    /** State only the OLD owner has, which is what makes the moved body need it. */
    private int budget = 100;

    public int getBudget() {
        return budget;
    }

    /**
     * MOVES onto {@code port}, and its body still reads {@link #budget} — so the old owner has
     * to arrive somehow, which is the decision this row exists to surface rather than make.
     */
    public String dispatch(Port port, String reason) {
        return port.id() + ":" + reason + ":" + budget;
    }

    /**
     * THE CONTROL, and it is the half that makes the other case mean anything: this body reaches
     * nothing of the old owner, so no question arises and the move must proceed untouched. A tool
     * that asked unconditionally would refuse every move in the product.
     */
    public String describe(Port port, String reason) {
        return port.id() + ":" + reason;
    }
}
