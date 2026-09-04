package com.example.service;

/**
 * Row 16's cross-package return type. Its only job is to live in a package the server's
 * file does not import, so the generated forwarder needs an import added for it.
 *
 * <p>It sits in the EXISTING com.example.service rather than a new package, and that is the
 * point of the file. The first version added com.example.hr, which took this sample project
 * from two packages to three — over the minimum sample AnalyzeNamingToolTest relies on to
 * assert that too small a population yields NO inferred naming convention. The detector was
 * right and the test was right; the fixture had changed the population out from under them.
 * A fixture must not move a census some other test measures.</p>
 */
public class Lead {

    private final String name;

    public Lead(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
