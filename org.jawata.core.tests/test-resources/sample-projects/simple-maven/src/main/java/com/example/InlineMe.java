package com.example;

/**
 * Fixture for Inline Class (Sprint 28d-rescue, row 17): a class that stopped earning its
 * own name. One user, one field holding it, no subtypes, no constructor body.
 */
public class InlineMe {

    private int calls;

    public int record() {
        calls++;
        return calls;
    }

    public int seen() {
        return calls;
    }
}
