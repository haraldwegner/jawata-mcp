package com.example;

/** A second production class, so find_references over Greeter has a real caller to find. */
public final class GreeterFactory {

    private GreeterFactory() {
    }

    /** Builds a Greeter for the given name. */
    public static Greeter forName(String name) {
        return new Greeter(name);
    }
}
