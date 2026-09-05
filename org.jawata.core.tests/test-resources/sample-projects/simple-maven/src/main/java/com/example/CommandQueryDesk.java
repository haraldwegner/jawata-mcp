package com.example;

/**
 * Callers of {@link CommandQueryTargets} in ANOTHER FILE — the half of row 61 that a single-file
 * fixture cannot show, and it carries BOTH call shapes the row distinguishes.
 */
public class CommandQueryDesk {

    private final CommandQueryTargets targets = new CommandQueryTargets();

    /** Uses the answer, so this call must be SPLIT into a command and a query. */
    public int afterOneFailure() {
        int count = targets.recordFailure();
        return count * 2;
    }

    /** Ignores the answer, so this call was only ever a command and must be left alone. */
    public void justRecord() {
        targets.recordFailure();
    }
}
