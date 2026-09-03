package com.example;

/**
 * Fixture for Replace Nested Conditional with Guard Clauses (Sprint 28d-rescue, row 52).
 *
 * <p>Two methods must be rewritten and three must be left exactly as they are. The
 * refusals are the interesting half: each is a shape where dropping the {@code else}
 * would change what the program does, and a version of this rewrite that did not check
 * would be a corrupting one rather than a noisy one.</p>
 */
public class GuardClauseTargets {

    /** REWRITTEN: the then-branch returns, so the else is redundant nesting. */
    public int nestedReturns(boolean dead, boolean separated) {
        if (dead) {
            return 1;
        } else {
            if (separated) {
                return 2;
            } else {
                return 3;
            }
        }
    }

    /** REWRITTEN: a throw leaves the method just as a return does. */
    public int throwsThenElse(boolean broken, int value) {
        if (broken) {
            throw new IllegalStateException("cannot go on");
        } else {
            return value * 2;
        }
    }

    /**
     * NOT rewritten: the then-branch FALLS THROUGH. Dropping the else here would run
     * both bodies when the condition holds, which is a different program.
     */
    public int fallsThrough(boolean flag, int value) {
        int result;
        if (flag) {
            result = value + 1;
        } else {
            result = value - 1;
        }
        return result;
    }

    /**
     * NOT rewritten: an else-if chain. Unwrapping one level of this produces a shape a
     * reader has to re-derive, and the chain is already the honest form.
     */
    public int chain(int value) {
        if (value < 0) {
            return -1;
        } else if (value == 0) {
            return 0;
        } else {
            return 1;
        }
    }

    /**
     * NOT rewritten: the then-branch's exit is a {@code break}, which leaves the LOOP and
     * not the method — so the else's statements would still run afterwards.
     */
    public int breaksOutOfLoop(int[] values) {
        int total = 0;
        for (int value : values) {
            if (value < 0) {
                break;
            } else {
                total += value;
            }
        }
        return total;
    }
}
