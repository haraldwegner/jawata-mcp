package com.example;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fixture for the Mutable Data detector (Sprint 28d-rescue).
 *
 * <p>The two at the top hand out the object's insides. The four after them return the
 * same state safely, each by a different wrapping, and none of them may be reported —
 * a check that flagged those would be switched off within a day, because returning a
 * read-only view is the CURE it is recommending.</p>
 */
public class MutableDataTargets {

    private final List<String> items = new ArrayList<>();
    private final int[] slots = new int[3];
    private final String label = "fixture";

    /** REPORTED: hands out the list itself. */
    public List<String> getItems() {
        return items;
    }

    /** REPORTED: an array is handed out the same way, through this.field. */
    public int[] getSlots() {
        return this.slots;
    }

    /** NOT reported: a read-only view is the cure, not the smell. */
    public List<String> viewItems() {
        return Collections.unmodifiableList(items);
    }

    /** NOT reported: a copy leaves the original alone. */
    public List<String> copyItems() {
        return new ArrayList<>(items);
    }

    /** NOT reported: so does an array copy. */
    public int[] copySlots() {
        return Arrays.copyOf(slots, slots.length);
    }

    /** NOT reported: a String is immutable, so handing it back changes nothing. */
    public String getLabel() {
        return label;
    }

    /** NOT reported: private, so the leak does not leave the class. */
    private List<String> rawItems() {
        return items;
    }

    public int size() {
        return rawItems().size() + slots.length;
    }
}
