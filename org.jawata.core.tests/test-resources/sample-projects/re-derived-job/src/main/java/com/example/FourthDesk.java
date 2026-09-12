package com.example;

/** POPULATION TWO, member 4 of 4 — the same job, derived by answering at the end. */
public class FourthDesk {

    private static Part find(Catalogue catalogue, String name) {
        Part found = null;
        for (Part part : catalogue.parts()) {
            String label = part.name();
            if (found == null && label.equalsIgnoreCase(name)) {
                found = part;
            }
        }
        return found;
    }

    public Part locate(Catalogue catalogue, String name) {
        return find(catalogue, name);
    }
}
