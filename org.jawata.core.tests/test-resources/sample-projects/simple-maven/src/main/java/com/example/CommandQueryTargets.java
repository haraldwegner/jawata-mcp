package com.example;

/**
 * Fixtures for Fowler row 61, Separate Query from Modifier, as
 * {@code change_method_signature kind=separate_query_from_modifier}.
 *
 * <p>The shape this row performs is a method that changes state and hands back the state it just
 * changed. The refusals are a method that answers something COMPUTED rather than a field it
 * wrote, one that answers nothing at all, and one that answers a field it never writes.</p>
 *
 * <p>No comment here quotes what the operation emits, and none of them spells a declaration a
 * test anchors on — a fixture that contains the string its own test searches for makes the test
 * pass on the comment, which two earlier stages of this sprint learned the hard way.</p>
 */
public class CommandQueryTargets {

    private int failures;
    private int untouched = 7;

    /** Changes state and hands back what it changed: the shape this row separates. */
    public int recordFailure() {
        failures = failures + 1;
        return failures;
    }

    /**
     * Also changes state, and answers something ASSEMBLED from locals rather than a field.
     * Separating this one would need the body run a second time, which is the refusal.
     */
    public String describeFailure(String reason) {
        failures = failures + 1;
        String label = reason.trim();
        return label + "/" + label.length();
    }

    /** Answers a field it never writes, so it is already a query and there is nothing to split. */
    public int currentUntouched() {
        return untouched;
    }

    /** Answers nothing, so it is already a command. */
    public void clear() {
        failures = 0;
    }
}
