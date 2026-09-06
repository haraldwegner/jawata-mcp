package com.example;

/** The base whose behaviour row 56's subclasses vary. */
public class Levying {

    public String rate() {
        return "standard";
    }

    public String describe() {
        return "levy at " + rate();
    }
}
