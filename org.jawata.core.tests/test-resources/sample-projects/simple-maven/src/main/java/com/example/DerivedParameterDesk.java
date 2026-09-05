package com.example;

/**
 * Callers of {@link DerivedParameterTargets} in ANOTHER FILE, carrying BOTH cases: two that
 * agree on how the parameter is derived, and two that do not.
 */
public class DerivedParameterDesk {

    private final DerivedParameterTargets targets = new DerivedParameterTargets();

    /** Derives the rate from the order it also passes — as does its neighbour below. */
    public double one(DerivedParameterTargets.Order order) {
        return targets.discounted(order, order.rate());
    }

    /** A SECOND caller deriving it the same way: unanimity is what the row requires. */
    public double two(DerivedParameterTargets.Order order) {
        return targets.discounted(order, order.rate());
    }

    /** Derives it the same way — but its neighbour below does not, and that is the refusal. */
    public double agreeing(DerivedParameterTargets.Order order) {
        return targets.contested(order, order.rate());
    }

    /** Passes a number of its own, which no query on the order could reproduce. */
    public double disagreeing(DerivedParameterTargets.Order order) {
        return targets.contested(order, 5);
    }
}
