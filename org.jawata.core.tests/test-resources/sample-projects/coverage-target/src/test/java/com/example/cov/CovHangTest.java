package com.example.cov;

import org.junit.jupiter.api.Test;

/**
 * Hangs — used ONLY by the unknown-run-failed probe (coverage run with a
 * short timeout); never selected by class-scope runs of CoveredTest.
 */
public class CovHangTest {

    @Test
    void hangs() throws InterruptedException {
        Thread.sleep(10 * 60 * 1000L);
    }
}
