package com.example;

/** Row 58 (Sprint 28d-rescue): one temp worth replacing with a query, one that is not. */
public class TempHolder {

    private final int quantity = 40;

    public int total() {
        int basePrice = quantity * 7;
        return basePrice > 100 ? basePrice * 2 : basePrice + 1;
    }

    public int reassigned() {
        int running = quantity * 2;
        running = running + 3;
        return running;
    }
}
