package com.example;

/**
 * Sprint 28d fixture — the <b>Command Query Separation</b> detector's
 * PROOF OF LIFE. Every method here is a genuine CQS violation: it writes a
 * field of its own object <em>and</em> answers a value, so a caller cannot
 * ask the question without also causing the change.
 *
 * <p>Three methods must be flagged: {@code withdraw}, {@code register},
 * {@code tick}. The legitimate shapes that must NOT be flagged live next
 * door in {@link CqsCleanTargets} — this file exists so that a zero over
 * there is evidence the detector is discriminating, rather than evidence
 * that it never fires at all.</p>
 */
public class CqsTargets {

    private int balance;
    private int seen;
    private String lastName;
    private long ticks;

    /** FLAGGED — assigns {@code balance} and answers the new balance. */
    int withdraw(int amount) {
        balance = balance - amount;
        return balance;
    }

    /** FLAGGED — records the caller's name and answers how many have been seen. */
    int register(String name) {
        lastName = name;
        seen = seen + 1;
        return seen;
    }

    /** FLAGGED — post-increment write, then answers the counter. */
    long tick() {
        ticks++;
        return ticks;
    }

    /** Not flagged — a pure query over the recorded name. */
    String lastName() {
        return lastName;
    }
}
