package com.example;

/**
 * Sprint 28d fixture — the ZERO for the {@code encapsulation} sweep kind. Three
 * NEAR MISSES, not an empty class, so the zero is evidence of discrimination
 * rather than evidence that the detector never fires.
 *
 * <ul>
 *   <li>{@code owner} is written by the constructor and is {@code final}: no
 *       mutator can exist after construction.</li>
 *   <li>{@code limit} is private and NOT final, and it IS written by the
 *       constructor — which {@link External} calls. It is silent only because a
 *       constructor is not a mutator for this kind: initialisation is how the
 *       object comes to exist. (The on-demand {@code analyze(kind=encapsulation)}
 *       audit deliberately still counts constructors, so the two answers about
 *       this one field differ on purpose.)</li>
 *   <li>{@code attempts} is private, not final, and IS written after
 *       construction — but only by {@link #record()}, which is private, so no
 *       type outside this class can reach the write.</li>
 * </ul>
 *
 * <p>Contrast {@link Account}, whose {@code balance} is written only inside the
 * class too — through a PUBLIC setter that {@link External} calls. That is the
 * difference the detector must see.</p>
 */
public class Vault {

    private final String owner;

    private int limit;

    private int attempts;

    public Vault(String owner, int limit) {
        this.owner = owner;
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }

    public int attempts() {
        return attempts;
    }

    public boolean unlock(String secret) {
        record();
        return attempts <= limit && owner.equals(secret);
    }

    private void record() {
        attempts++;
    }
}
