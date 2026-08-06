package com.example;

/** The single type of the {@code maven-unresolved-level} fixture. */
public class Unresolved {

    /** Returns a constant; the fixture exists for its pom, not its behaviour. */
    public String marker() {
        return "unresolved";
    }
}
