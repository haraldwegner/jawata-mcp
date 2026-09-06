package com.example;

/**
 * The superclass row 57 replaces with a delegate — reuse, not an is-a.
 *
 * <p>NOTE for whoever writes into {@code simple-maven} next: this project is ONE shared fixture
 * read by the whole suite, and five times this sprint a fixture written for one row moved a
 * population another row's test was counting. These names are deliberately unusual.</p>
 */
public class Ledgering {

    private final String book;

    public Ledgering(String book) {
        this.book = book;
    }

    public String book() {
        return book;
    }

    public String posting(String entry) {
        return book + ": " + entry;
    }
}
