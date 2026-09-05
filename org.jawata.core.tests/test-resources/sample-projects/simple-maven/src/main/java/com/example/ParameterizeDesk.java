package com.example;

/**
 * A caller of {@link ParameterizeTargets} in ANOTHER FILE — the half of row 27 that a
 * single-file fixture cannot show. The engine must rewrite this call to pass the constant the
 * method stopped holding, and nothing here is pointed at by the test.
 */
public class ParameterizeDesk {

    private final ParameterizeTargets targets = new ParameterizeTargets();

    public double annualReview(double salary) {
        return targets.tenPercentRaise(salary);
    }
}
