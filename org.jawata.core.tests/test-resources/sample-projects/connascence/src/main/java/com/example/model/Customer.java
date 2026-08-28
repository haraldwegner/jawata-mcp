package com.example.model;

/** A model type. Only java.lang is referenced outward, and the JDK is not our boundary. */
public final class Customer {

    private final String name;

    public Customer(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
