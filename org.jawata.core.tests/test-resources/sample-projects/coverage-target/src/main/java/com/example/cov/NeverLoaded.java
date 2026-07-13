package com.example.cov;

/** Never touched by any test — the NOT-INSTRUMENTED state probe. */
public class NeverLoaded {

    public String ghost() {
        return "boo";
    }
}
