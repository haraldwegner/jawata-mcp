package com.example.reb;

import com.example.rec.Leaf;

/** Requires C and reexports it. */
public class Middle {
    public Leaf leaf() {
        return new Leaf();
    }
}
