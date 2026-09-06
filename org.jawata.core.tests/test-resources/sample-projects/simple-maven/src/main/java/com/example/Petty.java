package com.example;

/**
 * ROW 57's PERFORMING CASE — it inherits only to REUSE. Nothing treats a Petty as a Ledgering,
 * it overrides nothing, and nothing extends it.
 */
public class Petty extends Ledgering {

    private final int float0;

    public Petty(String book, int float0) {
        super(book);
        this.float0 = float0;
    }

    public String slip(String what) {
        return posting(what) + " (float " + float0 + ")";
    }
}
