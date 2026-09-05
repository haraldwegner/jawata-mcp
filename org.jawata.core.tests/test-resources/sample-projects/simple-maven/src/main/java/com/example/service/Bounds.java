package com.example.service;

/**
 * An object whose parts callers unpack, declared in ANOTHER PACKAGE from the method that will
 * come to take it whole.
 *
 * <p>It exists because row 28's other fixture could not exercise the import path: its object is a
 * nested class in the same file, so the folded parameter's type was already visible and nothing
 * had to be added. Row 16 met that gap from the other side — it wrote a type as a bare simple
 * name and the compile gate refused the whole change on every cross-package input.</p>
 *
 * <p>It lives in {@code com.example.service} rather than a package of its own, deliberately:
 * Stage 5 recorded that adding a THIRD package to this project took it over the minimum sample
 * {@code AnalyzeNamingToolTest} relies on, and turned that test red for a reason unrelated to
 * what it measures.</p>
 */
public class Bounds {

    private final int floor;
    private final int ceiling;

    public Bounds(int floor, int ceiling) {
        this.floor = floor;
        this.ceiling = ceiling;
    }

    public int floor() {
        return floor;
    }

    public int ceiling() {
        return ceiling;
    }
}
