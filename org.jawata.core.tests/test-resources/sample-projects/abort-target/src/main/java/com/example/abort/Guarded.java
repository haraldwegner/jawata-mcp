package com.example.abort;

/** The target of a test that aborts BEFORE any mutation (its precondition is never met), by design. */
public class Guarded {

    public int twice(int a) {
        return a * 2;
    }
}
