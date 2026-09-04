package com.example;

/**
 * Rows 25 and 26 (Sprint 28d-rescue). `note()` is preceded by the same audit line at BOTH
 * its call sites, so moving that line in changes nothing. `partial()` is preceded by it at
 * one of two, which is the case the move must refuse. `threeSteps()` exists so row 26's
 * middle-statement refusal has a genuine target — a C6 audit found that test pointing at a
 * field instead, which is rejected earlier and for a different reason.
 */
public class Audited {

    static int audits = 0;
    static int exits = 0;
    static String trail = "";

    public void note() {
        exits = exits + 1;
    }

    public void partial() {
        exits = exits + 1;
    }

    public void describe(String label) {
        trail = trail + "label=" + label;
    }

    public void threeSteps() {
        audits = audits + 1;
        exits = exits + 1; // the middle
        audits = audits + 2;
    }
}
