package com.example.rea;

import com.example.reb.Middle;
import com.example.rec.Leaf;

/** Uses C's type while requiring only B — legal via B's reexport. */
public class Top {
    public int probe() {
        Leaf leaf = new Middle().leaf();
        return leaf.depth();
    }
}
