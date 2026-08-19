package com.example.rec;

/** The transitive target: A sees this only through B's reexport. */
public class Leaf {
    public int depth() {
        return 2;
    }
}
