package com.example;

/** mcp#63 — the construction sites, in a SECOND file so the migration is cross-file. */
public final class RecordComponentDesk {

    public RecordComponentTargets.Reading first() {
        return new RecordComponentTargets.Reading("north", 21);
    }

    public RecordComponentTargets.Reading second() {
        return new RecordComponentTargets.Reading("south", 19);
    }

    public RecordComponentTargets.Bounded bounded() {
        return new RecordComponentTargets.Bounded(1, 10);
    }

    private RecordComponentDesk() {
    }
}
