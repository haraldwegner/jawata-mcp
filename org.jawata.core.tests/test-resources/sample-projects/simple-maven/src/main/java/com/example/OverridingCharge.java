package com.example;

/** A subclass whose override IS its distinction — row 38 must refuse this one. */
public class OverridingCharge extends Charge {

    public OverridingCharge(int amount) {
        super(amount);
    }

    @Override
    public int amount() {
        return super.amount() + 1;
    }
}
