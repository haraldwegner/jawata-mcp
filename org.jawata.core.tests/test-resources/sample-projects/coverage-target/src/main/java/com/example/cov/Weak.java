package com.example.cov;

/** The weak-test target: WeakTest EXECUTES plus() but never asserts its value. */
public class Weak {

    public int plus(int a, int b) {
        return a + b;
    }
}
