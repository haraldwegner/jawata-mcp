package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
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

    /**
     * One lane's live rows with the word index built over THOSE rows alone (Sprint 28g S22).
     *
     * <p>Word rarity is a property of the corpus it is counted over. Counted over the whole store,
     * which is 96% code descriptions, a word that is common in code and rare in lessons is judged
     * non-discriminating and a lesson sharing only that word scores nothing; counted per lane, the
     * lessons are weighed against the lessons.</p>
     */
    public record LaneCorpus(KnowledgeLane lane, List<StoredEntry> live, LexicalIndex.Corpus words) {

        /**
         * The live rows that answer for the workspace {@code workspaceId} — D8d, moved here from the
         * retrieval so that the lane owns the rule about its own rows.
         *
         * <p>THE CODE LANE IS KEYED BY WORKSPACE. The store is shared by every workspace on the
         * machine and each code row is stamped with the one that wrote it; a store WITH a workspace
         * identity answers only for rows stamped with it, and a row stamped with none counts as
         * foreign, because nothing says it belongs here. A store WITHOUT an identity (tests, a
         * standalone resident) sees everything. Every other lane is knowledge ABOUT work and travels
         * between workspaces by design, so it is returned whole.</p>
         *
         * <p>Only the rows are filtered. The word index in {@link #words()} is still built over the
         * whole lane, so word rarity is unchanged by this move.</p>
         *
         * @param workspaceId the identity of this store's workspace, or null/blank when it has none
         */
        public List<StoredEntry> liveFor(String workspaceId) {
            if (!lane.filtersFor(workspaceId)) {
                return live;
            }
            List<StoredEntry> mine = new ArrayList<>(live.size());
            for (StoredEntry e : live) {
                if (lane.answersFor(workspaceId, e.workspaceId())) {
                    mine.add(e);
                }
            }
            return mine;
        }
    }

    /** The entries as of one stamp: all of them, the live ones, and one corpus per lane. */
    public record Snapshot(String stamp, List<StoredEntry> all, List<StoredEntry> live,
            Map<KnowledgeLane, LaneCorpus> lanes, Map<String, String> marks) {

        /** The corpus of one lane; an empty one when the lane holds nothing. */
        public LaneCorpus lane(KnowledgeLane lane) {
            return lanes.get(lane);
        }

        public Snapshot(String stamp, List<StoredEntry> all, List<StoredEntry> live, Map<KnowledgeLane,LaneCorpus> lanes) {
            this(stamp, all, live, lanes, null);
        }
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
            return build(store, null, null);
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
            Snapshot built = build(store, stamp, current);
            SNAPSHOTS.put(store, built);
            return built;
        }
    }

    /** The setting behind {@link #maxIncremental()}; unset means 200. */
    static final String MAX_INCREMENTAL_PROPERTY = "jawata.corpus.max.incremental";

    /**
     * Above this many changed rows a refresh reads the whole store instead: one read of everything is
     * cheaper than that many lookups, and a bulk load is exactly when the saving stops mattering.
     * A setting, read each time, so a test can exercise the bulk case without writing 200 rows.
     */
    static int maxIncremental() {
        return Integer.getInteger(MAX_INCREMENTAL_PROPERTY, 200);
    }

    /** Rows asked of the store in one lookup. */
    private static final int FETCH_BATCH = 400;

    /**
     * The snapshot's rows, assembled from {@code previous} plus only the rows whose mark changed, or
     * {@code null} when that cannot be done with certainty — the caller then reads every row.
     *
     * <p>REL44-49. The rows come out in the order the store lists them ({@code marks} is in that
     * order), unchanged rows are the objects the previous snapshot held, and changed rows are fetched
     * fresh, so the result is what a full read would give. Any doubt answers null: no previous
     * marks, more than {@link #maxIncremental()} changed rows, a lookup that returned fewer rows than
     * were asked for (the store's lookup swallows its own failure and answers an empty list, which
     * here would silently drop rows), or a mark with no row behind it.</p>
     */
    private static List<StoredEntry> refreshed(ExperienceStore store, Map<String, String> marks,
            Snapshot previous) {
        if (previous == null || previous.marks() == null) {
            return null;
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> mark : marks.entrySet()) {
            if (!mark.getValue().equals(previous.marks().get(mark.getKey()))) {
                changed.add(mark.getKey());
                if (changed.size() > maxIncremental()) {
                    return null;
                }
            }
        }
        Map<String, StoredEntry> fresh = new java.util.HashMap<>();
        for (int from = 0; from < changed.size(); from += FETCH_BATCH) {
            List<String> batch = new ArrayList<>(
                changed.subList(from, Math.min(changed.size(), from + FETCH_BATCH)));
            List<StoredEntry> fetched = store.byIds(batch);
            if (fetched.size() != batch.size()) {
                return null;
            }
            for (StoredEntry e : fetched) {
                fresh.put(e.id(), e);
            }
        }
        Map<String, StoredEntry> held = new java.util.HashMap<>();
        for (StoredEntry e : previous.all()) {
            held.put(e.id(), e);
        }
        List<StoredEntry> rows = new ArrayList<>(marks.size());
        for (String id : marks.keySet()) {
            StoredEntry row = fresh.containsKey(id) ? fresh.get(id) : held.get(id);
            if (row == null) {
                return null;
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Read every row and assemble a snapshot, reusing the word index of any lane whose rows did not
     * change since {@code previous}.
     *
     * <p>REL44-49. Every write moves the stamp, so every write used to cost a full rebuild, and
     * sampled over 10,189 rows the word indexes were a quarter of a write's own steering lookup. The
     * rows are still all read — freshness is exactly what it was — but an index is a function of its
     * lane's rows alone ({@link LexicalIndex#sameWords}), so only the lane that was written is
     * indexed again.</p>
     *
     * @param previous the snapshot being replaced, or null when there is none
     */
    private static Snapshot build(ExperienceStore store, String stamp, Snapshot previous) {
        // The marks are read AFTER the stamp the caller holds, and before any row, for the reason the
        // stamp is: a write landing in between leaves this snapshot labelled with older marks, and the
        // next call refreshes the rows that moved again.
        Map<String, String> marks = store.rowMarks();
        List<StoredEntry> read = marks == null ? null : refreshed(store, marks, previous);
        List<StoredEntry> all = Collections.unmodifiableList(
            read != null ? read : new ArrayList<>(store.all()));
        List<StoredEntry> live = new ArrayList<>(all.size());
        for (StoredEntry e : all) {
            if (e.isLive()) {
                live.add(e);
            }
        }
        Map<KnowledgeLane, List<StoredEntry>> byLane = new EnumMap<>(KnowledgeLane.class);
        for (StoredEntry e : live) {
            // A row whose type no ruling covers is in no lane, so it is in no lane's corpus.
            KnowledgeLane lane = KnowledgeLane.of(e.type(), e.facets().provenanceKind());
            // D45: the lane decides which statuses it serves (a proposed rule is in no corpus).
            if (lane != null && lane.admits(e.status())) {
                byLane.computeIfAbsent(lane, k -> new ArrayList<>()).add(e);
            }
        }
        Map<KnowledgeLane, LaneCorpus> lanes = new EnumMap<>(KnowledgeLane.class);
        for (KnowledgeLane lane : KnowledgeLane.values()) {
            List<StoredEntry> rows = Collections.unmodifiableList(
                byLane.getOrDefault(lane, List.of()));
            LaneCorpus kept = previous == null ? null : previous.lane(lane);
            LexicalIndex.Corpus words = kept != null && LexicalIndex.sameWords(kept.live(), rows)
                ? kept.words()
                : LexicalIndex.index(rows);
            lanes.put(lane, new LaneCorpus(lane, rows, words));
        }
        return new Snapshot(stamp, all, Collections.unmodifiableList(live),
            Collections.unmodifiableMap(lanes), marks);
    }
}
