package com.example;

/**
 * The implementor that makes {@link Lifecycle#shutdown()} undeletable, and whose own
 * {@code shutdown()} is undeletable for the mirror reason — it overrides the interface, so its
 * caller invokes it through {@code Lifecycle} and the direct-reference count is zero either way.
 */
public class Service implements Lifecycle {

    @Override
    public void shutdown() {
        // nothing to tear down; the declaration is the fixture
    }
}
