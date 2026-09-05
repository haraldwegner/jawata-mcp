package com.example;

import com.example.ReplacePrimitiveTargets.Shipment;

/**
 * The OTHER file — what makes row 54's cross-file claim checkable rather than asserted.
 *
 * <p>It reads and writes a field of Shipment from outside the declaring file, so a run that
 * only rewrote the declaration would leave this reading a record where a String is expected.
 * That is the half replace_type_code_with_class says in its own description it does not do.</p>
 *
 * <p><b>Nothing in this file quotes the expected OUTPUT, and that is deliberate.</b> The first
 * version of it did, in these comments — and the test asserting the migration then passed on
 * the comment rather than on the code, which the parity golden caught by disagreeing with a
 * green test. A fixture must not contain the string its own test searches for.</p>
 */
public class ReplacePrimitiveUser {

    /** A read from another file. */
    public String manifestFor(Shipment shipment) {
        return "manifest: " + shipment.carrier;
    }

    /** A write from another file. */
    public void reassign(Shipment shipment, String name) {
        shipment.carrier = name;
    }
}
