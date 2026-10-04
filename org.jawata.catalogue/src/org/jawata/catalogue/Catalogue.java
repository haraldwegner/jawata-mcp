package org.jawata.catalogue;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole published catalogue — every lane's rows, and the questions that are about the table
 * rather than about one lane.
 *
 * <h2>Why these three methods are HERE and not on each lane</h2>
 *
 * <p>{@code withOrigin} and {@code byKey} were written on {@link Refactorings} when it was the
 * only table. The moment {@link Smells} arrived they would have been copied — the same filter over
 * a different list, twice, then three times when the pattern rows land. This repository has paid
 * for that shape repeatedly: five code generators each carrying a byte-identical type lookup, six
 * copies of one {@code typeNamed} that had already drifted into two behaviours while both were
 * correct, thirty-three private copies of a four-line parse helper. Each of those was closed by
 * giving the question ONE owner, and the cheapest moment to do that is before the second copy
 * exists rather than after the sixth.</p>
 *
 * <p>So a lane answers only what is a fact about itself — {@code Refactorings.all()},
 * {@code Smells.all()} — and every question that spans lanes is asked here. {@code byKey} in
 * particular was never a lane's question: a caller holding {@code fowler:smell-14} is asking the
 * catalogue what that key is, and requiring them to know which table it lives in would make them
 * answer the question they came to ask. It had zero callers on {@code Refactorings}, measured, so
 * moving it breaks nothing and stops it being copied into a lane that would have the same
 * problem.</p>
 *
 * <h2>Lane membership is a fact about the row, not a parameter</h2>
 *
 * <p>There is deliberately no {@code lane(String)} accessor. The lanes differ in what an engine
 * DECLARES about them — a refactoring is performed, a smell is detected — so a caller that wants
 * one lane names it by its own table and gets a list whose meaning is unambiguous. A string
 * parameter would let a caller ask for "smells" and receive refactorings if the spelling drifted.</p>
 */
public final class Catalogue {

    private Catalogue() {}

    /**
     * Every row the catalogue holds, lane by lane, in each lane's own order.
     *
     * <p>The order of the LANES is this method's choice and nothing may depend on it; the order
     * WITHIN a lane is its published catalogue's and is load-bearing. A caller that needs one
     * lane's order asks that lane.</p>
     */
    public static List<CatalogueRow> rows() {
        List<CatalogueRow> all = new ArrayList<>();
        all.addAll(Refactorings.all());
        all.addAll(Smells.all());
        all.addAll(Patterns.all());
        return List.copyOf(all);
    }

    /**
     * The rows from one origin — {@code withOrigin("fowler")} for everything Fowler published.
     *
     * <p>Scoping by origin is what lets a count mean something once a second language's rows join
     * the table: "66 refactorings" is a claim about the {@code fowler:} refactoring lane, never
     * about the table's size. Note that an origin spans LANES — Fowler published both the
     * refactorings and the smells — so this answers more than one lane's worth and a count taken
     * from it must say which lane it meant.</p>
     */
    public static List<CatalogueRow> withOrigin(String origin) {
        List<CatalogueRow> out = new ArrayList<>();
        for (CatalogueRow r : rows()) {
            if (r.origin().equals(origin)) {
                out.add(r);
            }
        }
        return List.copyOf(out);
    }

    /** The row with this key, whatever lane it lives in, or {@code null} if nothing holds it. */
    public static CatalogueRow byKey(String key) {
        for (CatalogueRow r : rows()) {
            if (r.key().equals(key)) {
                return r;
            }
        }
        return null;
    }
}
