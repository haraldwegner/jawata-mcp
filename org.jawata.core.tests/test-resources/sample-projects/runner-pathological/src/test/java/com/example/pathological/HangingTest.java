package com.example.pathological;

import org.junit.jupiter.api.Test;

/**
 * Hangs deliberately — the forked runner's timeout must reap this JVM
 * (process tree included) and report an honest not-finalized result.
 */
public class HangingTest {

    @Test
    void hangsForever() throws InterruptedException {
        Thread.sleep(10 * 60 * 1000L);
    }
}
