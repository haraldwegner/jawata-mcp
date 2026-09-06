package com.example;

/**
 * ROW 4's PERFORMING CASE — a level that adds one method and still has subtypes of its own,
 * which is exactly the case {@code inline kind=subclass} refuses and points here for.
 */
public class TierBanded extends TierBase {

    public TierBanded(String name) {
        super(name);
    }

    public int band() {
        return 1;
    }
}
