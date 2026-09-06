package com.example;

/**
 * ROW 4 REFUSES this: its constructor FIXES super()'s argument rather than forwarding its own,
 * so the {@code super(...)} call in each subtype would no longer mean the same thing once this
 * level is gone.
 *
 * <p>It overrides nothing and its subtype lives in {@link TierKin}, so the two refusals that sit
 * ahead of this one cannot answer instead.</p>
 */
public class TierFixedCtor extends TierBase {

    public TierFixedCtor() {
        super("fixed");
    }

    public int step() {
        return 1;
    }
}
