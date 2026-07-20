package org.jawata.mcp.knowledge;

import org.jawata.mcp.learn.LearnerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistence for the injector's learning layer (Sprint 26, schema v5):
 * {@code learner_event} — the continuous label stream — and
 * {@code learner_state} — each learner's serialized model + rolling record.
 * Rides the SAME H2 file and provenance as the experience store; every access
 * synchronizes on the owning {@link H2ExperienceStore} instance (the store's
 * own locking discipline), so the two writers never interleave.
 *
 * <p>A failed write is LOUD, never silent: logged as an error AND counted in
 * {@link #failedWrites()}, which the learner status report surfaces — an event
 * stream that quietly drops events would train a lying model.</p>
 */
public final class LearnerEventStore {

    private static final Logger log = LoggerFactory.getLogger(LearnerEventStore.class);

    private final H2ExperienceStore store;
    private final AtomicLong failedWrites = new AtomicLong();

    /** Provenance is read from the store AT WRITE TIME — one source of truth,
     * correct even when provenance is set after construction. */
    public LearnerEventStore(H2ExperienceStore store) {
        this.store = store;
    }

    /**
     * Write attempts per event before a drop is declared. F1 (Sprint-26 audit):
     * widened 3 → 8 with exponential-capped backoff (see {@link #withRetry}) so the
     * total retry window (~4.5s) OUTLASTS a peer resident's restart — the shared
     * auto-server socket is unavailable for the seconds a peer JVM takes to come
     * back, and the old ~0.3s budget gave up inside that window.
     */
    static final int WRITE_ATTEMPTS = 8;

    /** A retryable SQL operation. */
    interface SqlOp {
        void run() throws Exception;
    }

    /**
     * v3.2.1 (dogfood #3): run {@code op}, retrying up to {@code attempts}
     * times; {@code onRetry} runs between attempts (the caller invalidates the
     * suspect connection there, so the retry acquires a FRESH one). The live
     * fleet was dropping ~40% of events on single-attempt writes — a statement
     * can die mid-write on a connection that acquisition-time validation still
     * blessed (auto-server handoff, lock timeout against a peer resident).
     */
    static void withRetry(int attempts, SqlOp op, Runnable onRetry) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                op.run();
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < attempts) {
                    log.info("learner-store write failed (attempt {}/{}), retrying on a"
                        + " fresh connection: {}", attempt, attempts, e.getMessage());
                    onRetry.run();
                    try {
                        // Exponential backoff capped at 1s: 100,200,400,800,1000,1000,1000
                        // ≈ 4.5s total over 8 attempts — long enough to outlast a peer
                        // resident's restart, bounded so the request thread never hangs.
                        Thread.sleep(Math.min(100L * (1L << (attempt - 1)), 1000L));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    /** Appends one event. Never throws — a failure surviving all retries is
     *  logged + counted (loud, visible). */
    public void append(LearnerEvent event) {
        synchronized (store) {
            try {
                withRetry(WRITE_ATTEMPTS, () -> {
                    Connection conn = store.sharedConnection();
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO learner_event (session_id, kind, tool, detail_json,"
                            + " workspace_id, project_id) VALUES (?, ?, ?, ?, ?, ?)")) {
                        ps.setString(1, event.sessionId());
                        ps.setString(2, event.kind());
                        ps.setString(3, event.tool());
                        ps.setString(4, event.detailJson());
                        ps.setString(5, store.provenanceWorkspaceId());
                        ps.setString(6, store.provenanceProjectId());
                        ps.executeUpdate();
                    }
                }, store::invalidateSharedConnection);
            } catch (Exception e) {
                failedWrites.incrementAndGet();
                log.error("LEARNER EVENT DROPPED after {} attempts ({} total) — kind={}"
                    + " tool={}: the label stream is incomplete until this is fixed",
                    WRITE_ATTEMPTS, failedWrites.get(), event.kind(), event.tool(), e);
            }
        }
    }

    /** Event count per kind — the /train status report's headline numbers. */
    public Map<String, Long> countByKind() {
        synchronized (store) {
            Map<String, Long> counts = new LinkedHashMap<>();
            try {
                Connection conn = store.sharedConnection();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT kind, COUNT(*) FROM learner_event GROUP BY kind ORDER BY kind");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        counts.put(rs.getString(1), rs.getLong(2));
                    }
                }
            } catch (Exception e) {
                log.error("learner_event count query failed", e);
            }
            return counts;
        }
    }

    public long totalEvents() {
        return countByKind().values().stream().mapToLong(Long::longValue).sum();
    }

    /** A learner's persisted state (model + rolling record), if any. */
    public Optional<String> loadState(String learner) {
        synchronized (store) {
            try {
                Connection conn = store.sharedConnection();
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT state_json FROM learner_state WHERE learner = ?")) {
                    ps.setString(1, learner);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
                    }
                }
            } catch (Exception e) {
                log.error("learner_state load failed for {}", learner, e);
                return Optional.empty();
            }
        }
    }

    /** Persists a learner's state. Retries + loud on final failure, like {@link #append}. */
    public void saveState(String learner, String stateJson) {
        synchronized (store) {
            try {
                withRetry(WRITE_ATTEMPTS, () -> {
                    Connection conn = store.sharedConnection();
                    try (PreparedStatement ps = conn.prepareStatement(
                        "MERGE INTO learner_state (learner, state_json, updated)"
                            + " KEY (learner) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
                        ps.setString(1, learner);
                        ps.setString(2, stateJson);
                        ps.executeUpdate();
                    }
                }, store::invalidateSharedConnection);
            } catch (Exception e) {
                failedWrites.incrementAndGet();
                log.error("LEARNER STATE WRITE FAILED after {} attempts ({} total) for {}",
                    WRITE_ATTEMPTS, failedWrites.get(), learner, e);
            }
        }
    }

    /** Dropped-write count — surfaced by the learner status report, never hidden. */
    public long failedWrites() {
        return failedWrites.get();
    }
}
