package com.example.bb;

/** In a test/ folder, on a project whose build path is incomplete. */
public class ThingTest {
    public void testValue() {
        if (new Thing().value() != 42) {
            throw new AssertionError("bad");
        }
    }
}
