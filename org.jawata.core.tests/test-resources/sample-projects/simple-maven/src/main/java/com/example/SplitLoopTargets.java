package com.example;

import java.util.List;

/**
 * Fixture for Split Loop (Sprint 28d-rescue, row 63).
 *
 * <p>One loop must split and three must not. Each refusal is a case where the second pass
 * would not compute what the single pass did.</p>
 */
public class SplitLoopTargets {

    /** SPLIT: two independent accumulations sharing one walk. */
    public int twoJobs(List<Calculator> people) {
        int total = 0;
        int count = 0;
        for (Calculator person : people) {
            total += person.multiply(2, 3);
            count++;
        }
        return total + count;
    }

    /**
     * NOT split: the second statement reads what the first writes, so a second pass
     * would see the finished total rather than the running one.
     */
    public int halvesTalk(List<Calculator> people) {
        int total = 0;
        int doubled = 0;
        for (Calculator person : people) {
            total += person.multiply(2, 3);
            doubled = total * 2;
        }
        return total + doubled;
    }

    /**
     * NOT split: the halves talk THROUGH A CALL. `seen` is changed by add() and read by
     * the second statement, and a model that sees only assignment sees no write here at
     * all — which is how this shape passed the independence check before.
     */
    public int halvesTalkThroughACall(List<Calculator> people) {
        java.util.List<Calculator> seen = new java.util.ArrayList<>();
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        for (Calculator person : people) {
            seen.add(person);
            sizes.add(seen.size());
        }
        return sizes.size();
    }

    /**
     * NOT split: a break. Each half would need its own copy of an exit whose meaning was
     * defined over the whole body.
     */
    public int stopsEarly(List<Calculator> people) {
        int total = 0;
        int count = 0;
        for (Calculator person : people) {
            if (count > 2) {
                break;
            }
            count++;
        }
        return total + count;
    }

    /**
     * NOT split: an Iterable may be one-shot, and walking it twice would silently yield
     * nothing the second time.
     */
    public int overAnIterable(Iterable<Calculator> people) {
        int total = 0;
        int count = 0;
        for (Calculator person : people) {
            total += person.multiply(2, 3);
            count++;
        }
        return total + count;
    }
}
