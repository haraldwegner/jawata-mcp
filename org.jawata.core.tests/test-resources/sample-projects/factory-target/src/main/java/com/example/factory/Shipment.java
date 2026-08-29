package com.example.factory;

/**
 * Sprint 28d Stage 8 fixture — the type whose constructor Replace Constructor with
 * Factory Method acts on.
 *
 * <p>Its constructor is PUBLIC and EXPLICIT on purpose: an implicit default
 * constructor has no declaration for a caret to sit on, and a package-private one
 * would make "the old path is now impossible" trivially true before the operation
 * ran.</p>
 */
public class Shipment {

    private final String destination;
    private final int weightKg;

    public Shipment(String destination, int weightKg) {
        this.destination = destination;
        this.weightKg = weightKg;
    }

    public String destination() {
        return destination;
    }

    public int weightKg() {
        return weightKg;
    }
}
