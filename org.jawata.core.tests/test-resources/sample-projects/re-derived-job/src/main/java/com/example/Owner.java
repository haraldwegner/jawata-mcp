package com.example;

/**
 * CONTROL TWO, half 1 — the owner of a job.
 *
 * <p>{@link Wrapper} has the same shape, shares two collaborators with this, and has no
 * supertype in common with it. The only thing that excludes the pair is that the other one
 * CALLS this one, so it is not a second implementation: it is this one with something
 * around it.</p>
 */
public class Owner {

    public Money render(Doc doc) {
        String title = doc.title();
        return Money.of(title.length());
    }
}
