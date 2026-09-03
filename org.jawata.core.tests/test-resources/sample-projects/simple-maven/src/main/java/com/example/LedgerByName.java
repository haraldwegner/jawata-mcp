package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixture for Alternative Classes with Different Interfaces (Sprint 28d-rescue), one of
 * the pair.
 *
 * <p>This class and {@link RegisterOfCalculators} do the same job over the same domain
 * type and share no supertype, and every method is named differently. That is the smell:
 * neither can stand in for the other, for no reason but the names.</p>
 */
public class LedgerByName {

    private final List<Calculator> entries = new ArrayList<>();

    public void addEntry(Calculator entry) {
        entries.add(entry);
    }

    public Calculator findEntry(String id) {
        return entries.isEmpty() ? null : entries.get(0);
    }

    public List<Calculator> allEntries() {
        return List.copyOf(entries);
    }

    public void removeEntry(Calculator entry) {
        entries.remove(entry);
    }
}
