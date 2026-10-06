package com.example.sel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SECOND test that touches no production class. Two rather than one, so a
 * narrowing that dropped exactly one class by accident would still read 2 and
 * not the 1 the assertion demands.
 */
public class UnrelatedTwoTest {

    @Test
    void stringsStillCompare() {
        assertTrue("selection".startsWith("sel"));
    }
}
