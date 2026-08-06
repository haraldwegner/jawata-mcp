package com.example;

/**
 * Sprint 28 (C1 audit) — the OUTPUT-PRUNING trap.
 *
 * <p>This file sits under {@code build/}, a directory named in the importer's
 * ignore list. The previous scan FILTERED that list against each directory's
 * LEAF name, so {@code build} itself was rejected while
 * {@code build/generated/com/example} passed, held {@code .java} files, and was
 * mounted as a source root — putting generated output on the model beside the
 * sources it was generated from, as duplicate types.</p>
 *
 * <p>The fix prunes the subtree instead of filtering the leaf. If this type
 * ever resolves in a loaded {@code unknown-layout}, that regressed.</p>
 */
public class Ghost {

    /** Returns a marker no test should ever see resolved. */
    public String haunt() {
        return "ghost";
    }
}
