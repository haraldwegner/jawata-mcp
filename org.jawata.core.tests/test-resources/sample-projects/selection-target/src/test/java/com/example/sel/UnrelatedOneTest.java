package com.example.sel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Touches NO production class, deliberately. It is one of the two tests a
 * narrowed run must leave out — and leaving them out is the only way the
 * executed-test count can read 1 instead of 3.
 */
public class UnrelatedOneTest {

    @Test
    void arithmeticStillWorks() {
        assertEquals(4, 2 + 2);
    }
}
