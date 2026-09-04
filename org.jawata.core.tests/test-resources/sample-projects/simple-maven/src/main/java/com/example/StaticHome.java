package com.example;

/** Declares the static helper row 24 moves away (Sprint 28d-rescue). */
public class StaticHome {

    public static int roundUp(int cents) {
        return ((cents + 99) / 100) * 100;
    }

    public int instanceOnly(int cents) {
        return cents;
    }
}
