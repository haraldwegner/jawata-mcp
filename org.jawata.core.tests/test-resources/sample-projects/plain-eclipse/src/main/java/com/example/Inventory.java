package com.example;

/** Production class in a plain Eclipse project. */
public class Inventory {

    /** Returns the count held for the given item. */
    public int countOf(String item) {
        return item == null ? 0 : item.length();
    }
}
