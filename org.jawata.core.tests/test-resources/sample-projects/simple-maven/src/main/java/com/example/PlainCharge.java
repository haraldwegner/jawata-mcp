package com.example;

/**
 * A subclass carrying NO distinction: it overrides nothing, nobody asks what type it is,
 * and its constructor forwards its own parameter straight through. Row 38's happy path.
 */
public class PlainCharge extends Charge {

    public PlainCharge(int amount) {
        super(amount);
    }

    public int doubled() {
        return amount() * 2;
    }
}
