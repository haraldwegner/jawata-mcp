package org.jawata.mcp.knowledge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.jawata.mcp.embed.EmbedderIdentity;
import org.jawata.mcp.embed.MiniLmEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 27 D2 — the store's one door to the embedder.
 *
 * <p>The model is loaded LAZILY and exactly once per process: it is ~45 MB of
 * weights, and a resident that never embeds anything should never pay for it.
 * Every store instance shares the one loaded model.</p>
 *
 * <p><b>Absence is a first-class state.</b> {@link #available()} can be false —
 * because loading failed, or because the operator disabled it — and every caller
 * must handle that by falling back to keyword behaviour. It is the degrade path
 * the sprint promises: a broken embedder must leave the product working exactly
 * as it did before semantic recall existed, never half-working and never
 * silently returning empty results that read like "nothing matched".</p>
 */
public final class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** Escape hatch: disables semantic recall process-wide (degrade tests, incidents). */
    public static final String DISABLE_PROPERTY = "jawata.embed.disabled";

    private static volatile EmbeddingService instance;

    private final MiniLmEmbedder embedder;      // null when unavailable
    private final String identityKey;
    private final String unavailableReason;

    private EmbeddingService(MiniLmEmbedder embedder, String unavailableReason) {
        this.embedder = embedder;
        this.unavailableReason = unavailableReason;
        this.identityKey = EmbedderIdentity.current().key();
    }

    /** The process-wide instance, loading the model on first use. */
    public static EmbeddingService shared() {
        EmbeddingService local = instance;
        if (local != null) {
            return local;
        }
        synchronized (EmbeddingService.class) {
            if (instance == null) {
                instance = create();
            }
            return instance;
        }
    }

    private static EmbeddingService create() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            log.warn("semantic embedding DISABLED via -D{}; recall falls back to keyword only",
                DISABLE_PROPERTY);
            return new EmbeddingService(null, "disabled via " + DISABLE_PROPERTY);
        }
        try {
            long t0 = System.currentTimeMillis();
            MiniLmEmbedder e = MiniLmEmbedder.bundled();
            log.info("embedder ready: {} {} backend={} in {} ms",
                EmbedderIdentity.current().key(), e.precision(), e.backend(),
                System.currentTimeMillis() - t0);
            return new EmbeddingService(e, null);
        } catch (RuntimeException | LinkageError t) {
            // LOUD, and in the response path too (the store reports
            // embeddingAvailable) - a silent fallback would look like
            // "semantic recall found nothing" forever.
            log.error("embedder FAILED to load; semantic recall is OFF and retrieval"
                + " falls back to keyword only", t);
            return new EmbeddingService(null, t.toString());
        }
    }

    /** Test seam: drop the cached instance so the next call re-evaluates. */
    static void resetForTests() {
        synchronized (EmbeddingService.class) {
            instance = null;
        }
    }

    public boolean available() {
        return embedder != null;
    }

    /** Why it is unavailable, or {@code null} when it is available. */
    public String unavailableReason() {
        return unavailableReason;
    }

    /** The identity of vectors this service produces. */
    public String identityKey() {
        return identityKey;
    }

    /**
     * Embed {@code text}, or {@code null} when unavailable or the text is blank.
     *
     * <p>A per-call failure is logged and returns null rather than propagating:
     * the caller's job (storing a row) must still succeed. Losing the vector
     * costs semantic reach for that row; losing the row would cost knowledge.</p>
     */
    public float[] embed(String text) {
        if (embedder == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            return embedder.embed(text);
        } catch (RuntimeException e) {
            log.error("embedding failed for a {}-char text; the row is stored WITHOUT a"
                + " vector and stays keyword-reachable", text.length(), e);
            return null;
        }
    }

    /**
     * THE rule for what text a row's vector is computed from: summary, then
     * details.
     *
     * <p>It lives in one place because every path that produces a vector must
     * use it — write, re-embed and backfill alike. If they disagreed, the same
     * entry would get different vectors depending on which path touched it last,
     * and a cue could match or miss it depending on nothing the user did.</p>
     *
     * <p>C0 measured why details are included: embedding bare summaries scored a
     * true paraphrase pair at 0.258 — indistinguishable from noise — while the
     * same relationships on summary + details land 0.39–0.57.</p>
     */
    public static String textOf(String summary, String details) {
        String s = summary == null ? "" : summary.trim();
        String d = details == null ? "" : details.trim();
        if (s.isEmpty()) {
            return d;
        }
        if (d.isEmpty()) {
            return s;
        }
        return s + " " + d;
    }

    /** Little-endian float32 blob — the on-disk vector form. */
    public static byte[] toBytes(float[] v) {
        if (v == null) {
            return null;
        }
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            b.putFloat(f);
        }
        return b.array();
    }

    /** Inverse of {@link #toBytes}; {@code null} for null/ragged input. */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % 4 != 0) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[bytes.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = b.getFloat();
        }
        return v;
    }

    /**
     * Cosine similarity. Vectors from this embedder are already L2-normalized,
     * so this is a dot product; the norms are still divided out so a
     * denormalized input cannot silently inflate a score.
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
