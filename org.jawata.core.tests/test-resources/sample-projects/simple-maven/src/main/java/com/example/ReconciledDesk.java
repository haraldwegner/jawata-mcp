package com.example;

/** The caller that makes Reconciled un-delegatable: it uses one AS a Ledgering. */
public class ReconciledDesk {

    /** Assigns it to a Ledgering-typed variable — the substitution row 57 must refuse. */
    public String viaSupertype() {
        Ledgering held = new Reconciled("cash");
        return held.posting("via the supertype");
    }
}
