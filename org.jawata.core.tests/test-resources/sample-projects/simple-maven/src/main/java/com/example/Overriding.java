package com.example;

/**
 * ROW 57 REFUSES this — it OVERRIDES an inherited method, so a caller holding a Ledgering and
 * calling posting() is depending on dynamic dispatch, which a delegate does not provide.
 */
public class Overriding extends Ledgering {

    public Overriding(String book) {
        super(book);
    }

    @Override
    public String posting(String entry) {
        return "[audited] " + super.posting(entry);
    }
}
