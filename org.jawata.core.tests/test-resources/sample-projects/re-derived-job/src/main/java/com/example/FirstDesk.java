package com.example;

/**
 * POPULATION TWO, member 1 of 4 — a lookup.
 *
 * <p>The fixture's portrait of the OTHER population this sprint's predecessor found by
 * hand: five {@code findTypeDeclaration} copies and, later, a set of {@code typeNamed}
 * copies, each written to walk a unit and match a name. They were merged — the five now
 * forward to one {@code TypeLookup} and the rest were deleted — so the live tree no longer
 * holds them. That is exactly why they are here: a population that has been cured is still
 * a shape the detector must be able to see, and a fixture is the only place left that has
 * it.</p>
 *
 * <p>Four walks, four shapes: a for-each with an early return, an indexed loop, a while
 * loop, and one that keeps a candidate and answers at the end. All four reach through
 * {@link Catalogue} and {@link Part}.</p>
 */
public class FirstDesk {

    private static Part find(Catalogue catalogue, String name) {
        for (Part part : catalogue.parts()) {
            if (part.name().equals(name)) {
                return part;
            }
        }
        return null;
    }

    public Part locate(Catalogue catalogue, String name) {
        return find(catalogue, name);
    }
}
