package com.example;

import java.util.ArrayList;

/**
 * Fixture for Remove Dead Code (Sprint 28d-rescue, row 34).
 *
 * <p>Every member here is either something the rule must remove or something it must
 * leave alone, and the ones it must leave alone are the point: the import above is
 * unused, {@code redundantCast} holds an unnecessary cast, and {@code used} has a
 * parameter nobody reads. All three are things JDT's engine WOULD take if the rule
 * asked it to, and all three belong to other operations.</p>
 */
public class DeadCodeTargets {

    /** Unused private field — removed. */
    private int neverRead;

    /** Read by note() — survives. */
    private String kept = "kept";

    /** Counts calls; read below, so it survives. */
    private int calls;

    /** Unused private type — removed. */
    private static final class NeverUsed {
        int x;
    }

    /** Unused private method — removed. */
    private String obsolete() {
        return "gone";
    }

    /**
     * Called from note(), so the method survives — and {@code ignored} is read nowhere,
     * which is the unused-parameter case the rule must NOT act on.
     */
    private String used(String label, int ignored) {
        return label + kept;
    }

    public String note() {
        return used("n", 1);
    }

    /**
     * Two unused locals. {@code plain} has no side effect and goes entirely;
     * {@code fromCall} initialises from a call, so the call must survive as a statement.
     */
    public int locals() {
        int plain = 3;
        int fromCall = compute();
        return 7;
    }

    /** An unnecessary cast — not dead code, and it must still be here afterwards. */
    public String redundantCast() {
        String s = "text";
        return (String) s;
    }

    public int compute() {
        calls++;
        return calls;
    }
}
