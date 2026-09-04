package com.example;

/** The parent in the Remove Subclass fixture (Sprint 28d-rescue, row 38). Concrete. */
public class Charge {

    private final int amount;

    public Charge(int amount) {
        this.amount = amount;
    }

    public int amount() {
        return amount;
    }
}
