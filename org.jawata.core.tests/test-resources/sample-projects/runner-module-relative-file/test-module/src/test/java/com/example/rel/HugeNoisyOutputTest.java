package com.example.rel;

import org.junit.jupiter.api.Test;

/**
 * Prints more DEBUG output than a run's capture holds, and one ERROR line last (S19-30b).
 * The ERROR line is the end of the run and the line that says what went wrong; the capture used
 * to keep only the FIRST million characters, so after about ten thousand DEBUG lines it was gone.
 */
public class HugeNoisyOutputTest {

    @Test
    void printsMoreDebugOutputThanTheCaptureHoldsThenOneErrorLine() {
        for (int i = 0; i < 16000; i++) {
            System.out.println("12:04:12.002 [main] DEBUG org.example.Library -- Mapping request " + i + " to handler number " + (i % 97) + " of the pool");
        }
        System.out.println("12:04:13.001 [main] ERROR org.example.Library -- statusCode=[503] the gateway went away");
    }
}
