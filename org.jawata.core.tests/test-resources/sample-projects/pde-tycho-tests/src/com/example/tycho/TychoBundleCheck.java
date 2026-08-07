package com.example.tycho;

/**
 * Deliberately imports NO test framework (C2 audit F2). The first version
 * imported JUnit, so the content rule reached "test" independently and the
 * packaging check could be deleted with every test staying green — a
 * non-discriminating fixture for the very rule it exists to prove. The
 * bundle's eclipse-test-plugin packaging is now the ONLY evidence.
 */
public class TychoBundleCheck {

    /** Returns a marker. */
    public String marker() {
        return "tycho-check";
    }
}
