package com.example.rel;

import org.junit.jupiter.api.Test;

/**
 * Prints what a library's logging prints when nobody configured it: many DEBUG lines in the
 * logback default format, and among them the one line that matters. A run's output tail must show
 * the one line (S19-20).
 */
public class NoisyOutputTest {

    @Test
    void printsManyDebugLinesAndOneInfoLine() {
        for (int i = 0; i < 40; i++) {
            System.out.println("12:04:11.942 [main] DEBUG org.example.Library -- Mapping thing " + i);
        }
        System.out.println("12:04:11.957 [main] INFO org.example.Library -- statusCode=[404] file missing");
        for (int i = 0; i < 40; i++) {
            System.out.println("12:04:11.960 [main] TRACE org.example.Library -- Firing event " + i);
        }
    }
}
