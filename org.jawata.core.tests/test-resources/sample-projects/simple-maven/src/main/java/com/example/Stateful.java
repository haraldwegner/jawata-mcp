package com.example;

/**
 * ROW 56 REFUSES this — it declares instance state, which would have to travel with the
 * behaviour, and what the delegate is constructed with is a seam a human chooses.
 */
public class Stateful extends Levying {

    private final String band;

    public Stateful(String band) {
        this.band = band;
    }

    @Override
    public String rate() {
        return band;
    }
}
