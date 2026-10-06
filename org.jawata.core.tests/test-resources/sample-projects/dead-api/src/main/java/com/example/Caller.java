package com.example;

/**
 * The reference that makes {@link Registry#register} live, in a file of its own so the refusal
 * has somewhere to point. A refusal naming the call site in the SAME file would not discriminate
 * between a workspace search and a file-local one.
 */
public class Caller {

    public void load(Registry registry) {
        registry.register("one");
    }
}
