package com.example;

/** The single user of InlineMe, which absorbs it (Sprint 28d-rescue, row 17). */
public class InlineAbsorber {

    private final InlineMe helper = new InlineMe();

    public int useOnce() {
        return helper.record();
    }

    public int total() {
        return helper.seen();
    }
}
