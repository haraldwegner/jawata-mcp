package com.example;

/** Two qualified call sites, so the move has references to repoint. */
public class StaticCaller {

    public int once(int cents) {
        return StaticHome.roundUp(cents);
    }

    public int twice(int cents) {
        return StaticHome.roundUp(cents) + StaticHome.roundUp(cents + 1);
    }
}
