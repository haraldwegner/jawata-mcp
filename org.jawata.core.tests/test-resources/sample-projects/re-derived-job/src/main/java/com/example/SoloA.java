package com.example;

/**
 * CONTROL THREE, half 1 — shares exactly ONE collaborator with its twin.
 *
 * <p>This and {@link SoloB} have the same shape, no supertype between them and neither
 * calls the other. They share {@link Item} and nothing else, which is below the threshold —
 * the condition that keeps {@code String f(Item)} from pairing the whole package.</p>
 */
public class SoloA {

    public String tag(Item item) {
        Order order = new Order();
        return item.label() + order.lines();
    }
}
