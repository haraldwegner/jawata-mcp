package org.jawata.mcp.field;

/**
 * The studio↔store contract version (Sprint 28b, D7 — the decision recorded in
 * ARCHITECTURE-field-recordings-28b.md): the hook binary sends this integer
 * with each request, the store echoes its own, and a mismatch is a TYPED,
 * counted outcome — never silence. The pile files carry the same version in
 * their header because studio reads them directly.
 */
public final class FieldContract {

    /** Bump on any semantic change to what crosses the seam. */
    public static final int VERSION = 1;

    /** The HTTP header both sides use to carry the contract version. */
    public static final String HEADER = "X-Jawata-Contract";

    private FieldContract() {
    }
}
