package com.example;

/**
 * Sprint 28g S5 / D9 change 4 — the shape real code has: state spread across NULLABLE FIELDS,
 * branched on with null tests, with no state field and no enum anywhere.
 *
 * <p><strong>Measured off the sprint's own case rather than invented.</strong>
 * {@code execution.Slot} declares no {@code int} state field and no state enum: its state lives
 * in {@code position}, {@code pendingEntry}, {@code activeOrder}, {@code algo},
 * {@code tpOrder}, {@code tpOrderCancelling}, {@code heldPlacement}, {@code freedByStop} and
 * {@code flattenTerms}, and is read through predicates — {@code isFree()},
 * {@code hasPosition()}, {@code isExitInProgress()}, {@code holdsUnsentEntry()}. This fixture
 * carries three of those fields under their own names and the same branching shape, so the row
 * is exercised on the structure that produced it rather than on a switch nobody wrote.</p>
 *
 * <p>It lives in the dead-api project because it needs no project of its own — nothing here
 * counts members, and the two fixtures do not interact.</p>
 */
public class Occupancy {

    private String pendingEntry;
    private String position;
    private String exitOrder;

    /** State only this object has, read by the branch bodies. */
    private int ticks;

    /**
     * THE CASE. Three states, told apart by which fields are set, in a chain that is the whole
     * method — which is what lets it become one delegating call.
     */
    public void advance(int by) {
        if (pendingEntry == null && position == null) {
            ticks += by;
        } else if (position != null && exitOrder == null) {
            ticks += by * 2;
        } else {
            ticks = 0;
        }
    }

    /**
     * REFUSED — the first branch assigns {@code position}, which a later condition tests, so the
     * object changes state inside the state's own method. Which state it becomes is a
     * transition, and moving a transition into the state being left is a design decision.
     */
    public void advanceAndTransition(int by) {
        if (pendingEntry == null && position == null) {
            position = "opened";
        } else if (position != null) {
            ticks += by;
        } else {
            ticks = 0;
        }
    }

    /**
     * REFUSED — {@code ticks > 0} is a comparison rather than a null test. It encodes state some
     * other way, and the generated selector evaluates each condition a SECOND time, so only a
     * test that cannot answer differently or have a side effect qualifies.
     */
    public void advanceOnComparison(int by) {
        if (ticks > 0) {
            ticks += by;
        } else if (position != null) {
            ticks = by;
        } else {
            ticks = 0;
        }
    }

    public int ticks() {
        return ticks;
    }

    public void occupy(String name) {
        this.position = name;
    }
}
