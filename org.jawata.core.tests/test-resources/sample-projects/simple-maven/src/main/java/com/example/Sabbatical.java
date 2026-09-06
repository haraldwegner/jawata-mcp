package com.example;

/**
 * Row 59 REFUSES this — no group of two or more constants sharing a prefix, which is what a
 * type code looks like. One lone constant is not a code.
 */
public class Sabbatical {

    public static final int TERM_SINGLE = 1;

    private final int term;

    public Sabbatical(int term) {
        this.term = term;
    }

    public int term() {
        return term;
    }
}
