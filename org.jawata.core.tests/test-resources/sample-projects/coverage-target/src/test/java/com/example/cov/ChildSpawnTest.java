package com.example.cov;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forks a CHILD JVM running ChildWork — the child executes real fixture
 * code, but the coverage agent is NOT propagated: the artifact must declare
 * the measurement boundary rather than silently reporting zero.
 */
public class ChildSpawnTest {

    @Test
    void childJvmExecutesOutsideTheMeasurement() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "com.example.cov.ChildWork")
            .redirectErrorStream(true)
            .start();
        String out = new String(child.getInputStream().readAllBytes());
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child must finish");
        assertEquals(0, child.exitValue(), "child output: " + out);
        assertTrue(out.contains("42"), "child must have RUN ChildWork; got: " + out);
    }
}
