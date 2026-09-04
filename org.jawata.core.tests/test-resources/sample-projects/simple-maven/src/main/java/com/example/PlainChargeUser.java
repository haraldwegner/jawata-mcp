package com.example;

/** Uses PlainCharge by its type, so the fold has references to repoint. */
public class PlainChargeUser {

    public int twice(int amount) {
        PlainCharge charge = new PlainCharge(amount);
        return charge.doubled();
    }
}
