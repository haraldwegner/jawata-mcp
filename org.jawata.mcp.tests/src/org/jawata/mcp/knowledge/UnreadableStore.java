package org.jawata.mcp.knowledge;

/**
 * #37 fault injection: a store that is OPEN and cannot be read.
 *
 * <p>The obvious injection — open an in-memory store and close it — does not produce
 * this state: {@code H2ExperienceStore.live()} re-opens a closed connection, and for an
 * in-memory URL that yields a fresh EMPTY database, so the store answers a perfectly
 * successful absence. (Benign for a file store, which re-opens the same file.) A test
 * built on it would prove nothing while looking green.</p>
 *
 * <p>So the failure is injected where it actually happened: every read throws the
 * {@code IllegalStateException} that {@link H2ExperienceStore} wraps a {@code
 * SQLException} in. Writes throw too — a store that cannot be read cannot be written.</p>
 */
final class UnreadableStore implements ExperienceStore {

    static final String BOOM = "failed to query entries: connection is broken";

    private final long stallMillis;

    /** Fails instantly — the broken-connection case. */
    UnreadableStore() {
        this(0);
    }

    /**
     * Fails only after {@code stallMillis} — the case the incident actually was: the
     * read does not fail, it simply never comes back. Pass something far longer than
     * the budget under test; the point is that the caller returns first.
     */
    UnreadableStore(long stallMillis) {
        this.stallMillis = stallMillis;
    }

    private IllegalStateException boom() {
        if (stallMillis > 0) {
            try {
                Thread.sleep(stallMillis);
            } catch (InterruptedException e) {
                // The bulkhead cancelled us. A real socket read would NOT unblock here —
                // that is the honest limit written up on ExperienceRetrieval.within.
                Thread.currentThread().interrupt();
            }
        }
        return new IllegalStateException(BOOM);
    }

    @Override
    public java.util.List<StoredEntry> query(RecallQuery query) {
        throw boom();
    }

    @Override
    public java.util.List<StoredEntry> all() {
        throw boom();
    }

    @Override
    public String put(SymbolFact fact) {
        throw boom();
    }

    @Override
    public String put(ExperienceEntry entry) {
        throw boom();
    }

    @Override
    public String putWithSource(ExperienceEntry entry, String sourceRef) {
        throw boom();
    }

    @Override
    public int deleteBySource(String sourceRef) {
        throw boom();
    }

    @Override
    public long wipe() {
        throw boom();
    }

    @Override
    public java.util.Optional<java.util.Map<String, Object>> get(String id) {
        throw boom();
    }

    @Override
    public java.util.List<StoredEntry> byIds(java.util.List<String> ids) {
        throw boom();
    }

    @Override
    public boolean setStatus(String id, String status) {
        throw boom();
    }

    @Override
    public long count() {
        throw boom();
    }

    @Override
    public java.util.List<java.util.Map<String, Object>> exportEntries(String status, String type) {
        throw boom();
    }

    @Override
    public java.util.Map<String, Object> importEntries(
            java.util.List<java.util.Map<String, Object>> entries) {
        throw boom();
    }

    @Override
    public java.util.List<StoredEntry> listEntries(String type, String status, String scope,
            String language, int limit) {
        throw boom();
    }

    @Override
    public int pruneAged(int days) {
        throw boom();
    }

    @Override
    public java.util.Map<String, Object> compact() {
        throw boom();
    }

    @Override
    public java.util.Map<String, Object> stats() {
        throw boom();
    }

    /** Closing a broken store is the one thing that must still work. */
    @Override
    public void close() {
        // nothing to release
    }
}
