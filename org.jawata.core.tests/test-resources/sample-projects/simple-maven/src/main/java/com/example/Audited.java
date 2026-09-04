package com.example;

/**
 * Rows 25 and 26 (Sprint 28d-rescue). `note()` is preceded by the same audit line at BOTH
 * its call sites, so moving that line in changes nothing. `partial()` is preceded by it at
 * one of two, which is the case the move must refuse.
 */
public class Audited {

    static int audits = 0;
    static int exits = 0;

    public void note() {
        exits = exits + 1;
    }

    public void partial() {
        exits = exits + 1;
    }
}
