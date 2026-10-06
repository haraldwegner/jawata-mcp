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
 *   <li><b>Say so</b> — {@link #degradedNotice()} is non-null the whole time the
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

    /** Sprint 27 D5: the current delegate — lets a caller that needs the
     *  CONCRETE store (the embedding index reads its connection) reach it
     *  through the wrapper instead of an {@code instanceof} check that is
     *  false in production (the C4-F1 lesson, kept fixed). */
    public ExperienceStore currentDelegate() {
        return delegate;
    }
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

    /**
     * Non-null while degraded — the text every answer carries. Null once recovered.
     *
     * <p>AND IT FORWARDS. This was the one method of twenty here that answered for
     * itself instead of asking the delegate, which meant that once recovery cleared
     * {@code notice}, this wrapper would report "not degraded" for the wrapped store
     * whatever the wrapped store had to say. Inert while the H2 store had nothing to
     * say — and it would have gone live silently the moment it did.</p>
     */
    @Override
    public String degradedNotice() {
        return notice != null ? notice : delegate.degradedNotice();
    }

    /**
     * Sprint 28g — AND THESE TWO FORWARD, for the reason the method above records.
     *
     * <p>Both arrived as interface DEFAULTS, so this wrapper declared neither. <b>Only
     * ONE of them was actually broken by that, and the C13 audit is what corrected the
     * claim.</b> {@code appliedSampleCount}'s default was a hard {@code return 0}, so
     * through this wrapper every pattern had zero samples whatever the delegate held —
     * exactly the shape the javadoc above calls out. {@code headingShapedSummaries}'
     * default looped over an UNQUALIFIED {@code all()}, which dispatches to this
     * class's own override and so reached the delegate's real rows: it answered
     * correctly, and the sentence that used to stand here saying otherwise was
     * wrong.</p>
     *
     * <p>They are abstract on the interface now, which is what made the compiler ask
     * this class for them rather than answering quietly on its behalf — and for the
     * second one that is a latent hazard closed rather than a defect fixed: its default
     * was right only because every implementor happens to override {@code all()}.</p>
     */
    @Override
    public int appliedSampleCount(String patternTarget) {
        return delegate.appliedSampleCount(patternTarget);
    }

    @Override
    public java.util.List<java.util.Map<String, Object>> headingShapedSummaries() {
        return delegate.headingShapedSummaries();
    }

    /**
     * D40c's three, forwarded for the same reason — the architect's C13 F1 found them
     * still answering from their defaults after the two above had been fixed.
     *
     * <p><b>One caveat, stated rather than discovered later:</b> while this wrapper is
     * DEGRADED the delegate is the in-memory fallback, so a folder recorded during that
     * window is recorded there. Recovery replays the entries written while degraded; it
     * does not replay this. In practice the loader runs on every boot and records again,
     * which is the same reason the folder is held in a field rather than a row.</p>
     */
    @Override
    public void recordStoryFolder(java.nio.file.Path folder) {
        delegate.recordStoryFolder(folder);
    }

    @Override
    public java.util.Optional<java.nio.file.Path> storyFolder() {
        return delegate.storyFolder();
    }

    @Override
    public boolean storyFolderAmbiguous() {
        return delegate.storyFolderAmbiguous();
    }

    /**
     * Sprint 28g S14 D30 — forwarded, and declared here because the interface gives it no
     * default to answer with.
     *
     * <p>That is the S13 lesson applied rather than re-learned: three members shipped as
     * defaults in that stage and this class, which production serves through, declared
     * none of them — so the default spoke in its place and the honest answer never
     * reached a caller. For a WRITE the same hole is worse than for a read: a default
     * that quietly declined to replace would look exactly like a member nobody had
     * described, which is the very duplicate this deliverable exists to stop.</p>
     */
    @Override
    public String replaceJob(String existingId, ExperienceEntry entry) {
        return delegate.replaceJob(existingId, entry);
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

    /**
     * Sprint 28f D1 — forwarded, and the forwarding is the whole point.
     *
     * <p>The interface gives this a DEFAULT that falls back to an insert, so this
     * class compiles perfectly without the method. That is exactly the hole
     * {@code H2ExperienceStore#degradedNotice}'s javadoc records — <i>"invisible for
     * exactly as long as a default answered for it"</i> — and here it would be worse
     * than invisible: every load through the wrapper, which is every load in
     * production, would insert instead of updating in place, and the durability this
     * method exists for would be absent while the store reported success.</p>
     */
    @Override
    public String upsertBySource(ExperienceEntry entry, String sourceRef, String sourceHash) {
        synchronized (lock) {
            return delegate.upsertBySource(entry, sourceRef, sourceHash);
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

    /**
     * Sprint 28f — forwarded, and this one the COMPILER made sure of.
     *
     * <p>Its sibling {@link #upsertBySource} above has to argue for its own existence,
     * because the interface gives that one a default and this class would have compiled
     * without it. This method is declared with no default for exactly that reason: the
     * forwarding is not something a future implementor has to remember.</p>
     */
    @Override
    public int retainSourcedRows(String sourceRef, java.util.Set<String> keepIds) {
        synchronized (lock) {
            return delegate.retainSourcedRows(sourceRef, keepIds);
        }
    }

    @Override
    public int deleteByIds(java.util.List<String> ids) {
        synchronized (lock) {
            return delegate.deleteByIds(ids);
        }
    }

    @Override
    public long wipe() {
        synchronized (lock) {
            return delegate.wipe();
        }
    }

    @Override
    public void tombstone(String sourceRef, String reason) {
        synchronized (lock) {
            delegate.tombstone(sourceRef, reason);
        }
    }

    @Override
    public void clearTombstones() {
        synchronized (lock) {
            delegate.clearTombstones();
        }
    }

    @Override
    public java.util.Set<String> tombstonedRefs() {
        return delegate.tombstonedRefs();
    }

    @Override
    public java.util.Set<String> fileSourceRefs() {
        return delegate.fileSourceRefs();
    }

    @Override
    public List<StoredEntry> all() {
        return delegate.all();
    }

    /**
     * Forwarded under the write lock. The interface default answers {@code false}, so a
     * wrapper that forgot this would silently leave every exported story untied to its row,
     * and the next load of the story folder would add each accepted entry a second time.
     */
    @Override
    public boolean attachSource(String id, String sourceRef, String sourceHash) {
        synchronized (lock) {
            return delegate.attachSource(id, sourceRef, sourceHash);
        }
    }

    /**
     * The delegate's stamp, qualified by WHICH delegate answered — a recovery swaps the
     * store underneath, and two different stores could otherwise report the same stamp
     * and hand a caller the corpus of the one that is gone.
     */
    @Override
    public String changeStamp() {
        ExperienceStore current = delegate;
        String inner = current.changeStamp();
        return inner == null ? null : System.identityHashCode(current) + "#" + inner;
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
    public boolean setRuleVersion(String id, int version) {
        synchronized (lock) {
            return delegate.setRuleVersion(id, version);
        }
    }

    @Override
    public boolean retire(String id) {
        synchronized (lock) {
            return delegate.retire(id);
        }
    }

    @Override
    public boolean updateSymbolAnchor(String id, String symbolFqn) {
        synchronized (lock) {
            return delegate.updateSymbolAnchor(id, symbolFqn);
        }
    }

    /**
     * Sprint 28f Stage 7. Forwarded like every other write, and it matters more than most:
     * the interface gives this a no-op default, so a wrapper that forgot to forward would
     * answer "nothing moved" — which is also the honest answer when a member really has no
     * job — and renames would quietly stop following with nothing anywhere to say so.
     */
    @Override
    public int moveSymbolAnchor(String fromFqn, String toFqn) {
        synchronized (lock) {
            return delegate.moveSymbolAnchor(fromFqn, toFqn);
        }
    }

    @Override
    public boolean markEvidenceDead(String id) {
        synchronized (lock) {
            return delegate.markEvidenceDead(id);
        }
    }

    @Override
    public boolean setForm(String id, String situation, String verdict) {
        synchronized (lock) {
            return delegate.setForm(id, situation, verdict);
        }
    }

    @Override
    public boolean rewriteForm(String id, String situation, String verdict) {
        synchronized (lock) {
            return delegate.rewriteForm(id, situation, verdict);
        }
    }

    @Override
    public boolean setOriginClient(String id, String client) {
        synchronized (lock) {
            return delegate.setOriginClient(id, client);
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
