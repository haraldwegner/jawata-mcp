package com.example.hollow;

/**
 * Control for the interface-member skip: an interface method is IMPLEMENTED,
 * not called into existence by its callers, so "only tests call it" says
 * nothing about whether production uses the abstraction. {@code go()} is
 * invoked exclusively from test code through this static type — without the
 * skip it would be reported.
 */
public interface Plugin {

    void go();
}
