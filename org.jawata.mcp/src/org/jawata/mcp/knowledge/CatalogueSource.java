package org.jawata.mcp.knowledge;

/**
 * ONE SOURCE OF CATALOGUE ROWS, with its own authority and its own lifecycle.
 *
 * <p><b>Why this exists (Sprint 28d, ARCHITECTURE-28d).</b> Until now there was
 * exactly one catalogue source, so four production sites hardcoded its class or
 * its prefix: the boot seeder, the retrieval renderer deciding whether a row's
 * {@code source_ref} is a public address, the {@code stats} catalogue block, and
 * the reseed's kept-counts. A second source would have had to be added to all
 * four — shotgun surgery on a shape nobody had named. This interface names it.</p>
 *
 * <p><b>The rule the shape encodes:</b> every catalogue row belongs to exactly
 * ONE source, identified by its {@link #prefix()}, and that source owns the row's
 * lifecycle. v3.17.0 shipped the first consequence — a reseed deletes only what
 * its own reload restores, so it never destroys another source's rows. This
 * generalises it before the second source arrives rather than after.</p>
 *
 * <p><b>Authority is what makes the sources different, and it is not decoration.</b>
 * A FOREIGN source is pinned to somebody else's commit, so its content can change
 * under us and its addresses must be re-resolved when the pin moves. An OWN source
 * versions with this product, so it cannot drift from the detectors that point at
 * it. A calibration tool is NOT a source at all: it never writes a row, so it never
 * appears here.</p>
 *
 * <p>Implementations must be CHEAP TO CONSTRUCT — the registry builds them to
 * answer read-only questions like "which namespace owns this row?", and a source
 * that parsed a megabyte in its constructor would put that cost on every
 * {@code stats} call. Do the expensive work in {@link #seed}.</p>
 */
public interface CatalogueSource {

    /**
     * The namespace name, as it appears in {@code stats} and in a degradation
     * line. It answers "which catalogue is empty?" — the question a single
     * global count cannot answer once there is more than one source.
     */
    String namespace();

    /**
     * Every row this source owns starts with this {@code source_ref} prefix, and
     * no other source's rows do. This is the ownership key: the reseed, the stats
     * block and the address renderer all decide by it.
     */
    String prefix();

    /**
     * Where this source's content comes FROM, in one human-readable phrase — a
     * pinned upstream commit, this product's own version. Reported rather than
     * inferred, because "the catalogue is stale" and "the catalogue is ours and
     * current" are different facts about rows that otherwise look identical.
     */
    String authority();

    /**
     * Seed this source's rows into the store, idempotently: a row already present
     * with the same content is not rewritten. Returns how many rows were WRITTEN —
     * zero on the ordinary case where everything is already current.
     *
     * <p>Writes are confined to this source's own namespace. A source that touched
     * another's rows would break the one rule this interface exists to hold.</p>
     */
    int seed(ExperienceStore store);
}
