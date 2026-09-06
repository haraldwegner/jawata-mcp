package com.example;

/**
 * ROW 4 REFUSES this: {@link TierKin} asks {@code instanceof TierObserved}, so something
 * depends on this level existing and removing it would change what that code sees.
 */
public class TierObserved extends TierBase {

    public TierObserved(String name) {
        super(name);
    }

    public int watched() {
        return 7;
    }
}
