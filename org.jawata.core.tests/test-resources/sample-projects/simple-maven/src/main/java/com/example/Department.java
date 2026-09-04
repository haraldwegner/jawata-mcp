package com.example;

/** The delegate a middle man hides (Sprint 28d-rescue, row 36). */
public class Department {

    private final String manager;

    public Department(String manager) {
        this.manager = manager;
    }

    public String manager() {
        return manager;
    }

    public int headcount() {
        return 12;
    }
}
