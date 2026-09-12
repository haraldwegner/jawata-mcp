package com.example;

/** CONTROL ONE, half 2 — an imposed signature, not a re-derivation. */
public class TieredPricing implements Pricing {

    @Override
    public Money quote(Order order) {
        long running = order.total();
        if (running > 50L) {
            running = running - 10L;
        }
        return Money.of(running);
    }
}
