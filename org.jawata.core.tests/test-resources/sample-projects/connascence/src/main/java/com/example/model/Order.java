package com.example.model;

/**
 * Dense connascence with {@link Customer} and {@link Money} — and all of it stays
 * inside com.example.model, so this package's CROSS-boundary weight is zero while
 * its within-package site count is high.
 */
public final class Order {

    private final Customer customer;
    private final Money total;

    public Order(Customer customer, Money total) {
        this.customer = customer;
        this.total = total;
    }

    public Customer customer() {
        return customer;
    }

    public Money total() {
        return total;
    }
}
