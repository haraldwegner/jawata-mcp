package com.example.crossfile;

import java.util.List;

/**
 * THE SECOND FILE — it reads the fields the extraction moves, and it is the only
 * reason this fixture exists.
 *
 * <p>Every reference below reaches {@code label} or {@code unit} directly on a
 * {@code Measurement} instance, from outside that instance's own file. If Extract
 * Class moves those fields and does not follow their readers here, this file stops
 * compiling — which is exactly the failure the operation's contract says it
 * prevents.</p>
 *
 * <p>Deliberately no reference in a comment or a string. An earlier fixture in this
 * sprint carried {@code new Shipment(...)} inside its own javadoc, and a test
 * counting occurrences read four where three existed. Comments survive
 * refactorings, so a reference written in one is a reference the operation is
 * right to leave alone and a count is wrong to include.</p>
 */
public class Report {

    /** A read through a parameter — the plainest cross-file form. */
    static String headline(Measurement m) {
        return m.label + ": " + m.value + m.unit;
    }

    /** A read through a local, so the rewrite must follow a variable and not just a parameter name. */
    static String footnote(List<Measurement> all) {
        StringBuilder out = new StringBuilder();
        for (Measurement m : all) {
            Measurement current = m;
            out.append(current.unit).append('/');
        }
        return out.toString();
    }

    /** A WRITE from another file, which a read-only rewrite would miss. */
    static void relabel(Measurement m, String newLabel) {
        m.label = newLabel;
    }
}
