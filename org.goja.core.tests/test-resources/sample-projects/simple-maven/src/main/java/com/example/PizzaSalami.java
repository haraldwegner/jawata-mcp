package com.example;

/** A salami pizza - Sprint 18 copy_class fixture (single top-level type + self-references). */
public class PizzaSalami {

    private final int slices;

    public PizzaSalami(int slices) {
        this.slices = slices;
    }

    public int slices() {
        return slices;
    }

    /** Self-reference: both the return type and the constructor call name this type. */
    public PizzaSalami withExtraSlice() {
        return new PizzaSalami(slices + 1);
    }
}
