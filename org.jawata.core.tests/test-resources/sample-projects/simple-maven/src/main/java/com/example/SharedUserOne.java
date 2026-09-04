package com.example;

/** One of two users of SharedHelper. */
public class SharedUserOne {

    private final SharedHelper helper = new SharedHelper();

    public int read() {
        return helper.value();
    }
}
