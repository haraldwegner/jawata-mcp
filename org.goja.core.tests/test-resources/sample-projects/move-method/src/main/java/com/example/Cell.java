package com.example;

/**
 * The collaborator a moved instance method should land on (Sprint 22a
 * move_method). {@link Mover#reset(Cell)} operates entirely on a Cell, so it
 * belongs here as {@code Cell.reset()}.
 */
public class Cell {

    private int value;

    public int value() {
        return value;
    }

    public void setValue(int v) {
        this.value = v;
    }
}
