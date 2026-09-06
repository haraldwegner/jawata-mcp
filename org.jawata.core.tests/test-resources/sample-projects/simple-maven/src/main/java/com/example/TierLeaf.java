package com.example;

/**
 * ROW 4 REFUSES this and hands the caller row 38 by name: a subclass with no subtypes of its
 * own is Remove Subclass ({@code inline kind=subclass}), not Collapse Hierarchy. The two rows
 * partition on exactly this precondition, and each refusal names the other.
 */
public class TierLeaf extends TierBase {

    public TierLeaf(String name) {
        super(name);
    }

    public int flat() {
        return 0;
    }
}
