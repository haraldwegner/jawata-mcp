package com.example;

/**
 * The parent in the Remove Subclass fixture (Sprint 28d-rescue, row 38). Concrete.
 *
 * <p>This javadoc links {@link PlainCharge}, which row 38 DELETES — a dangling link no
 * compile gate can see, because javadoc is a comment.</p>
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
