package com.example;

/**
 * mcp#63 fixture — records in the three canonical-constructor shapes, plus their
 * construction sites.
 *
 * <p>Named with a Record* prefix DELIBERATELY. This shared project has had a counted
 * population moved four times by a fixture added for one row — a findings page, a
 * clone-group page, a naming population and a search ranking — so this file adds no
 * badly-named member, no new package, and no duplicate simple name.</p>
 */
public final class RecordComponentTargets {

    /** IMPLICIT canonical constructor — the commonest shape, and the one #63 was filed about. */
    public record Reading(String sensor, int celsius) { }

    /** COMPACT canonical constructor: no parameter list, so nothing to edit when a component arrives. */
    public record Bounded(int low, int high) {
        public Bounded {
            if (low > high) {
                throw new IllegalArgumentException("low above high");
            }
        }
    }

    /** EXPLICIT canonical constructor: its body assigns each component, so a new one needs code. */
    public record Labelled(String name, int weight) {
        public Labelled(String name, int weight) {
            this.name = name.trim();
            this.weight = weight;
        }
    }

    /** A record with NO construction anywhere — so a default is not owed. */
    public record Unbuilt(String only) { }

    private RecordComponentTargets() {
    }
}
