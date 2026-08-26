package org.jawata.mcp.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 21 (v2.0): the local experience/knowledge store — embedded, workspace-scoped,
 * H2-backed. Opened at application start, closed at stop. This is the persistence the
 * {@code ExperienceAdvisor} (which fills {@code org.jawata.mcp.domain.Advisor}) reads and
 * writes.
 *
 * <p>Entries are {@link SymbolFact}s (the shared Sprint-15a shape). Stage 0 defines the
 * open/close lifecycle plus a single {@link #put}/{@link #get} round-trip and the schema;
 * richer indexed persistence (Stage 1) and two-phase fit-gated retrieval (Stage 2) build
 * on this seam. {@link #get} returns the stored document as a map (a typed reconstruction
 * is a later concern) so the store never needs to reverse {@link SymbolFact#toMap()}.</p>
 */
public interface ExperienceStore extends AutoCloseable {

    /**
     * Sprint 28c: record that the evidence behind an entry is gone — the anchor
     * no longer resolves, the source file is missing — WITHOUT touching status.
     *
     * <p>The separation is the point. Superseding an entry says "this is no
     * longer true"; this says "the thing it pointed at has moved, and a human
     * should look". For a form-1 entry those are different claims, because its
     * situation says when it applies and the anchor only says where it was
     * learned. Retiring knowledge because the evidence moved is the design flaw
     * this sprint exists to fix, so a resolver may mark but never retire.</p>
     *
     * <p>ABSTRACT, not defaulted: a default here would let a wrapper silently
     * swallow the mark, which is the degradedNotice lesson. The compiler names
     * every implementation that must forward it.</p>
     *
     * @return true when the flag was newly set; false when it was already set
     *         or the entry is gone
     */
    boolean markEvidenceDead(String id);

    /**
     * Sprint 28c Stage 15 — REWRITE an entry's form: replace its situation and
     * outcome, whatever they were, and stamp who decided.
     *
     * <p>{@link #setForm}'s javadoc rules that nothing but the migration may
     * form a row, because "a second path that can rewrite it after the fact
     * turns a stated experience into a guessed one". This IS that second path,
     * and it exists on Harald's explicit instruction (2026-08-26), because the
     * ruling protected against the wrong risk once the store could diagnose:
     * {@code migrate_form}'s mechanical derivation produced situations reading
     * "when by construction" and "when $8 on one day", and WITHOUT a rewrite
     * path those stand forever — a guessed experience protected from the only
     * correction it could get. The differences from the risk the ruling named:
     * the caller is a seat whose every rewrite is PROPOSED to a human first;
     * the text passes the same {@link EntryForm} gate as {@code record}; and
     * the row is stamped {@code provenance_kind = 'seat_rewritten'}, so a
     * reader can always tell a reviewed correction from an author's own words.</p>
     *
     * <p>Unlike {@code setForm} this updates a row whether or not it already
     * carries a form — fixing a badly-formed situation is its whole purpose —
     * and it hands the row back to the embedding backfill by clearing
     * {@code embedder_identity} and the lane vectors: the backfill's selection
     * is "no vector, no identity, or a stale identity", so stale-identity
     * clearing is what makes the NEW situation reach the meaning lanes instead
     * of the old text answering there forever (F2's permanent case).</p>
     *
     * <p>ABSTRACT, not defaulted — same rule as {@link #markEvidenceDead}: the
     * compiler names every implementation that must forward it.</p>
     *
     * @param situation the corrected condition; never null or blank
     * @param verdict   the outcome, or null for a type that owes none
     * @return true when the row was updated; false when no row has this id
     */
    boolean rewriteForm(String id, String situation, String verdict);

    /**
     * Sprint 28c D4 — give an existing row the 28c form: a situation, an
     * outcome, and {@code form = 1}.
     *
     * <p>The ONLY writer is {@link FormMigration}, and it is confirm-gated by a
     * human who has read the dry-run report. Nothing else may call this: an
     * entry's form is declared by its author at record time, and a second path
     * that can rewrite it after the fact turns a stated experience into a
     * guessed one.</p>
     *
     * <p>Returns true when the row was newly formed. A row that already carries
     * a form is left alone and returns false, so re-running the migration is a
     * no-op rather than a re-derivation — the same idempotence
     * {@link #markEvidenceDead} has, and for the same reason.</p>
     */
    boolean setForm(String id, String situation, String verdict);

    /** Persist a fact as a {@code candidate} entry; returns the generated entry id. */
    String put(SymbolFact fact);

    /** Persist a full entry (with retrieval facets: status/scope/operation/symptoms/links). */
    String put(ExperienceEntry entry);

    /**
     * Persist an entry tagged with an origin marker (Stage 4). A re-load of the same
     * {@code sourceRef} is made idempotent by {@link #deleteBySource} first — so seeding
     * from a memory file twice replaces rather than duplicates.
     */
    String putWithSource(ExperienceEntry entry, String sourceRef);

    /** Sprint 21b: like {@link #putWithSource(ExperienceEntry, String)} but records the
     *  source content hash so {@link #sourceUnchanged} can skip future no-op re-loads. */
    default String putWithSource(ExperienceEntry entry, String sourceRef, String sourceHash) {
        return putWithSource(entry, sourceRef);
    }

    /** Sprint 21b: true when an entry for {@code sourceRef} exists with this exact
     *  content hash — the load can skip the source without a single write. */
    default boolean sourceUnchanged(String sourceRef, String sourceHash) {
        return false;
    }

    /** Remove all entries (+ children) that came from a given source; returns rows removed. */
    int deleteBySource(String sourceRef);

    /** Remove everything (maintenance: wipe); returns the entry count removed. */
    long wipe();

    /** Every entry (maintenance: refresh re-resolves their pointers through JDT). */
    List<StoredEntry> all();

    /** Fetch an entry's stored document by id, or empty when absent. */
    Optional<Map<String, Object>> get(String id);

    /**
     * Phase-1 candidate gather (Sprint 21 Stage 2): keyword/alias-match any present cue over
     * the indexed scope columns + the symptom child table. Deliberately <em>generous</em> —
     * the {@link ExperienceRetrieval} fit-gate does the precise scope-containment. Rejected
     * entries are excluded. An empty query returns an empty list.
     */
    List<StoredEntry> query(RecallQuery query);

    /**
     * Sprint 27 D2: fetch entries by id, preserving the given ORDER — that order
     * is the meaning nominator's ranking. Missing ids are skipped, so the result
     * may be shorter than the input.
     *
     * <p>Declared on the interface rather than reached for by casting the
     * concrete type: the resident wraps the real store in
     * {@link RecoveringExperienceStore}, so an {@code instanceof} check against
     * the H2 class would silently fail in production and semantic recall would
     * be dead everywhere except tests.</p>
     */
    List<StoredEntry> byIds(List<String> ids);

    /** Update an entry's curation status; returns true when a row changed. */
    boolean setStatus(String id, String status);

    /**
     * Sprint 21e (item A): column-only write of the AUTOMATIC symbol anchor —
     * {@code symbol_fqn} ONLY ({@code null} clears it). Never touches
     * {@code package_name} (the author-asserted {@code packages[]} channel),
     * {@code source_hash} (byte-strict skip must not see anchoring as change) or
     * {@code status}. The frozen {@code body_json} stays untouched too — its fact-map
     * {@code symbol} key remains the ASSERTED-provenance marker.
     */
    default boolean updateSymbolAnchor(String id, String symbolFqn) {
        return false;
    }

    /** Total entry count — diagnostics + tests. */
    long count();

    // --- Sprint 21a (item G): curation --------------------------------------------------

    /**
     * Full-fidelity export of every entry (optionally filtered by {@code status} /
     * {@code type}): all columns + facets + symptoms + links + timestamps, portable enough
     * that {@link #importEntries} round-trips losslessly. Backup, sharing, cross-machine
     * seeding — ahead of the networked store.
     */
    List<Map<String, Object>> exportEntries(String status, String type);

    /** Re-ingest exported entries; dedup by id. Returns {@code {imported, duplicates, invalid}}. */
    Map<String, Object> importEntries(List<Map<String, Object>> entries);

    /**
     * Curation listing — {@code recall} is terminal-single by design, but you cannot
     * promote what you cannot see. Filter by {@code type} / {@code status} / {@code scope}
     * (symbol/package prefix) / {@code language}; newest first, capped at {@code limit}.
     * Unlike {@link #query}, rejected/superseded entries are INCLUDED (curation sees all).
     */
    List<StoredEntry> listEntries(String type, String status, String scope, String language, int limit);

    /**
     * GC the store itself: delete {@code rejected}/{@code superseded} entries older than
     * {@code days} (by {@code updated_at}); children removed too. Returns rows removed.
     */
    int pruneAged(int days);

    /**
     * Reclaim file space after prunes/wipes (H2 {@code SHUTDOWN COMPACT} + reopen).
     * NOTE: briefly closes the database — concurrently attached residents (AUTO_SERVER)
     * lose their connection and must reopen. Run when quiet. No-op for in-memory stores.
     */
    Map<String, Object> compact();

    /**
     * Sprint 21a (item F): store overview for UIs/diagnostics — entry counts by status
     * and language plus the backing file location + size ({@code in-memory} when none).
     */
    Map<String, Object> stats();

    /**
     * Sprint 21a (item B): provenance stamped on every subsequent write — the workspace +
     * project this resident serves (from {@code workspace.json} at store-open). Enables the
     * user-level shared store (item H) to keep per-workspace attribution. No-op by default.
     */
    default void setProvenance(String workspaceId, String projectId) {
    }

    /**
     * v2.5.1: the store's OWN workspace identity (what {@link #setProvenance} installed),
     * or {@code null} when none is configured. Anchor maintenance is workspace-scoped on
     * the SHARED store: a resident may judge/backfill ONLY entries stamped with its own
     * workspace — judging a foreign workspace's anchors against the wrong project set
     * superseded 304 live entries on 2026-07-08. Null = standalone/test store → today's
     * judge-everything semantics.
     */
    default String provenanceWorkspaceId() {
        return null;
    }

    /**
     * #37: why this store cannot currently give a trustworthy answer, or {@code null}
     * when it can.
     *
     * <p>A store that is serving from a fallback still ANSWERS — and its empty answer
     * is indistinguishable, in shape, from "I looked and there is nothing". That is the
     * lie this method exists to make impossible: retrieval asks before it reports an
     * absence, and reports {@code unavailable} instead when this is non-null.</p>
     *
     * <p>On the interface rather than on the one implementation that has it, because
     * every caller reaching it through {@code ExperienceStore} would otherwise need an
     * {@code instanceof} — and an {@code instanceof} against a wrapped store is how a
     * production path silently took the not-degraded branch before (the C4-F1 lesson).</p>
     *
     * <p><b>Abstract, not defaulted.</b> It shipped as {@code default { return null; }} for
     * twenty-four minutes, and in that time a wrapper was written whose {@code
     * degradedNotice} was the ONE method of twenty that did not forward to its delegate.
     * Invisible — because the default answered "not degraded" for the wrapped store anyway.
     * A default here lets an implementation look complete while silently never reporting
     * degradation, which is the hollow shape this codebase has already paid for. Every store
     * states its own answer, and the compiler names the ones that have not.</p>
     */
    String degradedNotice();

    @Override
    void close();
}
