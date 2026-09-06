package com.example;

/**
 * ROW 4 REFUSES this: it declares a member named {@code name}, and so does {@link TierBase}.
 * Two members of one name is a merge, and merging is a decision.
 *
 * <p>The collision is a FIELD rather than a method on purpose. A method named {@code name()}
 * would be an OVERRIDE, and the override refusal sits several checks earlier — so the fixture
 * would be turned away before reaching the branch it was written for, which is the shadowing
 * this stage has already had to repair once.</p>
 */
public class TierColliding extends TierBase {

    private int name;

    public TierColliding(String label) {
        super(label);
    }

    public int counted() {
        return name;
    }
}
