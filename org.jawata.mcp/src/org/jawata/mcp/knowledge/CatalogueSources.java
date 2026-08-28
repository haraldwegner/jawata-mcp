package org.jawata.mcp.knowledge;

import java.util.List;

/**
 * THE REGISTRY — the one list every catalogue-aware path iterates.
 *
 * <p>Adding a source is a registration HERE and nothing else. Before this
 * existed, the single source's class and prefix were hardcoded at four
 * production sites (boot seeding, the address renderer, the {@code stats} block,
 * the reseed's kept-counts), so a second source meant editing all four and
 * finding them by memory. That is the shotgun-surgery shape, and the fix is one
 * list rather than four greps.</p>
 *
 * <p><b>Boot AND reseed iterate this,</b> which is the point: a seeder wired only
 * into process start is invisible to any operation that empties what it seeds
 * into — the store's own recorded lesson, paid for once already when a rebuild
 * left the catalogue absent until the next restart.</p>
 */
public final class CatalogueSources {

    private CatalogueSources() {
    }

    /**
     * Every registered source, in seeding order.
     *
     * <p>Constructed fresh per call and CHEAP BY CONTRACT — see
     * {@link CatalogueOrigin}, which is pure data for exactly this reason.
     * Callers that only need prefixes (the address renderer, the stats block)
     * pay nothing: no manifest is opened until something asks for rows or for an
     * authority.</p>
     */
    public static List<CatalogueOrigin> all() {
        return List.of(
            // The pinned fork. A FOREIGN authority: it moves under us, so its rows
            // carry a pinned commit and their addresses must be re-resolved when the
            // pin moves. Its tree is a separate checkout, so it has no workspace root
            // — "does this address open HERE?" is not a question that can be asked of
            // it, and saying so is better than answering it wrongly.
            new CatalogueOrigin(
                "java-design-patterns", "/catalogue/patterns.json", "", List.of()),
            // Our own cure specimens. They version with the product — the detector
            // that names a cure and the code it points at ship from one commit — so
            // there is nothing to pin and nothing that can drift.
            new CatalogueOrigin(
                "jawata-samples", "/samples/samples.json", "org.jawata.samples",
                // S4 renamed this origin's spelling. The old one is retired, not
                // deleted: any install that seeded under it still holds those rows,
                // and on the rename they fall out of every prefix-keyed lane at once.
                List.of("sample:jawata-samples/")));
    }

    /**
     * The source that OWNS this row, or {@code null} when no catalogue does —
     * which is the ordinary answer for an experience or an ingested story.
     *
     * <p>The single place the ownership question is answered, so "is this a
     * public address?" and "whose namespace is this?" cannot drift apart.</p>
     */
    public static CatalogueOrigin owning(String sourceRef) {
        if (sourceRef == null) {
            return null;
        }
        for (CatalogueOrigin o : all()) {
            if (sourceRef.startsWith(o.prefix())) {
                return o;
            }
        }
        return null;
    }

    /** True when a catalogue source owns this row. */
    public static boolean isCatalogue(String sourceRef) {
        return owning(sourceRef) != null;
    }
}
