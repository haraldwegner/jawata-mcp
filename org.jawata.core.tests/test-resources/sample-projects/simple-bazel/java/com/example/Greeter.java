package com.example;

/** Production class in a Bazel package — the target of the load test's live semantic query. */
public class Greeter {

    private final String name;

    public Greeter(String name) {
        this.name = name;
    }

    /** Returns the greeting this Greeter was built for. */
    public String greet() {
        return "Hello, " + name;
    }
}
