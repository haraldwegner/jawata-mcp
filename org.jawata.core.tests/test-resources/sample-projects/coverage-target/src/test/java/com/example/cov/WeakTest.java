package com.example.cov;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately UNDER-ASSERTING: plus() runs (full line coverage!) but its
 * result is thrown away — mutation testing must expose what line coverage
 * cannot.
 */
public class WeakTest {

    @Test
    void executesButNeverAsserts() {
        new Weak().plus(2, 3);
        assertTrue(true);
    }
}
