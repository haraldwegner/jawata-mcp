package com.example;

import org.junit.jupiter.api.Test;

/** Test code in the Bazel test tree — content-based classification must see this. */
public class GreeterTest {

    @Test
    void greets() {
        if (!GreeterFactory.forName("world").greet().equals("Hello, world")) {
            throw new AssertionError("greeting mismatch");
        }
    }
}
