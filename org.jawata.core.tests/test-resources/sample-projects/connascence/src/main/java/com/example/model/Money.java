package com.example.model;

/**
 * Connascence kept WITHIN its package — the pole Page-Jones's rule wants to
 * maximise. Every counterpart of every site here lives in com.example.model.
 */
public final class Money {

    private final long cents;

    public Money(long cents) {
        this.cents = cents;
    }

    public long cents() {
        return cents;
    }

    /** Within-package Connascence of Type + a within-package creation. */
    public Money plus(Money other) {
        return new Money(cents + other.cents());
    }
}
