package com.example;

/** The other user of SharedHelper — the reason inlining it is refused. */
public class SharedUserTwo {

    private final SharedHelper helper = new SharedHelper();

    public int alsoRead() {
        return helper.value();
    }
}
