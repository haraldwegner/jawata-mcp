package com.example;

/**
 * Fixture for Consolidate Conditional Expression (Sprint 28d-rescue, row 7).
 *
 * <p>Two methods must be consolidated and three must be left exactly as they are. The
 * refusals are where the danger is: joining conditions with {@code ||} makes later ones
 * stop being evaluated, so a condition that DOES something must never be joined.</p>
 */
public class ConsolidateTargets {

    private int calls;

    /** CONSOLIDATED: three checks, one outcome, all conditions plain reads. */
    public int disabilityAmount(int seniority, int monthsDisabled, boolean partTime) {
        if (seniority < 2) {
            return 0;
        }
        if (monthsDisabled > 12) {
            return 0;
        }
        if (partTime) {
            return 0;
        }
        return 100;
    }

    /** CONSOLIDATED: two checks whose bodies are the same throw. */
    public int refuse(int a, int b) {
        if (a < 0) {
            throw new IllegalArgumentException("negative");
        }
        if (b < 0) {
            throw new IllegalArgumentException("negative");
        }
        return a + b;
    }

    /**
     * NOT consolidated: the second condition CALLS something. Joined with ||, it would
     * stop being called whenever the first holds — and this one counts its calls.
     */
    public int countsItsChecks(int value) {
        if (value < 0) {
            return 0;
        }
        if (expensiveCheck(value)) {
            return 0;
        }
        return value;
    }

    /** NOT consolidated: the bodies differ, so these are two decisions and not one. */
    public int differentBodies(int a, int b) {
        if (a < 0) {
            return -1;
        }
        if (b < 0) {
            return -2;
        }
        return a + b;
    }

    /**
     * NOT consolidated: the second condition increments. The same short-circuit argument
     * as the call above, in the shape that looks harmless.
     */
    public int increments(int value) {
        int local = value;
        if (local < 0) {
            return 0;
        }
        if (local++ > 10) {
            return 0;
        }
        return local;
    }

    private boolean expensiveCheck(int value) {
        calls++;
        return value > 100;
    }

    public int callCount() {
        return calls;
    }
}
