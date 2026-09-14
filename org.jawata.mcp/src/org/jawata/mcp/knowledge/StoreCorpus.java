package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The store's entries held in memory, with the word index built from them, rebuilt only
 * when the store changes.
 *
 * <p><b>Why it exists (2026-09-14).</b> Every recall read all entries with their text and
 * rebuilt the word index from scratch. A profile of recall over 10,257 entries measured
 * 1.5–1.6 s per recall — 61% of CPU reading the rows, 18% rebuilding the index — against
 * the hook's 1.2 s budget, so 1,182 recalls answered "store unavailable" and 2,892 were
 * never answered. The rows only change when something writes, so they are read then, not
 * per question.</p>
 *
 * <p><b>Freshness.</b> Each call reads {@link ExperienceStore#changeStamp()} — one cheap
 * server-side statement — and rebuilds when it differs from the snapshot's. The stamp is
 * read BEFORE the rows, so a write landing during a rebuild leaves the snapshot labelled
 * with the older stamp and the next call rebuilds again, so a snapshot is rebuilt once too
 * often rather than served stale. A store that returns no stamp is read directly every time,
 * exactly as before.</p>
 *
 * <p><b>The one stale window, stated rather than hidden.</b> Rewriting a sourced row in place
 * runs the row update, the symptom delete and the symptom inserts as separate statements. A
 * rebuild that lands between them caches the row with missing symptoms under a stamp that
 * already matches, and recall under-ranks that row until the next write moves the stamp.
 * Nothing is lost and the next write repairs it; one transaction around that rewrite closes
 * it.</p>
 *
 * <p>One snapshot per store instance, shared by every retrieval built over it.</p>
 */
public final class StoreCorpus {

    /** The entries as of one stamp: all of them, the live ones, and the word index over the live ones. */
    public record Snapshot(String stamp, List<StoredEntry> all, List<StoredEntry> live,
            LexicalIndex.Corpus words) {
    }

    private static final Map<ExperienceStore, Snapshot> SNAPSHOTS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ExperienceStore, Object> LOCKS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private StoreCorpus() {
    }

    /** The current snapshot for {@code store}, rebuilt only if the store changed since the last one. */
    public static Snapshot of(ExperienceStore store) {
        String stamp = store.changeStamp();
        if (stamp == null) {
            return build(store, null);
        }
        Snapshot current = SNAPSHOTS.get(store);
        if (current != null && stamp.equals(current.stamp())) {
            return current;
        }
        Object lock = LOCKS.computeIfAbsent(store, k -> new Object());
        synchronized (lock) {
            current = SNAPSHOTS.get(store);
            if (current != null && stamp.equals(current.stamp())) {
                return current;
            }
            Snapshot built = build(store, stamp);
            SNAPSHOTS.put(store, built);
            return built;
        }
    }

    private static Snapshot build(ExperienceStore store, String stamp) {
        List<StoredEntry> all = Collections.unmodifiableList(new ArrayList<>(store.all()));
        List<StoredEntry> live = new ArrayList<>(all.size());
        for (StoredEntry e : all) {
            if (e.isLive()) {
                live.add(e);
            }
        }
        return new Snapshot(stamp, all, Collections.unmodifiableList(live),
            LexicalIndex.index(live));
    }
}
