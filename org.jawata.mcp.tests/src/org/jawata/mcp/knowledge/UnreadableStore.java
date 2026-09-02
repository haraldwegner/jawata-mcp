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
    private final boolean succeedAfterStall;

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
        this(stallMillis, false);
    }

    /**
     * With {@code succeedAfterStall}: the read stalls past the budget and then
     * SUCCEEDS with an empty result — the straggler case. A fixture that always
     * throws cannot exercise the race where a late normal completion overwrites the
     * caller's unavailable verdict, because a throwing task never writes a verdict at
     * all; the first race test was green against broken code for exactly that reason.
     */
    UnreadableStore(long stallMillis, boolean succeedAfterStall) {
        this.stallMillis = stallMillis;
        this.succeedAfterStall = succeedAfterStall;
    }

    private void stall() {
        if (stallMillis > 0) {
            try {
                Thread.sleep(stallMillis);
            } catch (InterruptedException e) {
                // The bulkhead cancelled us. A real socket read would NOT unblock here —
                // that is the honest limit written up on ExperienceRetrieval.within.
                Thread.currentThread().interrupt();
            }
        }
    }

    private IllegalStateException boom() {
        stall();
        return new IllegalStateException(BOOM);
    }

    @Override
    public java.util.List<StoredEntry> query(RecallQuery query) {
        if (succeedAfterStall) {
            stall();
            return java.util.List.of();   // the straggler: late, and NORMAL
        }
        throw boom();
    }

    @Override
    public java.util.List<StoredEntry> all() {
        if (succeedAfterStall) {
            stall();
            return java.util.List.of();   // consistent with query(): slow, then normal
        }
        throw boom();
    }

    @Override
    public java.util.List<StoredEntry> byIds(java.util.List<String> ids) {
        if (succeedAfterStall) {
            stall();
            return java.util.List.of();
        }
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
    public int deleteByIds(java.util.List<String> ids) {
        throw boom();
    }

    @Override
    public long wipe() {
        throw boom();
    }

    @Override
    public void tombstone(String sourceRef, String reason) {
        throw boom();
    }

    @Override
    public void clearTombstones() {
        throw boom();
    }

    @Override
    public java.util.Set<String> tombstonedRefs() {
        throw boom();
    }

    @Override
    public java.util.Set<String> fileSourceRefs() {
        throw boom();
    }

    @Override
    public java.util.Optional<java.util.Map<String, Object>> get(String id) {
        throw boom();
    }

    @Override
    public boolean setStatus(String id, String status) {
        throw boom();
    }

    @Override
    public boolean markEvidenceDead(String id) {
        throw boom();
    }

    @Override
    public boolean setForm(String id, String situation, String verdict) {
        throw boom();
    }

    @Override
    public boolean rewriteForm(String id, String situation, String verdict) {
        throw boom();
    }

    @Override
    public boolean setOriginClient(String id, String client) {
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

    /**
     * NOT degraded — BROKEN, and those are different states worth keeping apart.
     *
     * <p>"Degraded" means the store is serving a fallback: it answers, and its answers
     * are incomplete. This store does not answer at all. Reporting a notice here would
     * route these tests through the degraded branch and leave the throwing branch — the
     * one the measured incident actually took — unexercised.</p>
     */
    @Override
    public String degradedNotice() {
        return null;
    }

    /** Closing a broken store is the one thing that must still work. */
    @Override
    public void close() {
        // nothing to release
    }
}
