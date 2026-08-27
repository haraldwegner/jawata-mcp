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
     * <p>Constructed fresh per call and cheap by contract — see
     * {@link CatalogueSource}. Callers that only need prefixes (the address
     * renderer, the stats block) pay nothing for the seeding machinery.</p>
     */
    public static List<CatalogueSource> all() {
        return List.of(new PatternCatalogueLoader());
    }

    /**
     * The source that OWNS this row, or {@code null} when no catalogue does —
     * which is the ordinary answer for an experience or an ingested story.
     *
     * <p>The single place the ownership question is answered, so "is this a
     * public address?" and "whose namespace is this?" cannot drift apart.</p>
     */
    public static CatalogueSource owning(String sourceRef) {
        if (sourceRef == null) {
            return null;
        }
        for (CatalogueSource s : all()) {
            if (sourceRef.startsWith(s.prefix())) {
                return s;
            }
        }
        return null;
    }

    /** True when a catalogue source owns this row. */
    public static boolean isCatalogue(String sourceRef) {
        return owning(sourceRef) != null;
    }
}
