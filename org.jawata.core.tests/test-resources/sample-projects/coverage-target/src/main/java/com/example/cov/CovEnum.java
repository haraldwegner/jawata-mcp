package com.example.cov;

/**
 * The GENERATED-OR-EXCLUDED probe: JaCoCo's filters drop the compiler-
 * generated {@code values()}/{@code valueOf(String)} from analysis — querying
 * them must answer with the honest state, never invented lines.
 */
public enum CovEnum {
    ALPHA, BETA;

    public String tag() {
        return name().toLowerCase();
    }
}
