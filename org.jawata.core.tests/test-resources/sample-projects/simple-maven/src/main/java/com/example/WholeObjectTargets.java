package com.example;

/**
 * Fixtures for Fowler row 28, Preserve Whole Object, as
 * {@code change_method_signature kind=preserve_whole_object}.
 *
 * <p>The shape is several parameters every caller pulls out of ONE object. The refusals are a
 * caller unpacking two different objects, callers that disagree on an accessor, a call that
 * already passes the object, and a body that reads a folded parameter twice.</p>
 *
 * <p>No comment here quotes what the operation emits and none spells a declaration a test anchors
 * on — a fixture containing the string its own test searches for makes the test pass on the
 * comment, which three earlier stages of this sprint learned the hard way.</p>
 */
public class WholeObjectTargets {

    /** Fowler's own shape: both values come out of the same range, at every caller. */
    public boolean withinRange(int low, int high, int reading) {
        return reading >= low && reading <= high;
    }

    /** Its callers unpack TWO different objects, so there is no single whole object. */
    public boolean spans(int low, int high) {
        return high > low;
    }

    /** Its callers agree on one accessor and DISAGREE on the other. */
    public boolean contested(int low, int high) {
        return high >= low;
    }

    /** The caller already passes the range itself, which is the neighbouring row's shape. */
    public boolean alsoCarriesTheRange(Range range, int low, int high) {
        return range.reading() >= low && range.reading() <= high;
    }

    /** Reads one folded parameter twice, so an accessor would run twice where a value ran once. */
    public int weighted(int low, int high) {
        return low * low + high;
    }

    /**
     * Its object lives in ANOTHER PACKAGE, so folding it must ADD AN IMPORT to this file.
     *
     * <p>The neighbouring cases all unpack the nested {@code Range}, whose type is already
     * visible here — so they exercise the rewrite and not the import, which is the shape a
     * fixture takes when its author also wrote the rule.</p>
     */
    public boolean outside(int floor, int ceiling, int reading) {
        return reading < floor || reading > ceiling;
    }

    /** The object the values are unpacked from. */
    public static class Range {

        private final int low;
        private final int high;
        private final int reading;

        public Range(int low, int high, int reading) {
            this.low = low;
            this.high = high;
            this.reading = reading;
        }

        public int low() {
            return low;
        }

        public int high() {
            return high;
        }

        /** A SECOND plausible source for the same value — what makes disagreement possible. */
        public int ceiling() {
            return high;
        }

        public int reading() {
            return reading;
        }
    }
}
