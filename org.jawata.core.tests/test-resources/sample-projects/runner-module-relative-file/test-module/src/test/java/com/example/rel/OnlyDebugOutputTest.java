package com.example.rel;

import org.junit.jupiter.api.Test;

/**
 * Prints two DEBUG lines and nothing else. A run's output tail sets DEBUG lines aside to show what
 * matters, but when nothing else was printed the tail must show them as they came and not be empty.
 */
public class OnlyDebugOutputTest {

    @Test
    void printsNothingButDebugLines() {
        System.out.println("12:04:12.002 [main] DEBUG org.example.Library -- only line one");
        System.out.println("12:04:12.003 [main] DEBUG org.example.Library -- only line two");
    }
}
