package com.example;

/**
 * A subclass whose constructor fixes super()'s argument, so `new FixedCharge()` and
 * `new Charge(...)` are not the same call. Row 38 must refuse this one too.
 */
public class FixedCharge extends Charge {

    public FixedCharge() {
        super(99);
    }
}
