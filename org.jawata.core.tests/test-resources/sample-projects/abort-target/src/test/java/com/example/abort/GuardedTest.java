package com.example.abort;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Aborts on every run, mutated or not: an environment precondition that is never present. It runs the
 * target first, so the target IS covered and IS mutated; only then does the assumption stop it.
 */
public class GuardedTest {

    @Test
    void needsAnEnvironmentThatIsNeverThere() {
        int twice = new Guarded().twice(2);
        Assumptions.assumeTrue(System.getProperty("jawata.abort.fixture.environment") != null);
        assertEquals(4, twice);
    }
}
