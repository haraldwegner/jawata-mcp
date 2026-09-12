package com.example;

/** Scenery for the controls. */
public class Money {

    private final long amount;

    private Money(long amount) {
        this.amount = amount;
    }

    public static Money of(long amount) {
        return new Money(amount);
    }

    public long amount() {
        return amount;
    }
}
