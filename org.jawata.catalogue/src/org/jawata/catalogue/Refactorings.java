package org.jawata.catalogue;

import java.util.ArrayList;
import java.util.List;

/**
 * The published refactoring catalogue, as a production table no engine owns.
 *
 * <h2>jawata-duplicate: this REPLACES {@code PerformedRefactoringCountTest#catalogue}</h2>
 *
 * <p>That method is the same 66 rows, and it is the reason this class exists rather than a
 * second implementation of it. Sprint 28g's D39 and the architecture's P2 step 3 both say the
 * table moves out of the test tree into production, because a table in a test cannot be read
 * by the product, cannot be declared against by a second language, and is reachable only by
 * whatever happens to be on the test classpath.</p>
 *
 * <p>The old method stays for exactly one commit as the TRANSCRIPTION CONTROL: a test asserts
 * the names here equal the names there, so a typo in any of the 66 goes red rather than
 * silently shipping a renamed refactoring. It is deleted in the step that follows, and until
 * then the two are not two sources of truth — one is the subject and the other is its proof.</p>
 *
 * <h2>What is external here, and why that matters</h2>
 *
 * <p>These rows are Fowler's published catalogue (refactoring.com, read 2026-09-02, recorded in
 * the signed spec at {@code 7ffd516}). <b>No object here owns them.</b> That is the whole
 * soundness argument for every count taken against this table: a catalogue derived from our own
 * operations would agree with them by construction and could never fail. The same exemption
 * {@code EverySmellIsDetectedOrDeclinedTest} claims for the twenty-four smells, for the same
 * reason.</p>
 *
 * <p>What is NOT here is what jawata does about each row. The old table's third column named
 * one engine kind per row; that column is now each engine's own coverage declaration, keyed by
 * {@link CatalogueRow#key()}. A row the product declines — Fowler 3, 6, 20 and 66 — is not a
 * row with a missing operation, it is a row an engine declares NOT APPLICABLE with a reason,
 * which is a different and checkable claim.</p>
 */
public final class Refactorings {

    /** The origin prefix every row of Fowler's catalogue carries. */
    public static final String FOWLER = "fowler";

    private static final List<CatalogueRow> ROWS = rows();

    private Refactorings() {}

    /**
     * Every refactoring this lane holds, in catalogue order.
     *
     * <p>THE CROSS-LANE QUESTIONS MOVED, and the move is part of the deliverable rather than
     * tidying. {@code withOrigin} and {@code byKey} lived here while this was the only table;
     * the arrival of {@link Smells} would have made each of them a second copy of one filter,
     * and the pattern rows a third. Both now live once on {@link Catalogue}. {@code byKey} had
     * ZERO callers here, measured before the move — a caller holding {@code fowler:smell-14} is
     * asking the catalogue what that key is, and a lane is the wrong object to ask, because
     * answering would require them to already know which lane it is in.</p>
     */
    public static List<CatalogueRow> all() {
        return ROWS;
    }

    private static List<CatalogueRow> rows() {
        List<CatalogueRow> r = new ArrayList<>();
        fowler(r, 1, "Change Function Declaration");
        fowler(r, 2, "Change Reference to Value");
        fowler(r, 3, "Change Value to Reference");
        fowler(r, 4, "Collapse Hierarchy");
        fowler(r, 5, "Combine Functions into Class");
        fowler(r, 6, "Combine Functions into Transform");
        fowler(r, 7, "Consolidate Conditional Expression");
        fowler(r, 8, "Decompose Conditional");
        fowler(r, 9, "Encapsulate Collection");
        fowler(r, 10, "Encapsulate Record");
        fowler(r, 11, "Encapsulate Variable");
        fowler(r, 12, "Extract Class");
        fowler(r, 13, "Extract Function");
        fowler(r, 14, "Extract Superclass");
        fowler(r, 15, "Extract Variable");
        fowler(r, 16, "Hide Delegate");
        fowler(r, 17, "Inline Class");
        fowler(r, 18, "Inline Function");
        fowler(r, 19, "Inline Variable");
        fowler(r, 20, "Introduce Assertion");
        fowler(r, 21, "Introduce Parameter Object");
        fowler(r, 22, "Introduce Special Case");
        fowler(r, 23, "Move Field");
        fowler(r, 24, "Move Function");
        fowler(r, 25, "Move Statements into Function");
        fowler(r, 26, "Move Statements to Callers");
        fowler(r, 27, "Parameterize Function");
        fowler(r, 28, "Preserve Whole Object");
        fowler(r, 29, "Pull Up Constructor Body");
        fowler(r, 30, "Pull Up Field");
        fowler(r, 31, "Pull Up Method");
        fowler(r, 32, "Push Down Field");
        fowler(r, 33, "Push Down Method");
        fowler(r, 34, "Remove Dead Code");
        fowler(r, 35, "Remove Flag Argument");
        fowler(r, 36, "Remove Middle Man");
        fowler(r, 37, "Remove Setting Method");
        fowler(r, 38, "Remove Subclass");
        fowler(r, 39, "Rename Field");
        fowler(r, 40, "Rename Variable");
        fowler(r, 41, "Replace Command with Function");
        fowler(r, 42, "Replace Conditional with Polymorphism");
        fowler(r, 43, "Replace Constructor with Factory Function");
        fowler(r, 44, "Replace Control Flag with Break");
        fowler(r, 45, "Replace Derived Variable with Query");
        fowler(r, 46, "Replace Error Code with Exception");
        fowler(r, 47, "Replace Exception with Precheck");
        fowler(r, 48, "Replace Function with Command");
        fowler(r, 49, "Replace Inline Code with Function Call");
        fowler(r, 50, "Replace Loop with Pipeline");
        fowler(r, 51, "Replace Magic Literal");
        fowler(r, 52, "Replace Nested Conditional with Guard Clauses");
        fowler(r, 53, "Replace Parameter with Query");
        fowler(r, 54, "Replace Primitive with Object");
        fowler(r, 55, "Replace Query with Parameter");
        fowler(r, 56, "Replace Subclass with Delegate");
        fowler(r, 57, "Replace Superclass with Delegate");
        fowler(r, 58, "Replace Temp with Query");
        fowler(r, 59, "Replace Type Code with Subclasses");
        fowler(r, 60, "Return Modified Value");
        fowler(r, 61, "Separate Query from Modifier");
        fowler(r, 62, "Slide Statements");
        fowler(r, 63, "Split Loop");
        fowler(r, 64, "Split Phase");
        fowler(r, 65, "Split Variable");
        fowler(r, 66, "Substitute Algorithm");
        return List.copyOf(r);
    }

    private static void fowler(List<CatalogueRow> rows, int number, String name) {
        rows.add(new CatalogueRow(FOWLER + CatalogueRow.SEPARATOR + number, name));
    }
}
