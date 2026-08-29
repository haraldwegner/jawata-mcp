package com.example.factory;

import java.util.List;

/**
 * Sprint 28d Stage 8 fixture — the CALL SITES.
 *
 * <p>Three constructor calls, in a file OTHER than the one declaring the
 * constructor. That separation is the point: rewriting a call in the constructor's
 * own compilation unit proves far less than rewriting one across a file boundary,
 * and cross-file reference migration is the case most likely to break on real code.
 * Stage 7 could not demonstrate it — neither of its fixtures had a second file
 * touching the moved state, and its tests say so.</p>
 *
 * <p>Three rather than one, so a partial rewrite is visible: an operation that
 * fixed the first call and missed the rest would leave this file uncompilable once
 * the constructor is private, and the parity assertion catches exactly that.</p>
 *
 * <p><b>This javadoc deliberately does NOT write the constructor-call expression
 * out.</b> The test counts occurrences in the source TEXT, and text cannot tell a
 * call from prose describing one — an earlier draft mentioned the expression here
 * and the proof-of-life assertion duly counted four calls where three exist. The
 * sharper cost would have come later: a comment survives the refactoring, so the
 * "no constructor call remains" assertion would have failed on a CORRECT result.
 * Same shape as two other defects this sprint — talking about refusing is not
 * refusing, and discussing a design choice is not requesting a ruling.</p>
 */
public class Dispatcher {

    public Shipment express(String destination) {
        return new Shipment(destination, 1);
    }

    public Shipment bulk(String destination, int weightKg) {
        return new Shipment(destination, weightKg);
    }

    public List<Shipment> pair(String destination) {
        return List.of(new Shipment(destination, 2), express(destination));
    }
}
