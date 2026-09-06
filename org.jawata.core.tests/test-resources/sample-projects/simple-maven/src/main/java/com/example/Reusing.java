package com.example;

/**
 * ROW 56 REFUSES this — it overrides NOTHING, so there is no varying behaviour to move out.
 * That is row 57's case, and the refusal says so by name.
 */
public class Reusing extends Levying {

    public String stamped() {
        return "[stamped] " + describe();
    }
}
