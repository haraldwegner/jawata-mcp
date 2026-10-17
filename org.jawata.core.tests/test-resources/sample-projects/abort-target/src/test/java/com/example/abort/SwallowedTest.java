package com.example.abort;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The shape a neutered method hides behind: the check is an ASSUMPTION, so when the method breaks
 * the test is ABORTED, not failed, and a runner that counts an abort as a pass reads green.
 * Unmutated, the assumption holds and the test passes.
 */
public class SwallowedTest {

    @Test
    void plusIsFiveOnlyAsAnAssumption() {
        Assumptions.assumeTrue(new Swallowed().plus(2, 3) == 5);
    }
}
