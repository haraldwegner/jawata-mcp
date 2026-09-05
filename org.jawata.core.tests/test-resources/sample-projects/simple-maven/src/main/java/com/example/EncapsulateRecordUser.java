package com.example;

/**
 * Reads a bare record's fields from another file.
 *
 * <p>Encapsulating a field rewrites every direct access to it, and the accesses that matter
 * are the ones the caller never pointed the tool at. A reader in the same file would not show
 * that.</p>
 */
public class EncapsulateRecordUser {

    public String describe(EncapsulateRecordTargets.Coordinate where) {
        return "at " + where.latitude + "/" + where.longitude;
    }

    public double northOf(EncapsulateRecordTargets.Coordinate where) {
        return where.latitude;
    }
}
