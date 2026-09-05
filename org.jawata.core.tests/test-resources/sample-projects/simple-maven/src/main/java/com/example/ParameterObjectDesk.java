package com.example;

/**
 * A caller of {@link ParameterObjectTargets} in ANOTHER FILE — the half of row 21 that a
 * single-file fixture cannot show. The engine must rewrite this call to construct the new
 * class, and nothing here is pointed at by the test.
 */
public class ParameterObjectDesk {

    private final ParameterObjectTargets targets = new ParameterObjectTargets();

    public String label() {
        return targets.describeDelivery("Berlin", "Hauptstrasse 1", 10115);
    }
}
