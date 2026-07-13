package com.example.cov;

/**
 * Hand-computed coverage truth (exercised by CoveredTest):
 * alwaysCalled → fully covered; neverCalled → fully missed;
 * branchy → called with flag=true only: 1 of 2 branches, the return "no"
 * line uncovered; lambdaHolder → invoked, but the RETURNED lambda never runs
 * (its body lines stay uncovered, mapped to real source lines).
 */
public class Covered {

    public int alwaysCalled(int x) {
        return x + 1;
    }

    public int neverCalled(int x) {
        return x * 2;
    }

    public String branchy(boolean flag) {
        if (flag) {
            return "yes";
        }
        return "no";
    }

    public Runnable lambdaHolder() {
        return () -> {
            int z = 1 + 1;
            if (z != 2) throw new IllegalStateException();
        };
    }
}
