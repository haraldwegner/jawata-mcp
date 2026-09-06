package com.example;

/**
 * The SECOND subtype, so the reparenting claim is about a set rather than a single case — one
 * subtype reparented could be a coincidence of how the sweep happened to visit files.
 */
public class TierSilver extends TierBanded {

    public TierSilver(String name) {
        super(name);
    }

    public int multiplier() {
        return 2;
    }
}
