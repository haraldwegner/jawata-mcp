package com.example.tycho;

import org.junit.jupiter.api.Test;

/** Flat under src/, so the folder convention says nothing about it; the bundle's
 *  eclipse-test-plugin packaging is what declares it test code. */
public class TychoBundleTest {

    /** A test method, so the content rule would also reach "test" — by a
     *  different route, which is why the precedence order needs proving. */
    @Test
    public void marker() {
    }
}
