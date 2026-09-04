package com.example;

/** The data two loose functions keep passing to each other (Sprint 28d-rescue, row 5). */
public class Reading {

    private final int quantity;

    public Reading(int quantity) {
        this.quantity = quantity;
    }

    public int quantity() {
        return quantity;
    }
}
