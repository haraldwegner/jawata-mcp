package com.example;

/**
 * Callers of {@link FlagArgumentTargets} in ANOTHER FILE, carrying both literals and the variable
 * that makes one method unfoldable.
 */
public class FlagArgumentDesk {

    private final FlagArgumentTargets targets = new FlagArgumentTargets();

    /** Passes the flag one way… */
    public double premium(double base) {
        return targets.price(base, true);
    }

    /** …and its neighbour passes it the other, so both named methods get a caller. */
    public double regular(double base) {
        return targets.price(base, false);
    }

    /** A second caller of the same side, so the rewrite must reach more than one. */
    public double alsoPremium(double base) {
        return targets.price(base, true);
    }

    /** Decides at the call, which this row can rewrite. */
    public double expressFare(double base) {
        return targets.fare(base, true);
    }

    /** Passes the decision ON, which is the case with no name to become. */
    public double eitherFare(double base, boolean chosen) {
        return targets.fare(base, chosen);
    }

    /** Calls the non-boolean case, so it has callers and refuses for the type instead. */
    public double tiered(double base) {
        return targets.tiered(base, 3);
    }

    /** Calls the case whose chosen name would collide with an existing method. */
    public double levied(double base) {
        return targets.levied(base, false);
    }
}
