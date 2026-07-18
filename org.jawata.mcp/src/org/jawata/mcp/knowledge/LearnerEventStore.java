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

    /** Appends one event. Never throws — failure is logged + counted (loud, visible). */
    public void append(LearnerEvent event) {
        synchronized (store) {
            try {
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
            } catch (Exception e) {
                failedWrites.incrementAndGet();
                log.error("LEARNER EVENT DROPPED ({} total) — kind={} tool={}: the label"
                    + " stream is incomplete until this is fixed",
                    failedWrites.get(), event.kind(), event.tool(), e);
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

    /** Persists a learner's state. Loud on failure, like {@link #append}. */
    public void saveState(String learner, String stateJson) {
        synchronized (store) {
            try {
                Connection conn = store.sharedConnection();
                try (PreparedStatement ps = conn.prepareStatement(
                    "MERGE INTO learner_state (learner, state_json, updated)"
                        + " KEY (learner) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
                    ps.setString(1, learner);
                    ps.setString(2, stateJson);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                failedWrites.incrementAndGet();
                log.error("LEARNER STATE WRITE FAILED ({} total) for {}",
                    failedWrites.get(), learner, e);
            }
        }
    }

    /** Dropped-write count — surfaced by the learner status report, never hidden. */
    public long failedWrites() {
        return failedWrites.get();
    }
}
