package com.example;

/**
 * Fixture for Return Modified Value (Sprint 28d-rescue, row 60).
 *
 * <p>Two methods must be converted and five must be left exactly as they are. Every
 * refusal below is a way the rewrite would change behaviour while still compiling, which
 * is the only kind of defect this rule can produce.</p>
 */
public class ReturnModifiedValueTargets {

    private int calls;

    /** CONVERTED: the variable exists only to carry the answer. */
    public String describe(int n) {
        String result;
        if (n < 0) {
            result = "negative";
        } else {
            result = "positive";
        }
        return result;
    }

    /** CONVERTED: nested branches, every path assigns, every assignment is last. */
    public int band(int n) {
        int result;
        if (n < 10) {
            result = 1;
        } else {
            if (n < 100) {
                result = 2;
            } else {
                result = 3;
            }
        }
        return result;
    }

    /**
     * NOT converted: the assignment is not the last statement of its branch. Returning
     * early here would skip the call — and the result would compile and pass any test
     * that does not assert on the counter.
     */
    public String logsAfterAssigning(int n) {
        String result;
        if (n < 0) {
            result = "negative";
            note();
        } else {
            result = "positive";
        }
        return result;
    }

    /**
     * NOT converted: one path leaves the variable at its initial value, so the trailing
     * return is still reachable and there would be nothing to return.
     */
    public String fallsThrough(int n) {
        String result = "unknown";
        if (n < 0) {
            result = "negative";
        }
        return result;
    }

    /** NOT converted: the variable is READ before the return, so it carries information. */
    public String readsItself(int n) {
        String result;
        if (n < 0) {
            result = "negative";
        } else {
            result = "positive";
        }
        if (result.isEmpty()) {
            note();
        }
        return result;
    }

    /**
     * NOT converted: the assignment accumulates inside a loop. That is a running total,
     * not the method answering a question, and Fowler's other rows own the shape.
     */
    public int accumulates(int[] values) {
        int result = 0;
        for (int value : values) {
            result = result + value;
        }
        return result;
    }

    /**
     * NOT converted: an if with no else. The missing path falls to the return, which is
     * the same danger as fallsThrough with the initializer removed.
     */
    public String noElse(int n) {
        String result = "unknown";
        if (n < 0) {
            result = "negative";
        }
        note();
        return result;
    }

    private void note() {
        calls++;
    }

    public int callCount() {
        return calls;
    }
}
