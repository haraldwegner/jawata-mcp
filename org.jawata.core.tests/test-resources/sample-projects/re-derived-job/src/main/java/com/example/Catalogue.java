package com.example;

import java.util.ArrayList;
import java.util.List;

/** Scenery: what a lookup walks. */
public class Catalogue {

    private final List<Part> parts = new ArrayList<>();

    public List<Part> parts() {
        return parts;
    }

    public int size() {
        return parts.size();
    }
}
