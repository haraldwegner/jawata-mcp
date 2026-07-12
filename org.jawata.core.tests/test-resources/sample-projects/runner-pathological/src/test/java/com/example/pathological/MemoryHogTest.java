package com.example.pathological;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Allocates far past the runner's default -Xmx512m — the bound must stop it
 * (OutOfMemoryError as a FAILED result), never the server JVM.
 */
public class MemoryHogTest {

    @Test
    void allocatesUnbounded() {
        List<byte[]> hog = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            hog.add(new byte[1024 * 1024]);
        }
        // Unreachable under the memory bound; defeat dead-code elimination.
        if (hog.size() < 0) throw new IllegalStateException();
    }
}
