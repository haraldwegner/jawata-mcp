package com.example;

/**
 * Fixture for the delete atom (Sprint 28d-rescue, Stage 1).
 *
 * <p>The field and its accessors are here together on purpose: the Eclipse engine offers
 * to take the accessors along when a field is deleted, and the atom answers no. Deleting
 * {@code tally} must leave {@code getTally} and {@code setTally} standing, so that a
 * composed row which wants them gone has to say so.</p>
 */
public class DeleteAtomTargets {

    private int tally;

    private String note = "kept";

    public int getTally() {
        return tally;
    }

    public void setTally(int tally) {
        this.tally = tally;
    }

    /** Deleted by name in the atom's first test; nothing else refers to it. */
    public String obsolete() {
        return "gone";
    }

    public String note() {
        return note;
    }
}
