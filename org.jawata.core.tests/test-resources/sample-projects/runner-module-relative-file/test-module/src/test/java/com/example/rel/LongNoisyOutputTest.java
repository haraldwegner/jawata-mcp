package com.example.rel;

import org.junit.jupiter.api.Test;

/**
 * Prints one INFO line first and then more DEBUG lines than a run's output tail holds (S19-30).
 * The tail shows the INFO line only when the DEBUG lines are left out before the last 100 lines
 * are taken, not after.
 */
public class LongNoisyOutputTest {

    @Test
    void printsAnInfoLineThenMoreDebugLinesThanTheTailHolds() {
        System.out.println("12:04:12.001 [main] INFO org.example.Library -- statusCode=[500] backend gone");
        for (int i = 0; i < 150; i++) {
            System.out.println("12:04:12.002 [main] DEBUG org.example.Library -- Retrying step " + i);
        }
    }
}
