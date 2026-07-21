package org.jawata.mcp.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * v3.2.1 (Sprint 26 dogfood finding #1 — the silent sticky degrade): wraps
 * the in-memory fallback a failed store-open used to hand back bare. Three
 * duties, none optional:
 *
 * <ol>
 *   <li><b>Serve</b> — the fallback works exactly like before (an in-memory
 *       delegate), so a broken store never kills the resident.</li>
 *   <li><b>Say so</b> — {@link #notice()} is non-null the whole time the
 *       store is degraded; the registry appends it to EVERY answer. A
 *       degraded result presented as normal is this codebase's recorded
 *       top-bug class — this class exists so it cannot happen here again.</li>
 *   <li><b>Heal</b> — a background daemon retries the real open on a fixed
 *       interval. On success the degraded window's entries are REPLAYED into
 *       the real store, the delegate is swapped, and the notice clears. A
 *       permanent failure (e.g. schema written by a newer resident) simply
 *       keeps the notice up forever — loud beats stuck.</li>
 * </ol>
 */
public final class RecoveringExperienceStore implements ExperienceStore {

    private static final Logger log = LoggerFactory.getLogger(RecoveringExperienceStore.class);

    private final Object lock = new Object();
    private volatile ExperienceStore delegate;
    private volatile String notice;
    private final String reason;
    private final Thread retryThread;
    private volatile boolean closed;
    private volatile String provWorkspace;
    private volatile String provProject;

    /**
     * @param reason why the real open failed (rides the notice + stats)
     * @param reopener attempts the real open; throws while it still cannot
     * @param retryMs pause between background attempts
     */
    public RecoveringExperienceStore(String reason, Supplier<H2ExperienceStore> reopener,
            long retryMs) {
        this.reason = reason;
        this.delegate = H2ExperienceStore.openMemory();
        this.notice = "EXPERIENCE STORE DEGRADED — serving a NON-PERSISTENT in-memory "
            + "fallback (reason: " + reason + "). Memory answers are incomplete; recovery "
            + "is retried in the background and replays anything recorded meanwhile. The "
            + "learning layer stays offline until the next restart. If this notice "
            + "persists, restart the resident.";
        this.retryThread = new Thread(() -> retryLoop(reopener, retryMs),
            "jawata-store-recovery");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    /** Non-null while degraded — the text every answer carries. Null once recovered. */
    public String notice() {
        return notice;
    }

    private void retryLoop(Supplier<H2ExperienceStore> reopener, long retryMs) {
        while (!closed && notice != null) {
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed) {
                return;
            }
            try {
                H2ExperienceStore real = reopener.get();
                synchronized (lock) {
                    ExperienceStore memory = delegate;
                    if (provWorkspace != null || provProject != null) {
                        real.setProvenance(provWorkspace, provProject);
                    }
                    List<Map<String, Object>> window = memory.exportEntries(null, null);
                    Map<String, Object> replay = window.isEmpty()
                        ? Map.of("imported", 0) : real.importEntries(window);
                    delegate = real;
                    notice = null;
                    memory.close();
                    log.warn("Experience store RECOVERED after degraded start ({}); "
                        + "replayed degraded-window entries: {}", reason, replay);
                }
                return;
            } catch (Exception e) {
                log.info("Experience store still unavailable ({}); retrying in {}ms",
                    e.getMessage(), retryMs);
            }
        }
    }

    // ---- delegation (writes synchronized against the recovery swap) ----

    @Override
    public String put(SymbolFact fact) {
        synchronized (lock) {
            return delegate.put(fact);
        }
    }

    @Override
    public String put(ExperienceEntry entry) {
        synchronized (lock) {
            return delegate.put(entry);
        }
    }

    @Override
    public String putWithSource(ExperienceEntry entry, String sourceRef) {
        synchronized (lock) {
            return delegate.putWithSource(entry, sourceRef);
        }
    }

    @Override
    public String putWithSource(ExperienceEntry entry, String sourceRef, String sourceHash) {
        synchronized (lock) {
            return delegate.putWithSource(entry, sourceRef, sourceHash);
        }
    }

    @Override
    public boolean sourceUnchanged(String sourceRef, String sourceHash) {
        return delegate.sourceUnchanged(sourceRef, sourceHash);
    }

    @Override
    public int deleteBySource(String sourceRef) {
        synchronized (lock) {
            return delegate.deleteBySource(sourceRef);
        }
    }

    @Override
    public long wipe() {
        synchronized (lock) {
            return delegate.wipe();
        }
    }

    @Override
    public List<StoredEntry> all() {
        return delegate.all();
    }

    @Override
    public Optional<Map<String, Object>> get(String id) {
        return delegate.get(id);
    }

    @Override
    public List<StoredEntry> query(RecallQuery query) {
        return delegate.query(query);
    }

    @Override
    public List<StoredEntry> byIds(List<String> ids) {
        return delegate.byIds(ids);
    }

    @Override
    public boolean setStatus(String id, String status) {
        synchronized (lock) {
            return delegate.setStatus(id, status);
        }
    }

    @Override
    public boolean updateSymbolAnchor(String id, String symbolFqn) {
        synchronized (lock) {
            return delegate.updateSymbolAnchor(id, symbolFqn);
        }
    }

    @Override
    public long count() {
        return delegate.count();
    }

    @Override
    public List<Map<String, Object>> exportEntries(String status, String type) {
        return delegate.exportEntries(status, type);
    }

    @Override
    public Map<String, Object> importEntries(List<Map<String, Object>> entries) {
        synchronized (lock) {
            return delegate.importEntries(entries);
        }
    }

    @Override
    public List<StoredEntry> listEntries(String type, String status, String scope,
            String language, int limit) {
        return delegate.listEntries(type, status, scope, language, limit);
    }

    @Override
    public int pruneAged(int days) {
        synchronized (lock) {
            return delegate.pruneAged(days);
        }
    }

    @Override
    public Map<String, Object> compact() {
        synchronized (lock) {
            return delegate.compact();
        }
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> s = new java.util.LinkedHashMap<>(delegate.stats());
        if (notice != null) {
            // A bare "in-memory" reads like a configuration; this is a failure.
            s.put("file", "in-memory (DEGRADED: " + reason + ")");
            s.put("degraded", true);
            s.put("degradeReason", reason);
        }
        return s;
    }

    @Override
    public void setProvenance(String workspaceId, String projectId) {
        this.provWorkspace = workspaceId;
        this.provProject = projectId;
        delegate.setProvenance(workspaceId, projectId);
    }

    @Override
    public String provenanceWorkspaceId() {
        return delegate.provenanceWorkspaceId();
    }

    @Override
    public void close() {
        closed = true;
        retryThread.interrupt();
        synchronized (lock) {
            delegate.close();
        }
    }
}
