package com.example.abort;

/** The control: SolidTest asserts the value, so a mutation of times() FAILS the test and is killed. */
public class Solid {

    public int times(int a, int b) {
        return a * b;
    }
}
