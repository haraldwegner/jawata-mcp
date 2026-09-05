package com.example;

/**
 * Fixtures for Fowler row 53, Replace Parameter with Query, as
 * {@code change_method_signature kind=replace_parameter_with_query}.
 *
 * <p>The shape is a parameter every caller derives the same way from another argument of the
 * same call. The refusal is a method whose callers derive it DIFFERENTLY — one of them passing
 * something the query cannot reproduce.</p>
 *
 * <p>No comment here quotes what the operation emits, and none of them spells a declaration a
 * test anchors on — a fixture that contains the string its own test searches for makes the test
 * pass on the comment, which two earlier stages of this sprint learned the hard way.</p>
 */
public class DerivedParameterTargets {

    /** Every caller derives {@code rate} from the same order it also passes. */
    public double discounted(Order order, int rate) {
        return order.total() * (100 - rate) / 100.0;
    }

    /** Its callers DISAGREE — one derives the rate, one passes a number of its own. */
    public double contested(Order order, int rate) {
        return order.total() - rate;
    }

    /** The object the derivation is asked of. */
    public static class Order {

        private final double total;
        private final int rate;

        public Order(double total, int rate) {
            this.total = total;
            this.rate = rate;
        }

        public double total() {
            return total;
        }

        public int rate() {
            return rate;
        }
    }
}
