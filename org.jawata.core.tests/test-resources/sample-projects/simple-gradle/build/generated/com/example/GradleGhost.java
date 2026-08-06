package com.example;

/** Sprint 28 (C1 audit) — the output-exclusion trap for Gradle. Gradle's own
 *  output directory is {@code build/}; a scan that mounts it puts generated
 *  classes on the model beside the sources they came from. If this type ever
 *  resolves in a loaded simple-gradle, that regressed. */
public class GradleGhost {
    /** Returns a marker no test should ever see resolved. */
    public String haunt() { return "gradle-ghost"; }
}
