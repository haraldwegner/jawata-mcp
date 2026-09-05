package com.example;

/**
 * Callers of {@link ErrorCodeTargets} in ANOTHER FILE — one set that ignores the sentinel, which
 * is the case this row is for, and two that test it, which are the cases it refuses.
 */
public class ErrorCodeDesk {

    private final ErrorCodeTargets targets = new ErrorCodeTargets();

    /** Carries on with whatever comes back, sentinel included. That is the bug. */
    public int doubled(int slot) {
        return targets.readingAt(slot) * 2;
    }

    /** A SECOND caller doing the same, so "no caller tests it" is about more than one. */
    public int halved(int slot) {
        return targets.readingAt(slot) / 2;
    }

    /** COMPARES the call directly, so it is handling the failure today. */
    public String describe(int slot) {
        if (targets.checkedReadingAt(slot) == -1) {
            return "no reading";
        }
        return "reading";
    }

    /** Holds the result and compares the LOCAL — the commoner shape, and the harder one to see. */
    public String describeHeld(int slot) {
        int reading = targets.heldReadingAt(slot);
        if (reading == -1) {
            return "no reading";
        }
        return "reading " + reading;
    }

    /** Calls the always-failing case, so it has callers and refuses for its returns instead. */
    public int always(int slot) {
        return targets.alwaysFails(slot);
    }
}
