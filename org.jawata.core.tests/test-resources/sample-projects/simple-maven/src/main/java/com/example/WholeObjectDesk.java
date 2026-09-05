package com.example;

/**
 * Callers of {@link WholeObjectTargets} in ANOTHER FILE, carrying every case the row must tell
 * apart: two that unpack one object the same way, one that unpacks two, two that disagree on an
 * accessor, and one that already passes the object.
 */
public class WholeObjectDesk {

    private final WholeObjectTargets targets = new WholeObjectTargets();

    /** Unpacks one range — as does its neighbour below, which is what unanimity needs. */
    public boolean one(WholeObjectTargets.Range range) {
        return targets.withinRange(range.low(), range.high(), range.reading());
    }

    /** A SECOND caller unpacking the same object the same way. */
    public boolean two(WholeObjectTargets.Range range) {
        return targets.withinRange(range.low(), range.high(), range.reading());
    }

    /** Reads the two values from DIFFERENT objects, so no single object can replace them. */
    public boolean fromTwoRanges(WholeObjectTargets.Range first,
                                 WholeObjectTargets.Range second) {
        return targets.spans(first.low(), second.high());
    }

    /** Derives the second value one way… */
    public boolean contestedOne(WholeObjectTargets.Range range) {
        return targets.contested(range.low(), range.high());
    }

    /** …and its neighbour derives it ANOTHER, which is the disagreement. */
    public boolean contestedTwo(WholeObjectTargets.Range range) {
        return targets.contested(range.low(), range.ceiling());
    }

    /** Passes the range AND its parts, so folding would pass the same object twice. */
    public boolean alreadyCarries(WholeObjectTargets.Range range) {
        return targets.alsoCarriesTheRange(range, range.low(), range.high());
    }

    /** Unpacks an object from ANOTHER package — the case that needs an import added. */
    public boolean outsideOne(com.example.service.Bounds bounds) {
        return targets.outside(bounds.floor(), bounds.ceiling(), 5);
    }

    /** A SECOND caller unpacking it the same way, so unanimity holds. */
    public boolean outsideTwo(com.example.service.Bounds bounds) {
        return targets.outside(bounds.floor(), bounds.ceiling(), 7);
    }

    /** Unpacks a range for a method whose body reads one of the values twice. */
    public int weighted(WholeObjectTargets.Range range) {
        return targets.weighted(range.low(), range.high());
    }
}
