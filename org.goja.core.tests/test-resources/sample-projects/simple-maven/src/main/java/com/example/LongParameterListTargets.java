package com.example;

/**
 * Sprint 17 fixture — Long Parameter List detector.
 * <ul>
 *   <li>{@code tooMany} (5 params) and the 5-arg constructor — flagged (default threshold 4).</li>
 *   <li>{@code ok} (2 params) — must NOT be flagged.</li>
 * </ul>
 */
public class LongParameterListTargets {

    public LongParameterListTargets(int a, int b, int c, int d, int e) {
    }

    public void tooMany(int a, int b, int c, int d, int e) {
    }

    public void ok(int a, int b) {
    }
}
