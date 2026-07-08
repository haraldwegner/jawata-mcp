package com.example;

/**
 * A call site of {@link Mover#reset(Cell)}. After move_method the call
 * {@code mover.reset(cell)} must be rewritten to invoke the method on its new
 * receiver ({@code cell.reset()}).
 */
public class Client {

    void run() {
        Mover mover = new Mover();
        Cell cell = new Cell();
        mover.reset(cell);
    }
}
