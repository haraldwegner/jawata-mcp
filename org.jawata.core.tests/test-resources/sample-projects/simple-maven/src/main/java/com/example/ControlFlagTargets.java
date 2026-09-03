package com.example;

/**
 * Fixture for Replace Control Flag with Break (Sprint 28d-rescue, row 44).
 *
 * <p>One method must be rewritten. Four must not, and each fails a different one of the
 * four conditions — a flag that is read afterwards, an assignment that is not last, a
 * condition that is not simply the negated flag, and a flag not declared above its
 * loop. Each refusal is a case where the rewrite would change the program or would not
 * compile.</p>
 */
public class ControlFlagTargets {

    /** REWRITTEN: the flag carries nothing but the exit. */
    public int pureExit(int[] values) {
        int total = 0;
        int index = 0;
        boolean done = false;
        while (!done) {
            total += values[index];
            index++;
            done = true;
        }
        return total;
    }

    /**
     * NOT rewritten: the flag is READ after the loop, so it is carrying an answer.
     * Deleting it would delete the answer.
     */
    public String readAfterwards(int[] values) {
        int index = 0;
        boolean found = false;
        while (!found) {
            index++;
            found = true;
        }
        return found ? "found at " + index : "not found";
    }

    /**
     * NOT rewritten: the assignment is not the LAST statement, so replacing it with a
     * break would skip the work that follows it inside the same pass.
     */
    public int assignsThenWorks(int[] values) {
        int total = 0;
        boolean done = false;
        while (!done) {
            done = true;
            total += values[0];
        }
        return total;
    }

    /**
     * NOT rewritten: the condition is a conjunction, not the bare negated flag, so
     * replacing it with `true` would drop the other half of the test.
     */
    public int compoundCondition(int[] values) {
        int index = 0;
        boolean done = false;
        while (!done && index < values.length) {
            index++;
            done = true;
        }
        return index;
    }

    /**
     * NOT rewritten: the flag is not declared directly above its loop, so the rewrite
     * cannot show that nothing in between depends on it.
     */
    public int declaredFarAway(int[] values) {
        boolean done = false;
        int total = 0;
        int index = 0;
        while (!done) {
            total += values[index];
            index++;
            done = true;
        }
        return total;
    }
}
