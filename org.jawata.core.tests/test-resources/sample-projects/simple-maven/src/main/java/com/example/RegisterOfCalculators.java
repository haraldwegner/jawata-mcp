package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixture for Alternative Classes with Different Interfaces (Sprint 28d-rescue), the
 * other of the pair.
 *
 * <p>Same job as {@link LedgerByName}, same domain type, every method named differently.
 * The bodies differ too, which is the point: a duplicate-code check compares bodies and
 * would see nothing here.</p>
 */
public class RegisterOfCalculators {

    private final List<Calculator> held = new ArrayList<>();

    public void put(Calculator item) {
        if (!held.contains(item)) {
            held.add(item);
        }
    }

    public Calculator lookup(String key) {
        for (Calculator item : held) {
            if (key != null) {
                return item;
            }
        }
        return null;
    }

    public List<Calculator> everything() {
        return new ArrayList<>(held);
    }

    public void discard(Calculator item) {
        held.removeIf(existing -> existing == item);
    }
}
