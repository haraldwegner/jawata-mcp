package com.example;

/**
 * A subtype of the level row 4 collapses. Afterwards it must extend {@link TierBase} DIRECTLY —
 * the reparenting is the half that distinguishes this row from Remove Subclass.
 */
public class TierGold extends TierBanded {

    public TierGold(String name) {
        super(name);
    }

    public int multiplier() {
        return 3;
    }
}
