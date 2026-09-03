package com.example;

/**
 * Fixture for the commented-out-code check (Sprint 28d-rescue).
 *
 * <p>This Javadoc is never examined, which is part of what the test pins: documentation
 * is documentation, and a sample inside it is an example on purpose. For instance
 * {@code int x = compute(); return x;} appears here and must not be reported.</p>
 */
public class CommentedOutTargets {

    public int reported() {
        // int leftOver = compute();
        // return leftOver + 1;
        return compute();
    }

    public int alsoReported() {
        /*
         * if (compute() > 0) {
         *     throw new IllegalStateException("was tried once");
         * }
         */
        return 0;
    }

    // NOT reported: prose, however punctuated. This sentence ends in a semicolon;
    // and this one mentions compute() by name, which a pattern would have caught.
    public int notReported() {
        // TODO: decide whether this should round up
        // see also: the discussion about overflow
        return compute();
    }

    // NOT reported: a word with a semicolon after it parses, and does no work.
    // x;
    public int stillNotReported() {
        return compute() * 2;
    }

    private int compute() {
        return 41;
    }
}
