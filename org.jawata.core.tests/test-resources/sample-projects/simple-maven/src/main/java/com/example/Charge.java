package com.example;

/**
 * The parent in the Remove Subclass fixture (Sprint 28d-rescue, row 38). Concrete.
 *
 * <p>This javadoc links {@link PlainCharge}, which row 38 DELETES — a dangling link no
 * compile gate can see, because javadoc is a comment. The sentence after it names
 * PlainCharge again as ORDINARY PROSE, and the two must be treated differently: a link is
 * a machine-checkable reference and is repaired; prose is somebody's writing, and guessing
 * which mentions meant the type is exactly what a rewriter must not do.</p>
 */
public class Charge {

    private final int amount;

    public Charge(int amount) {
        this.amount = amount;
    }

    public int amount() {
        return amount;
    }
}
