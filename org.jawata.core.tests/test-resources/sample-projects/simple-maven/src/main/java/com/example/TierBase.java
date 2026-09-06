package com.example;

/**
 * ROW 4's PARENT — concrete on purpose, so nothing about an abstract parent is in play in the
 * performing case. It also serves the NO_SUPERCLASS refusal, since it extends nothing itself.
 */
public class TierBase {

    private final String name;

    public TierBase(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
