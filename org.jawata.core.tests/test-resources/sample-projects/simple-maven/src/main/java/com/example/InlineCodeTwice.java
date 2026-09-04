package com.example;

/**
 * Row 49 (Sprint 28d-rescue). The same two statements appear in both methods, so extracting
 * the first pair should turn the second into a call to the same new method.
 */
public class InlineCodeTwice {

    public int first(int base) {
        int scaled = base * 3;
        int shifted = scaled + 7;
        return shifted;
    }

    public int second(int base) {
        int scaled = base * 3;
        int shifted = scaled + 7;
        return shifted - 1;
    }
}
