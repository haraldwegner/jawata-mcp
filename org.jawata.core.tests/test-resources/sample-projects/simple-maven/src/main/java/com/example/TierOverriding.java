package com.example;

/**
 * ROW 4 REFUSES this: the override IS the distinction the level carries, so collapsing it
 * changes what gets dispatched for every subtype below it. Its subtype lives in
 * {@link TierKin}, so this file stays a single top-level class as the row requires.
 */
public class TierOverriding extends TierBase {

    public TierOverriding(String name) {
        super(name);
    }

    @Override
    public String name() {
        return "tier:" + super.name();
    }
}
