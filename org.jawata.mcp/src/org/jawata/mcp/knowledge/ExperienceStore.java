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
     * Sprint 28c D14 (delivered 2026-08-27 on Harald's ruling) — stamp WHICH
     * CLIENT recorded an entry.
     *
     * <p>The record path cannot write this itself: a tool sees only its
     * arguments, and the session — the thing that knows the client — lives at
     * the protocol layer. So the stamp is applied POST-INSERT by the EventTap
     * collaborator, which receives every completed call WITH its session id and
     * resolves the client through {@code ClientDirectory}'s closed vocabulary.
     * The value stored is that vocabulary ({@code claude_code}, {@code cursor},
     * …, {@code unknown}), never a raw client string.</p>
     *
     * <p>ABSTRACT, not defaulted — the compiler names every implementation that
     * must forward it, same rule as {@link #markEvidenceDead}.</p>
     *
     * @return true when the row was stamped; false when no row has this id or
     *         the client is null/blank (nothing is written, never a default)
     */
    boolean setOriginClient(String id, String client);

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

    /**
     * Sprint 28f D1 — persist a source's entry IN PLACE, deleting nothing.
     *
     * <p>The idempotence {@link #putWithSource(ExperienceEntry, String)} describes is
     * bought by {@link #deleteBySource} running first, which means a load that dies
     * between the delete and the re-insert has taken that file's rows with it, and
     * that every row's id changes on every re-ingest. This is the durable form: an
     * existing row for the same source and summary is rewritten and KEEPS ITS ID, a
     * new one is inserted, and nothing is removed.</p>
     *
     * <p><b>The default is the honest degradation, not a silent one.</b> A store that
     * cannot do this falls back to the insert — which is what every implementation but
     * H2 is, and the interface says so rather than forcing a stub that would lie about
     * updating in place.</p>
     */
    default String upsertBySource(ExperienceEntry entry, String sourceRef, String sourceHash) {
        return putWithSource(entry, sourceRef, sourceHash);
    }

    /** Sprint 21b: true when an entry for {@code sourceRef} exists with this exact
     *  content hash — the load can skip the source without a single write. */
    default boolean sourceUnchanged(String sourceRef, String sourceHash) {
        return false;
    }

    /** Remove all entries (+ children) that came from a given source; returns rows removed. */
    int deleteBySource(String sourceRef);

    /**
     * Sprint 28f — after ingesting a source, drop the rows of it this pass did not write.
     *
     * <p>The companion to {@link #upsertBySource}, and the half that keeps "a load deletes
     * nothing" from meaning "a load accumulates". The upsert matches a row by
     * {@code (source_ref, summary)}, so an edited description or a renamed section heading
     * matches nothing and inserts — leaving the row the file used to say beside the row it
     * says now, with nothing to distinguish the withdrawn statement from the current one.
     * Passing the ids this pass wrote is what lets the store tell them apart.</p>
     *
     * <p><b>Declared with NO default, deliberately.</b> A default would let a delegating
     * store compile without forwarding it and leak silently through the wrapper every
     * production load goes through — which is exactly what {@link #upsertBySource}'s
     * default nearly did, and the reason its own implementation carries a warning. Here
     * the compiler is the guard: a new implementor cannot forget.</p>
     *
     * @param keepIds every id this pass wrote for the source. An EMPTY set does nothing —
     *                "the file yielded nothing" must never read as "delete the file's
     *                knowledge"; {@link #deleteBySource} is how a caller asks for that.
     * @return rows dropped
     */
    int retainSourcedRows(String sourceRef, java.util.Set<String> keepIds);

    /**
     * Remove exactly the entries named, by id; returns rows removed.
     *
     * <p>The id form exists because {@link #deleteBySource} cannot express "this
     * row and not its twin": a replaced catalogue row and the row replacing it
     * share a {@code sourceRef}, so a delete keyed on the ref would take the live
     * one with the dead one. Any lane that REPLACES a row rather than appending
     * to it needs this.</p>
     *
     * <p>Abstract, not defaulted, for the same reason {@link #tombstone} is: a
     * wrapper that silently inherited a no-op would report a removal it never
     * made, and every count downstream would agree with it.</p>
     */
    int deleteByIds(java.util.List<String> ids);

    /** Remove everything (maintenance: wipe); returns the entry count removed. */
    long wipe();

    /**
     * Sprint 28c (v14) — mark a source as DELIBERATELY REMOVED from this store, so a
     * crawl (the studio's deploy-time auto-seed above all) never re-imports it.
     * Keyed on the ref alone, not a content hash: the decision is about the source,
     * and an edited file must stay out too. Upsert — re-tombstoning refreshes the
     * reason. Abstract, not defaulted: every wrapper must forward it, or a store
     * behind a recovery shim would silently drop the one write that makes a
     * curation stick.
     */
    void tombstone(String sourceRef, String reason);

    /** Every tombstoned source ref. The crawl fetches this ONCE per run. */
    java.util.Set<String> tombstonedRefs();

    /**
     * Clear the whole tombstone table — the first half of a reseed's revival
     * contract. The reseed carries the old tombstones forward in its own
     * before-set and re-writes only the refs its reload did NOT bring back, so
     * a source the user deliberately reloads is revived by not being
     * re-tombstoned.
     *
     * <p>This verb arrived WITH its caller, per this file's own rule: revival
     * used to ride {@link #wipe()}, and when the reseed stopped wiping the
     * store (a wipe may only delete what something can put back — the
     * catalogue and direct records have no file behind them) the tombstone
     * half of that contract needed its own verb or tombstones would outlive
     * the user's own decision to reload a source. A per-ref
     * {@code clearTombstone} shipped here once with zero callers and was
     * deleted; this one is different in exactly the way that matters.</p>
     */
    void clearTombstones();

    /** Distinct {@code memory:} source refs of file-derived entries — the set a
     *  reseed diffs to decide what it deliberately did not reload. */
    java.util.Set<String> fileSourceRefs();

    /** Every entry (maintenance: refresh re-resolves their pointers through JDT). */
    List<StoredEntry> all();

    /**
     * A cheap value that changes whenever the entries change — a row written, updated,
     * re-statused or removed, by this process or by another engine on the same store — or
     * {@code null} when this store cannot say.
     *
     * <p>It is what lets retrieval keep the corpus in memory ({@link StoreCorpus}) instead
     * of reading every row on every recall, which a profile on 2026-09-14 measured at 61%
     * of a 1.5 s recall over 10,257 rows. {@code null} is the safe answer: a caller that
     * gets it reads the store as it always did.</p>
     */
    default String changeStamp() {
        return null;
    }

    /**
     * Record that entry {@code id} now has a file behind it: {@code sourceRef} is the
     * {@code memory:} reference the loader would give that file and {@code sourceHash} the
     * loader's key for its content.
     *
     * <p>Used when an acceptance exports a story INTO the folder the store loads from. Without
     * it the next load reads the exported file as new knowledge and the entry exists twice —
     * measured 2026-09-14 on the test that guards loads (190 rows became 191). With it the
     * loader finds the row by that reference, skips the file while it is unchanged, and
     * rewrites the same row in place when someone edits it.</p>
     *
     * @return whether a row was updated
     */
    default boolean attachSource(String id, String sourceRef, String sourceHash) {
        return false;
    }

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
     * Sprint 28f Stage 5 — stamp a rule's version number; true when a row changed.
     *
     * <p>Column-only, like {@code updateSymbolAnchor}: it never touches {@code status},
     * {@code source_hash} or the frozen {@code body_json}. A rule's version is bookkeeping
     * about the rule's HISTORY, not a change to what the rule says.</p>
     */
    boolean setRuleVersion(String id, int version);

    /**
     * Sprint 28f Stage 5 — mark a rule as having STOPPED APPLYING, as of now.
     *
     * <p>Deliberately not a status change, and the entry stays readable afterwards.
     * {@code superseded} would claim a newer version replaced it and {@code rejected} that
     * it was judged wrong; a retired rule is neither — it was right and it stopped applying,
     * which is a DATE rather than a verdict.</p>
     *
     * @return true when a row changed; false when the id is unknown or already retired
     */
    boolean retire(String id);

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

    /**
     * Sprint 28f Stage 7 — a member was RENAMED, so every row anchored at its old
     * fully-qualified name now points at the new one. Answers how many moved.
     *
     * <p><b>Not {@link #updateSymbolAnchor}, and the two must never share a name.</b>
     * That one sets ONE row's anchor, addressed by row id, and is how an unanchored row
     * GETS a pointer. This one is addressed by the NAME and the caller does not know, or
     * want to know, which rows were pointing at it. Both are {@code (String, String)}, so
     * an overload pair would be chosen by what the caller meant rather than by anything
     * the compiler could check — and picking the wrong one either rewrites one row's
     * anchor to a fully-qualified name or moves every row anchored at a row id. Neither
     * fails where it happened.</p>
     *
     * <p>The count is the return value because a mover that answers only "done" cannot be
     * told from one that moved nothing — and moving nothing is the normal case, since
     * most renamed members have no job describing them.</p>
     *
     * <p>Scope: the {@code symbol_fqn} column, which is where an anchor lives. Symbols
     * named in an entry's SCOPE are a separate population and are not moved here.</p>
     *
     * @return the number of rows whose anchor moved; zero when no row named that member
     */
    default int moveSymbolAnchor(String fromFqn, String toFqn) {
        return 0;
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
     * Sprint 28g D25 — how many of our own experiences record having USED this
     * catalogue pattern.
     *
     * <p>It is on this interface rather than only on the implementation because
     * D25's measure is that the count is <em>answerable as a query</em>: a
     * caller holding a store must be able to ask, without knowing which store
     * it holds or going near a link table.</p>
     *
     * <p>Zero means nobody has recorded using it, which is the normal state for
     * most of the catalogue and is not a verdict on the pattern.</p>
     *
     * <p><b>ABSTRACT, not a default, and the hollow-wiring gate is what corrected that.</b>
     * It shipped as {@code default ... { return 0; }} — and
     * {@link RecoveringExperienceStore}, which production serves through, does not
     * forward what it does not declare. So the wrapper answered ZERO for every pattern
     * whatever the wrapped store held, and the sentence two paragraphs up — that a
     * method reachable only by casting silently does nothing where it matters — was
     * true of this method, written in its own javadoc.</p>
     *
     * <p>Abstract is what makes the compiler name every store that has not answered,
     * which is the reason {@link #degradedNotice()} has no default either.</p>
     *
     * @param patternTarget the pattern an {@code applied} link points at
     */
    int appliedSampleCount(String patternTarget);

    /**
     * Sprint 28g D28 — the rows whose summary is a section HEADING rather than a
     * sentence of knowledge, as the shipped admission gate itself judges them.
     *
     * <p>D28's first measure — that the gate which refuses a heading at
     * {@code record} applies to LOADED files too — was delivered in Sprint 28c
     * (D3) and is covered by
     * {@code ExperienceMaintenanceTest#a_heading_shaped_memory_file_is_refused_and_the_refusal_is_reported}.
     * Its SECOND measure is about this store rather than about the door:
     * <em>after a reload no row's summary is a section heading</em>. Nothing could
     * answer that. The load gate refuses new ones; it says nothing about the 49
     * rows that were already in when it shipped, and superseding is not deleting
     * in this store, so a reload does not carry them out.</p>
     *
     * <p><b>It asks {@link AdmissionPolicy} rather than re-deciding.</b> The rule
     * lives in one private pattern read by one method, and a second copy here —
     * in Java or as a SQL {@code LIKE} — would be the two-readers-of-one-rule
     * shape this sprint has had to close four times. {@code check(summary, null)}
     * is an exact heading test by construction: the summary is checked for
     * heading shape and nothing else, so a null symptom list reaches no other
     * branch.</p>
     *
     * <p><b>ABSTRACT — but unlike {@link #appliedSampleCount}, this one was never
     * broken, and the C13 audit is what corrected that claim.</b> It shipped as a
     * default whose body read {@code for (StoredEntry e : all())} — an UNQUALIFIED
     * call, so through {@link RecoveringExperienceStore} it dispatched to that class's
     * own {@code all()}, which forwards to the delegate. The default therefore audited
     * the wrapped store's real rows and answered correctly. Four places including this
     * one said it answered EMPTY through the wrapper; that was false, and it was false
     * because the enumeration that found the genuine defect next door was carried
     * across to this member without being re-checked against its body.</p>
     *
     * <p>It is abstract anyway, for a reason that survives the correction: the default
     * was right only by the accident of every implementor overriding {@code all()}, and
     * a store that did not would have audited the wrong rows while looking identical
     * from here. Abstract makes the compiler ask rather than answer on its behalf.</p>
     *
     * <p>The shared body lives in {@link #auditHeadings(ExperienceStore)} so the two
     * implementations that really walk their rows share one rule rather than two.</p>
     *
     * @return one entry per offending row, {@code id} to {@code summary}, newest
     *     first as {@link #all()} orders them; empty when the store is clean
     */
    List<Map<String, Object>> headingShapedSummaries();

    /**
     * The audit itself, over any store's own rows — one implementation of the rule,
     * asked by every store that can actually answer it.
     *
     * <p>It asks {@link AdmissionPolicy} rather than re-deciding: the rule is one private
     * pattern read by one method, and a second copy here would be the
     * two-readers-of-one-rule shape this sprint has had to close four times.
     * {@code check(summary, null)} is an exact heading test by construction, since a
     * summary is checked for heading shape and nothing else.</p>
     */
    static List<Map<String, Object>> auditHeadings(ExperienceStore store) {
        List<Map<String, Object>> offending = new java.util.ArrayList<>();
        for (StoredEntry e : store.all()) {
            if (AdmissionPolicy.check(e.summary(), null)
                    .filter(r -> r.shape() == AdmissionPolicy.Shape.HEADING).isPresent()) {
                offending.add(Map.of("id", e.id(), "summary",
                    e.summary() == null ? "" : e.summary()));
            }
        }
        return List.copyOf(offending);
    }

    /**
     * Sprint 28g S14 D30 — rewrite one job row in place, keeping its id.
     *
     * <p><b>The defect this exists for:</b> {@code record type=job} inserts, so a member
     * described twice ends up with two jobs and the older one describes code that has
     * since changed. Measured 2026-10-07 over {@code org.jawata.mcp.knowledge} alone,
     * uncapped: <b>73 members carry more than one job row, 75 surplus rows</b> — and the
     * session that took that measurement had just created some of them itself, by
     * following an "undescribed members" list that names members which already have a
     * job. jawata-mcp#85 carries the store-wide census.
     *
     * <p><b>There is deliberately no companion read, because one already exists.</b>
     * {@code listEntries(type="job", scope=<the exact member symbol>)} answers it: that
     * clause tries {@code symbol_fqn = ?} first, so an exact anchor matches. The first
     * draft of this work added a {@code jobsAnchoredTo} beside it and the duplicate gate
     * refused it — correctly. What misled that draft is worth recording, because it is a
     * real defect in the same clause: the scope filter's LIKE pattern is
     * {@code scope + ".%"}, and a job's symbol separates its member with {@code #}, so
     * scoping to a TYPE silently finds none of its members' jobs. Exact works; the
     * type-wide form does not.
     *
     * <p>The id is kept because it is the entry's identity: links point at it and the
     * review ledger names it, so a replacement that minted a new id would orphan both
     * while looking like a clean update. This is what {@code upsertBySource} already does
     * for a re-read file, and it reuses that same rewrite rather than copying its column
     * list — that list's own javadoc warns it is a fourth place to widen when a column is
     * added, and a fifth copy is the defect this codebase has closed repeatedly.
     *
     * <p>Reusing it also carries two behaviours a fresh statement would have had to
     * rediscover: the row's meaning vectors go to NULL so the changed text is re-embedded
     * (a stale vector ranks the row on words it no longer carries), and {@code source_ref}
     * is deliberately NOT written, so a row's provenance survives the rewrite.
     *
     * @param existingId the row to rewrite — a caller that found it through
     *     {@link #jobsAnchoredTo} holds the guarantee that it exists
     * @param entry the replacement
     * @return {@code existingId}, so a caller can report what it wrote without
     *     re-deriving it
     */
    String replaceJob(String existingId, ExperienceEntry entry);

    /**
     * Sprint 28g D40c — tell the store which folder it is loading stories from.
     * Called once per root by the loader, which is the only place that knows.
     *
     * <p>Recording the SAME folder again is a no-op — a reload is not a second
     * folder. Recording a DIFFERENT one makes the answer AMBIGUOUS and
     * {@link #storyFolder()} then answers empty, which is the whole deliverable:
     * the folder was previously GUESSED as the deepest directory holding every
     * loaded {@code memory:} path, so two roots resolved to their shared
     * ancestor and an accepted story would have been exported there. With the
     * layered roots this installation actually configures that ancestor is a
     * home directory.</p>
     *
     * <p>Empty is the honest answer rather than a fallback to the guess: a story
     * written into an ancestor is not read back by any load, so it is a file
     * nobody sees that also looks like a success.</p>
     */
    /**
     * <p><b>ALL THREE ARE ABSTRACT, and the architect's C13 F1 is why.</b> They shipped
     * as defaults — a no-op recorder, an empty folder and a false flag — and
     * {@link RecoveringExperienceStore}, which production serves through, forwards only
     * what it declares. So through the wrapper the loader's recording was DISCARDED, the
     * folder was always empty, and this deliverable did not hold in production at all:
     * it replaced a wrong answer, the home-directory ancestor, with no answer.</p>
     *
     * <p>That was the THIRD member of this interface to ship that way in one stage, after
     * {@code appliedSampleCount} and {@code headingShapedSummaries}. The rule the three of
     * them leave behind: <b>this interface must not carry a {@code default} for anything
     * whose honest answer depends on which store you are holding.</b> A default is right
     * only where every implementation would answer the same.</p>
     */
    void recordStoryFolder(java.nio.file.Path folder);

    /**
     * The folder this store loaded its stories FROM, as recorded rather than derived.
     *
     * <p>Empty means one of three different things and the caller must not read it
     * as one: nothing was ever recorded, the store does not remember, or SEVERAL
     * folders were recorded and no single one is the store's own. All three mean
     * the same for an export — do not write a file — which is why they share an
     * answer, and {@link #storyFolderAmbiguous()} separates the third for a
     * reader who needs to say WHY nothing was written.</p>
     */
    Optional<java.nio.file.Path> storyFolder();

    /**
     * Whether {@link #storyFolder()} is empty BECAUSE more than one folder was
     * recorded, as opposed to none having been.
     *
     * <p>It exists so a response can tell a reader which of the two it is. An
     * export that silently writes nothing is indistinguishable from one that ran
     * and found nothing to do, and that is the shape of silence this store has
     * had to remove three times.</p>
     */
    boolean storyFolderAmbiguous();

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
