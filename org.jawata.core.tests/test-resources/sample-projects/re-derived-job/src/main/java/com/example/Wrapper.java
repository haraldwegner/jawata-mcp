package com.example;

/** CONTROL TWO, half 2 — delegation, not a rival implementation. */
public class Wrapper {

    private final Owner owner = new Owner();

    public Money render(Doc doc) {
        String title = doc.title();
        if (title == null) {
            return Money.of(0L);
        }
        return owner.render(doc);
    }
}
