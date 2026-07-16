package com.example;

/** Fixture for inline_variable on a multi-variable declaration (Sprint 25, spec D1a item 2). */
public class MultiVarInline {
    public int compute() {
        int a = 1, b = 2;
        int sum = a + b;
        return sum * b;
    }
}
