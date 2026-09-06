package com.example;

/**
 * ROW 29 REFUSES this: its superclass is {@code java.lang.IllegalStateException}, which has no
 * source in the workspace — so there is no constructor to add the pulled assignments TO.
 *
 * <p>It is the shape the refusal was written for and the one no other fixture has: every other
 * constructor-body fixture extends a class declared beside it, where the superclass is always
 * reachable.</p>
 */
public class ErroringTargets extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public ErroringTargets(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
