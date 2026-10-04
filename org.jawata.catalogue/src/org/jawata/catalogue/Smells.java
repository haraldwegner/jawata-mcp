package org.jawata.catalogue;

import java.util.ArrayList;
import java.util.List;

/**
 * The published catalogue of code smells, as a production table no engine owns.
 *
 * <h2>jawata-duplicate: this REPLACES {@code EverySmellIsDetectedOrDeclinedTest#FOWLER_CHAPTER_3}</h2>
 *
 * <p>That map is the same twenty-four names, and it is the reason this class exists rather than a
 * second list of them. Sprint 28g's D39 and the architecture's P2 step 3 move the table out of the
 * test tree for the same reason the refactorings moved: a table inside a test cannot be read by the
 * product, cannot be declared against by a second language, and is reachable only by whatever
 * happens to be on the test classpath.</p>
 *
 * <p>The old map stays for exactly one commit as the TRANSCRIPTION CONTROL — a test asserts the
 * names here equal the names there, in order. It is deleted in the step that follows.</p>
 *
 * <h2>WHAT IS DELIBERATELY NOT HERE: the kind</h2>
 *
 * <p>The old map's value was the {@code find_quality_issue} kind that reports each smell —
 * {@code long_method} for Long Function, {@code god_class} for Large Class. <b>That is the Java
 * engine's spelling, and it is the same third column that was removed from
 * {@link Refactorings}.</b> A C++ engine detecting Long Function would report it under a name of
 * its own, and a row that hard-codes ours could not describe that. So the row says WHAT the smell
 * is; which kind detects it is each engine's own declaration, keyed by {@link CatalogueRow#key()}.</p>
 *
 * <p>Two of the twenty-four are DECLINED rather than detected — Mysterious Name and Comments. They
 * are rows here like any other: a declination is a claim an engine makes about a row, with a
 * reason, and the row has to exist for the claim to be about anything. {@code NOT_APPLICABLE} is
 * not {@code NONE}, and the engine owes a note either way.</p>
 *
 * <h2>The keys</h2>
 *
 * <p>{@code fowler:smell-1} … {@code fowler:smell-24}. The origin is Fowler, the same as the
 * refactorings, because he published both — but his chapter 3 numbers nothing, so the id carries
 * the lane and the chapter's own order. Keeping the lane IN the id is what lets one key space hold
 * both of his tables without either one renumbering the other.</p>
 */
public final class Smells {

    /** The id prefix every row of Fowler's chapter 3 carries, after the origin. */
    public static final String SMELL = "smell-";

    private static final List<CatalogueRow> ROWS = rows();

    private Smells() {}

    /**
     * Every smell this catalogue holds, in catalogue order.
     *
     * <p>Cross-lane questions — every row whatever its lane, a row by key, the rows of one
     * origin — belong to {@link Catalogue}, which answers them once for every table rather
     * than once per table.</p>
     */
    public static List<CatalogueRow> all() {
        return ROWS;
    }

    private static List<CatalogueRow> rows() {
        List<CatalogueRow> r = new ArrayList<>();
        // Fowler, Refactoring 2nd ed., chapter 3 — in the book's own order. These are HIS names,
        // verbatim, including the ones our kinds spell differently (Lazy Element, Insider Trading,
        // Repeated Switches, Large Class, Long Function). The engine maps its spelling to the key;
        // the key never adopts the engine's.
        smell(r, 1, "Mysterious Name");
        smell(r, 2, "Duplicated Code");
        smell(r, 3, "Long Function");
        smell(r, 4, "Long Parameter List");
        smell(r, 5, "Global Data");
        smell(r, 6, "Mutable Data");
        smell(r, 7, "Divergent Change");
        smell(r, 8, "Shotgun Surgery");
        smell(r, 9, "Feature Envy");
        smell(r, 10, "Data Clumps");
        smell(r, 11, "Primitive Obsession");
        smell(r, 12, "Repeated Switches");
        smell(r, 13, "Loops");
        smell(r, 14, "Lazy Element");
        smell(r, 15, "Speculative Generality");
        smell(r, 16, "Temporary Field");
        smell(r, 17, "Message Chains");
        smell(r, 18, "Middle Man");
        smell(r, 19, "Insider Trading");
        smell(r, 20, "Large Class");
        smell(r, 21, "Alternative Classes with Different Interfaces");
        smell(r, 22, "Data Class");
        smell(r, 23, "Refused Bequest");
        smell(r, 24, "Comments");
        return List.copyOf(r);
    }

    private static void smell(List<CatalogueRow> rows, int number, String name) {
        rows.add(new CatalogueRow(
                Refactorings.FOWLER + CatalogueRow.SEPARATOR + SMELL + number, name));
    }
}
