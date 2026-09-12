package com.example;

import java.util.List;

/** POPULATION TWO, member 3 of 4 — the same job, derived with a while loop. */
public class ThirdDesk {

    private static Part find(Catalogue catalogue, String name) {
        List<Part> all = catalogue.parts();
        int index = 0;
        while (index < all.size()) {
            Part here = all.get(index);
            String label = here.name();
            if (label != null && label.equals(name)) {
                return here;
            }
            index++;
        }
        return null;
    }

    public Part locate(Catalogue catalogue, String name) {
        return find(catalogue, name);
    }
}
