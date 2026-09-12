package com.example;

/** CONTROL ONE, half 1 — an imposed signature, not a re-derivation. */
public class FlatPricing implements Pricing {

    @Override
    public Money quote(Order order) {
        return Money.of(order.total());
    }
}
