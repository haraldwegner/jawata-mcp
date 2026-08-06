package com.example;

/**
 * Sprint 28 (C1 re-audit) — an output-exclusion TRAP.
 *
 * <p>It sits in the directory this fixture's build system writes its output to.
 * Without it, "no build output was mounted as source" is asserted against a
 * fixture that HAS no output — an assertion that cannot fail, on any code.</p>
 *
 * <p>If this type ever resolves in the loaded project, output was mounted
 * beside the sources it was generated from.</p>
 */
public class EclipseGhost {
    /** Returns a marker no test should ever see resolved. */
    public String marker() { return "eclipse-ghost"; }
}
