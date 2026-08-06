/*
 * Copyright © 2019 Example GmbH. A legacy-encoded header: that byte is a
 * Latin-1 (c), invalid as UTF-8, and it precedes the package declaration â€” so a
 * strict decoder meets it while looking for that declaration.
 */
package com.example.legacy;

/** Sprint 28 (C1 audit): one non-UTF-8 source must not abort the load of the
 *  project around it. */
public class Latin1 {
    /** Returns a marker. */
    public String marker() { return "latin1"; }
}
