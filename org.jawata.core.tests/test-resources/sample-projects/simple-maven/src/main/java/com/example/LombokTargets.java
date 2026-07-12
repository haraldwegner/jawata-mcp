package com.example;

import lombok.Data;
import lombok.Getter;

/**
 * Fixture for find_modernization Lombok-removal kinds (Sprint 15 B5b);
 * detection is source-only (import + annotation simple-name). Sprint 23 put
 * lombok on the fixture classpath (the whole fixture must compile now that
 * run_tests builds before running) — JDT does not run lombok's processor, so
 * the constructor lombok would generate is written out explicitly.
 */
public class LombokTargets {
}

/** lombok_to_record + delombok candidate: @Data data carrier. */
@Data
class LombokPoint {
    private final int x;
    private final int y;

    LombokPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }
}

/** delombok candidate only: @Getter is not a class-level data annotation. */
@Getter
class LombokBox {
    private int width;
    private int height;
}
