package com.example;

/**
 * ROW 57 REFUSES this — {@link ReconciledDesk} treats it AS a Ledgering, which is exactly the
 * substitutability that deleting `extends` takes away.
 */
public class Reconciled extends Ledgering {

    public Reconciled(String book) {
        super(book);
    }

    public String summary() {
        return posting("reconciled");
    }
}
