package com.example;

/**
 * Sprint 28d fixture — the <b>Command Query Separation</b> detector's clean
 * side. NOTHING in this file may be flagged: it holds a pure query, a pure
 * command, and one instance of each documented exclusion (fluent return,
 * previous-value protocol, lazy initialisation, deferred write, imposed
 * supertype signature).
 *
 * <p>Its companion {@link CqsTargets} fires non-zero, so a zero here is a
 * discrimination result rather than a silent detector.</p>
 */
public class CqsCleanTargets {

    private int count;
    private String label;
    private String cachedGreeting;
    private int deferred;

    /** Pure query — reads, changes nothing. */
    int count() {
        return count;
    }

    /** Pure command — changes, answers nothing. */
    void bump() {
        count = count + 1;
    }

    /** Exclusion: fluent setter returning {@code this} (the builder protocol). */
    CqsCleanTargets label(String value) {
        this.label = value;
        return this;
    }

    /** Exclusion: previous-value protocol — the {@code Map.put}/{@code getAndIncrement} shape. */
    int getAndBump() {
        int previous = count;
        count = count + 1;
        return previous;
    }

    /** Exclusion: lazy initialisation — a cache write guarded by a read of the same field. */
    String greeting() {
        if (cachedGreeting == null) {
            cachedGreeting = "hello " + label;
        }
        return cachedGreeting;
    }

    /** Exclusion: deferred write — the lambda mutates when RUN, not when this returns. */
    Runnable deferrer() {
        return () -> deferred = deferred + 1;
    }

    /**
     * Exclusion: an imposed supertype signature. {@code Iterator#next} must both
     * advance and answer; Separate Query from Modifier is not available to an
     * implementor, because the shape is not theirs to change.
     */
    static final class Counter implements java.util.Iterator<Integer> {

        private int at;

        @Override
        public boolean hasNext() {
            return at < 3;
        }

        @Override
        public Integer next() {
            return at++;
        }
    }
}
