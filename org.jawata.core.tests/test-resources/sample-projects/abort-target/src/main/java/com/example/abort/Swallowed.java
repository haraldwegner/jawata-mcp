package com.example.abort;

/** The target whose only test turns a failure into an ABORT: SwallowedTest guards its check with an assumption. */
public class Swallowed {

    public int plus(int a, int b) {
        return a + b;
    }
}
