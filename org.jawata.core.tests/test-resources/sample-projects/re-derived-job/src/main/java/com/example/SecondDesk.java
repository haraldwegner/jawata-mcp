package com.example;

import java.util.List;

/** POPULATION TWO, member 2 of 4 — the same job, derived with an indexed loop. */
public class SecondDesk {

    private static Part find(Catalogue catalogue, String name) {
        List<Part> all = catalogue.parts();
        for (int i = 0; i < all.size(); i++) {
            Part candidate = all.get(i);
            if (name.equals(candidate.name())) {
                return candidate;
            }
        }
        return null;
    }

    public Part locate(Catalogue catalogue, String name) {
        return find(catalogue, name);
    }
}
