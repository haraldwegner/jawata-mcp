package org.jawata.mcp.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds, at resident start, what the first recall after a restart would otherwise build while the
 * caller waits (REL44-8, the cold-start case).
 *
 * <p>Three things are made on first use: the corpus snapshot ({@link StoreCorpus}, every row read once),
 * the stored vectors ({@link EmbeddingIndex}, read once) and the embedder itself (loaded on first
 * embed). Measured on a restart over a 10,189-row store the first recall took ~710 ms and, under the
 * hook's 200 ms budget, answered nothing; every later one answered in 120 to 230 ms. The hook's call is
 * the first call that arrives, so it paid for all three. Running one lookup of each kind here, off the
 * serving path, moves that cost to a moment nobody is waiting.</p>
 *
 * <p>Never fatal and never silent: a store that cannot be read is reported in the result and the log,
 * and recall then builds on demand exactly as it did before this class existed.</p>
 */
public final class RecallWarmUp {

    private static final Logger log = LoggerFactory.getLogger(RecallWarmUp.class);

    /** A cue with no meaning of its own: it exists to be embedded and to touch every vector once. */
    private static final String WARM_CUE = "warm up";

    private RecallWarmUp() {
    }

    /**
     * What a warm-up made ready.
     *
     * @param rows    rows held in the corpus snapshot afterwards; 0 when the store could not be read
     * @param meaning whether the embedder and vectors were warmed, false when there is no embedder
     */
    public record Warmed(int rows, boolean meaning) {
    }

    /** Warms the corpus snapshot, then the embedder and vectors, for {@code store}; returns what it made ready. */
    public static Warmed run(ExperienceStore store) {
        long start = System.currentTimeMillis();
        int rows = 0;
        try {
            rows = StoreCorpus.of(store).all().size();
        } catch (RuntimeException e) {
            log.warn("Recall warm-up could not read the store ({}); the first recall builds the corpus"
                + " itself", e.toString());
        }
        boolean meaning = false;
        try {
            EmbeddingIndex index = EmbeddingIndex.forStore(store);
            if (index != null && index.available()) {
                index.nearestEntries(WARM_CUE, 1, 1.0);
                meaning = true;
            }
        } catch (RuntimeException e) {
            log.warn("Recall warm-up could not warm meaning-based recall ({}); the first meaning lookup"
                + " loads it itself", e.toString());
        }
        log.info("Recall warm-up: {} row(s) in the corpus, meaning {} — {} ms", rows,
            meaning ? "warm" : "not warmed", System.currentTimeMillis() - start);
        return new Warmed(rows, meaning);
    }
}
