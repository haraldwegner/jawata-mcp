package com.example;

/**
 * Holds an instance method that really operates on its {@link Cell} parameter —
 * the canonical Move Instance Method candidate. {@code move_method(target="c")}
 * relocates {@link #reset(Cell)} onto {@link Cell} as {@code reset()}.
 */
public class Mover {

    public void reset(Cell c) {
        c.setValue(0);
    }
}
