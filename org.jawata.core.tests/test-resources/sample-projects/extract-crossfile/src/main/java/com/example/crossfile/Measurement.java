package com.example.crossfile;

/**
 * The EXTRACT TARGET: a class whose state splits into two clusters, one of which
 * is read from a different file.
 *
 * <p>{@code label} and {@code unit} are PACKAGE-PRIVATE on purpose. Every existing
 * Extract Class fixture makes them private, which does not merely leave the
 * cross-file case untested — it makes it impossible, because a private field
 * cannot be read from another file at all. Package-private is the narrowest
 * visibility for which a second file CAN reach the state directly, so it is the
 * narrowest fixture that can ask the question.</p>
 *
 * <p>{@code value} stays behind. A cluster that is the whole class would let a
 * broken move pass by moving everything.</p>
 */
public class Measurement {

    int value;

    String label;
    String unit;

    public Measurement(int value, String label, String unit) {
        this.value = value;
        this.label = label;
        this.unit = unit;
    }

    /** An in-file reader, so the within-file rewrite is exercised in the same run. */
    String describeHere() {
        return label + " (" + unit + ")";
    }

    int value() {
        return value;
    }
}
