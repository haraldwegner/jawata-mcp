package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 28g S5 / D9 row 3 — the member nothing references, beside every member that LOOKS
 * like it and must be refused.
 *
 * <p>The census's five row-75 edits are of exactly this shape: a pair of public constructors,
 * {@code setCallback}, {@code setCancelDrains}, {@code routeFillToAlgo}/{@code routeCancelToAlgo}
 * and {@code startSLExitForPartialFill} — public, zero references, deleted by a human who had
 * already proved them dead, because <em>"the cleanup covers private members only"</em>.</p>
 *
 * <p>The refused cases are the point of the fixture rather than padding. A reference search
 * returns zero for a member that is genuinely dead AND for an override whose callers go through
 * its supertype — the two are indistinguishable in the search result and completely different in
 * what deleting them does.</p>
 */
public class Registry {

    /**
     * TWO FIELDS, ONE DECLARATION, which is the whole point of this line — removing the
     * declaration node to delete {@code shared} would take {@code alsoShared} with it. Written
     * as two separate declarations first, where the refusal could never have fired.
     */
    public int shared = 1, alsoShared = 2;

    private final List<String> entries = new ArrayList<>();

    /** THE CASE: public, and nothing in this project mentions it. */
    public void retireEverything() {
        entries.clear();
    }

    /** REFUSED — {@link Caller} calls it, so it is not dead. */
    public void register(String entry) {
        entries.add(entry);
    }

    /** REFUSED — private, where the COMPILER proves deadness and the search is the weaker tool. */
    private void unusedAndPrivate() {
        entries.size();
    }

    /**
     * REFUSED — it overrides {@link Object#toString()}, so its callers never name it directly
     * and a zero-reference result says nothing about whether it is reachable.
     */
    @Override
    public String toString() {
        return "Registry(" + entries.size() + ")";
    }
}
