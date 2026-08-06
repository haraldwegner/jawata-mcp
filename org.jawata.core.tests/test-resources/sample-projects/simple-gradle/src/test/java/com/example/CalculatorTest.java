package com.example;

/**
 * Test code in Gradle's conventional test source set. Content-based
 * classification is never reached here — the src/test/** convention decides
 * first, which is the precedence Stage 2 depends on.
 */
public class CalculatorTest {

    void adds() {
        if (new Calculator().add(2, 2) != 4) {
            throw new AssertionError("arithmetic");
        }
    }
}
