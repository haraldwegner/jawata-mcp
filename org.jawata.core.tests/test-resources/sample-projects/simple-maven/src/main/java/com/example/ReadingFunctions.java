package com.example;

/**
 * Two static functions that both take a Reading first, and one that does not — the third is
 * what proves the grouping is checked rather than assumed.
 */
public class ReadingFunctions {

    public static int baseCharge(Reading reading) {
        return reading.quantity() * 2;
    }

    public static int taxThreshold(Reading reading, int allowance) {
        return reading.quantity() - allowance;
    }

    public static int unrelated(int plain) {
        return plain + 1;
    }
}
