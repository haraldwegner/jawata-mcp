package com.example;

/**
 * ROW 56's PERFORMING CASE — it exists to vary ONE thing, and declares no state of its own.
 */
public class Zeroed extends Levying {

    @Override
    public String rate() {
        return "zero";
    }
}
