package com.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixture for the Global Data detector (Sprint 28d-rescue).
 *
 * <p>Every field below is deliberate. The three at the top must be reported, the four
 * after them must not, and the private one must be reported only when the caller opts
 * in — so the check is pinned in both directions rather than only where it speaks.</p>
 *
 * <p>It carries three methods on purpose: with fewer it would also be a Lazy Class
 * candidate, and a fixture that trips a neighbouring detector makes that detector's
 * tests harder to read for no gain here.</p>
 */
public class GlobalDataTargets {

    /** REPORTED: static and not final — anything that sees it can reassign it. */
    public static int mutableCounter;

    /** REPORTED: final fixes the reference; every element of the list stays writable. */
    public static final List<String> SHARED_NAMES = new ArrayList<>();

    /** REPORTED: an array is mutable whatever it holds. */
    public static final int[] SHARED_SLOTS = new int[4];

    /** REPORTED: a map is the same case as the list. */
    public static final Map<String, String> SHARED_INDEX = new HashMap<>();

    /** NOT reported: a String is immutable, so this is a name for a value. */
    public static final String LABEL = "global-data-fixture";

    /** NOT reported: a primitive constant. */
    public static final int LIMIT = 4;

    /** NOT reported: an instance field is not global data. */
    private int perInstance;

    /**
     * NOT reported at the default reach, REPORTED at threshold 1: shared between every
     * instance of this class, and reachable from nowhere else.
     */
    private static int hiddenCounter;

    public int bump() {
        hiddenCounter++;
        perInstance++;
        return hiddenCounter + perInstance;
    }

    public void remember(String name) {
        SHARED_NAMES.add(name);
        SHARED_INDEX.put(name, LABEL);
    }

    public int slotTotal() {
        int total = 0;
        for (int slot : SHARED_SLOTS) {
            total += slot;
        }
        return total + LIMIT + mutableCounter;
    }
}
