package com.example;

/** Calls both functions the old way, so the combine has call sites to rewrite. */
public class ReadingCaller {

    public int charge(Reading reading) {
        return ReadingFunctions.baseCharge(reading);
    }

    public int taxable(Reading reading) {
        return ReadingFunctions.taxThreshold(reading, 5);
    }
}
