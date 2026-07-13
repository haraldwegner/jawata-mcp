package com.example.cov;

/**
 * Executed ONLY inside a CHILD JVM forked by ChildSpawnTest — the coverage
 * agent is not propagated to children, so this class's execution is OUTSIDE
 * the measurement and must be declared as such (the boundary marker), never
 * silently zero.
 */
public class ChildWork {

    public static void main(String[] args) {
        System.out.println(new ChildWork().compute(21));
    }

    public int compute(int x) {
        return x * 2;
    }
}
