package com.example.sel;

/**
 * The ONE production class this fixture's selection turns on. Exactly one of
 * the three test classes touches it, which is what makes "the run narrowed"
 * a number (1) rather than a claim.
 */
public class Widget {

    /** Edited by the test, to make a diff the derivation can resolve. */
    public int bump(int x) {
        return x + 1;
    }
}
