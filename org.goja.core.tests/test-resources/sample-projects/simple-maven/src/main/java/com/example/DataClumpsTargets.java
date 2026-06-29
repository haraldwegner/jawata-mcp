package com.example;

/**
 * Sprint 17 fixture — Data Clumps detector.
 * <ul>
 *   <li>{@code plotPoint} and {@code movePoint} share the same 3-parameter tuple
 *       {@code (int px, int py, int pz)} — a clump (default threshold 2 occurrences).</li>
 *   <li>{@code single} (1 param) — below the minimum clump width, never flagged.</li>
 * </ul>
 */
public class DataClumpsTargets {

    public void plotPoint(int px, int py, int pz) {
    }

    public void movePoint(int px, int py, int pz) {
    }

    public void single(String name) {
    }
}
