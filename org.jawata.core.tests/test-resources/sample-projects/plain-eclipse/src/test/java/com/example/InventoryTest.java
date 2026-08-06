package com.example;

/** Test code whose root carries test="true" in .classpath — Stage 2 tags from that. */
public class InventoryTest {

    void counts() {
        if (new Inventory().countOf("abc") != 3) {
            throw new AssertionError("count");
        }
    }
}
