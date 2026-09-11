# ARCHITECTURE — Sprint 28f, the database (jawata-mcp 4.3)

Design-mode run of the architect seat, 2026-09-11, against the signed spec
`jawata-enterprise/docs/sprints/jawata-mcp/sprint-28f-database.md` (GATE 1, `9ec07bb`). Every
claim about the current code below was read through jawata (`inspect`, `search_symbols`,
`type_members`, `source`) or from the studio sources named; nothing is recalled.

**The store consulted before designing (D-FOUR).** `experience(kind=nominate)` returned eight
candidates; four were selected and shape this design, four were not:

| selected | what it made this design do |
|---|---|
| *once the store is rebuilt from files, a direct record is deleted by the next reseed* | there is no rebuild left in normal operation — the folder is a fill and an export, never a source; the one destructive verb is named for it and sits behind a backup |
| *give new material the format the existing pipeline already reads — a step a source can decline is a step a source will decline* | the story export writes exactly the format `ExperienceMaintenance.parse` already reads (`docs/story-template.md`); the import IS `load`; job and area rows are entries in the one table with the one form gate, never a second table with a second loader |
| *re-seeding a catalogue supersedes rows rather than removing them; only prune deletes* | "no deletion in any normal operation" reuses the supersede status that already exists; the catalogue seeder's lifecycle is kept, not rewritten |
| *when one metric serves several jobs, name each job's band* | the retrieval band, the dedup band and the new duplicate-nomination band are three named rows, derived from measured distributions, and they never swap jobs (§8) |

Not selected: the DAO / DAO-factory / sharding catalogue patterns (the store already sits behind the
`ExperienceStore` interface with one H2 implementation — naming the pattern adds nothing), and
"read the producer's configuration before building a guard" (the guards here fire on the agent's
own output; there is no producer switch to open). No catalogue pattern was adopted (D-FIVE).

---

## 1. The one target

```
                      ┌────────────────────────── jawata-studio (guest mode, 4.3/4.4) ──────────────────────┐
                      │  jawata-hook binaries (roles.rs)                seats/               MemoryView /   │
                      │  UserPrompt ─ map at task start                 cataloguer.md        RuntimeSettings │
                      │  ToolRecall ─ area on first Read · dupgate on   architect.md         backups·restore │
                      │               a Write/Edit that adds a method   (detect step reads   lanes · rules   │
                      │  Stop       ─ "done" refused while a job is     the population)      promote·retire  │
                      │               unrecorded; substrate rule says                        coverage N of M │
                      │               import, never reseed                                                    │
                      └──────┬──────────────────────────────┬──────────────────────────────────┬─────────────┘
                             │ MCP (JSON-RPC)                │ runner.rs run_seat               │ tauri cmds
                             ▼                              ▼                                  ▼
   ┌──────────────────────────────────── jawata-mcp engine ───────────────────────────────────────────────┐
   │  tools/ExperienceTool  ── the verbs: record · recall(lane) · load(import, merging) · wipe_and_import │
   │                            · backup/restore · vectorise · promote(→export) · promote_rule · retire   │
   │                            · catalogue next/done · duplicate_check · stats                           │
   │  tools/smell/ReDerivedJobDetector ── S9: same shape + overlapping symbols, no supertype, no ref      │
   │                                                                                                      │
   │  knowledge/                                                                                          │
   │   H2ExperienceStore ── ONE table, four LANES (column): experience · domain · code · rules           │
   │   EntryForm ── the one form gate (+ job/area/rule forms)     StoreBackups ── H2 online BACKUP TO,    │
   │   ExperienceMaintenance.load ── upsert by source, in place   10 rotating copies, restore             │
   │   StoryWriter ── writes what parse() reads                    CatalogueLedger ── unit·hash·done     │
   │   EmbeddingIndex/Service ── inline + drained backfill         PointerResolver ── anchors live → JDT  │
   └───────────────────────────────┬──────────────────────────────────────────────────────────────────────┘
                                   │ IJdtService (anchors, draft parse, the detector)
                                   ▼
                              org.jawata.core (JDT)  — DiskSyncGuard re-queues a changed unit for the ledger
```

Dependency direction, and it is one-way: **hooks, seats and studio call the engine; the engine
never knows a hook, a seat or a view exists.** The store knows lanes and forms; it knows nothing
about JDT except through `PointerResolver` (the existing seam `ExperienceMaintenance` already
uses for anchor refresh). Nothing in `knowledge/` imports `tools/`; nothing in either imports
studio.

---

## 2. Modules and responsibilities

| Module (package · file) | Responsibility in 4.3 | State today (read) |
|---|---|---|
| `knowledge/H2ExperienceStore` | the one table; gains `lane`, `reviewed_at`, `rule_version`, `retired_at`; `language` default becomes absence; `upsertBySource` (in place) | 100 members; `insert` binds `"java"` at column 18 when the entry declares none; `bulk = sourceRef != null` skips inline embedding; `importEntries`, `exportEntries`, `wipe`, `deleteBySource`, `pruneAged`, `recoverOrphans` exist |
| `knowledge/StoreBackups` (new) | before every destructive verb: H2's online `BACKUP TO <storeDir>/backups/<stamp>.zip`; keep 10, evict the oldest; `restore(name)` = close → replace → reopen through `RecoveringExperienceStore` | nothing; `ExperienceTool.deleteByIds` writes a JSON archive via `writeArchive`/`deletionArchivePath` — that per-row archive is REPLACED by the whole-store copy (one artifact, one lifecycle) while the response keeps listing the ids |
| `knowledge/ExperienceMaintenance` | `load` becomes the merging import: unchanged file skipped (`sourceUnchanged`), changed file UPDATED IN PLACE under its id, new file inserted, vanished file leaves its row; `requireStamp` decides status, never admission; `refresh()` stays the anchor re-resolver | `load(path, recursive, requireStamp)`, `sourceHash`, `refresh`, `backfillAutoAnchors`, `wipe`, `dedup`, the story parser (`MemoryDoc`) |
| `knowledge/StoryWriter` (new) | renders a row into the story format `parse` reads; called on the transition to `accepted`; target = the export folder setting | none; format in `docs/story-template.md` |
| `knowledge/EntryForm` | one gate, extended: `job` (anchors resolve, plain words, not a signature restatement), `area` (one per package), `rule` (version, `derived_from` links); `LINK_RELS` gains `derived_from` | `check(type, summary, symptoms, situation, verdict)`, `LINK_RELS` closed vocabulary |
| `knowledge/EmbeddingIndex` · `EmbeddingService` | inline embedding stays for a direct record; every bulk loader drains the backfill before it returns; `stats.unembedded`; a recall result marks an unembedded row | composite + three lanes; backfill selects "no vector / no identity / stale identity"; runs at start |
| `knowledge/CatalogueLedger` (new) | `catalogue_progress(unit, content_hash, done_at, bundle)` in the same H2 file — the cataloguer's memory; `next(scope, limit)`, `done(unit, hash)`; coverage per bundle | none |
| `knowledge/CatalogueSeeder` | untouched; the vendored catalogue seeds `accepted` (one status literal) | supersede lifecycle (`purgeLeftovers`, `sweepOrphans`) |
| `tools/ExperienceTool` | the verb table: `reseed` → `wipe_and_import` (confirm, backup, load-then-swap, `success=false` when removed > loaded); `backup`, `restore`, `vectorise`, `promote` (exports on accept), `promote_rule`, `retire_rule`, `catalogue` (next/done), `duplicate_check`, `recall(lane=…, population=…)` | 24 kinds in `KINDS`; `reseed` already scoped to the file lane (v14) but deletes per source BEFORE loading; `record` embeds inline and dedup-flags |
| `tools/smell/ReDerivedJobDetector` (new) | S9: nominates re-derived jobs on compiler facts — same signature shape, overlapping foreign symbols, no common supertype, no reference between them; token clones stay `find_duplicate_code`'s | `find_duplicate_code` = exact normalised tokens; the smell family in `tools/smell/` with `CureCatalog` routing |
| `core/workspace/DiskSyncGuard` | untouched; its scan is what re-queues a changed unit in the ledger (a hash mismatch at `next`) | exists |
| studio `jawata-hook/src/dupgate.rs` (new) | the "before creating" refusal — `recallgate.rs`'s shape (Mode Off/Observe/Block, a disposition token, fail OPEN when the store is down) over a Write/Edit that adds a method; token `jawata-duplicate: <reason>` | `recallgate.rs` holds `recall-applied` / `recall-rejected:`; `editgate.rs` holds the `jawata-author:` window; `roles.rs` routes PreToolUse to ToolRecall and Guard |
| studio `jawata-hook/src/pipeline.rs` | UserPrompt: a second query, `lane=code`, rendered as a MAP block (not the NOMINEES block); ToolRecall on a Read of `.java`: the package's jobs once per session (memo dir beside `editgate`'s) | prompt cues → store → `hookSpecificOutput.additionalContext` |
| studio `jawata-hook/src/stop.rs` | `StopFacts` gains the turn's added symbols; a rule "a job is unrecorded" (bounded like `MAX_RESEED_BOUNCES`); the substrate rule's instruction says `load` (merging), never `reseed` | `judge(&StopFacts)`; the substrate rule at ~852 names `experience(kind=reseed…)` |
| studio `seats/cataloguer.md` (new) | tools yes; input = one unit + JDT facts; output = job rows and, at a package's end, its area summary, through `record(type=job|area)`; takes `limit`; run by hand (`/catalogue <package>`) or by the runner's cron `schedule:` once it fires (28g) | `runner.rs`: `SeatDefinition.schedule` parsed, `scheduler_tick` library-level, "NOT yet fired" |
| studio `MemoryView.svelte` · `RuntimeSettings.svelte` | backups list + restore click; lanes; rule promote/retire; coverage per bundle; the export folder setting | the store view and the runtime settings exist |

---

## 3. Seams — what new code plugs into, the pattern each uses, the smell it prevents

| Seam | Pattern | Prevents |
|---|---|---|
| **`ExperienceStore` (interface)** gains `upsertBySource`, `lane` in `RecallQuery`, `backup`/`restore`, `ledger` | the existing repository seam; H2 stays the one implementation | a second store class for the code lane — the second-lifecycle smell the store nominated |
| **`EntryForm.check` per type** | one admission gate, type-aware (already so for lesson vs domain_fact) | a job gate beside the story gate that drifts from it |
| **`StoryWriter` ⇄ `ExperienceMaintenance.parse`** | round-trip pair over ONE format | an export format nobody can import; the second reader |
| **`StoreBackups.before(verb)`** called inside the verb, server-side | template step at the verb (the raw's own ruling: never in a calling skill) | a backup a caller can skip |
| **`PointerResolver`** (exists) | anchors resolve at read; the store holds names, never positions | stale file/line in a row — his "the location in source can be stale" |
| **`jawata rename/move → store.updateSymbolAnchor`** | the refactoring engine notifies the store of old→new FQN in the same change | a rename that orphans every job it touched |
| **`recallgate.rs` shape → `dupgate.rs`** | Mode / Verdict / disposition token / fail-open, copied as a module, not parameterised | a second gate with its own bypass semantics |
| **`StopFacts` rule** | the existing judge with one more fact | a job check on a channel that can only append (rule 6: the stop gate can refuse) |
| **`ReDerivedJobDetector` in `tools/smell`** with a `CureCatalog` row whose cure is the architect's advice | the detector family; routed like every smell | a bespoke duplicate scanner outside the finding/cure machinery |

---

## 4. The four lifecycles — write, gate, update, retire, read

| lane | written by | gate | update | retire | read |
|---|---|---|---|---|---|
| **experience** (lessons, failure modes, patterns) | `record`; the story-folder import | `EntryForm` (situation + verdict for a lesson) → `candidate`; cold reader / `reviewed:` stamp → `accepted` | in place (`setForm`, `rewriteForm` clears vectors) | `superseded` / `rejected`; `prune` deletes aged only, behind a backup | recall at a decision or a symptom |
| **domain** | same | `EntryForm` (no verdict owed) | in place | same | on reading a domain object |
| **code** — `job`, `area` | the closing work item (`record type=job`); the cataloguer seat; NEVER a compiler pass | `job` form: every anchor resolves through JDT · plain words · not a signature restatement; `area`: one per package | in place; anchors re-resolved at read, carried by jawata's own refactorings; unresolvable → `location lost`, queued for re-anchoring | the review sweep (shown often, chosen never); never `wipe_and_import`, never the story import, never `prune` | task start (map) · entering an area · before creating · any seat by meaning · the architect as a population |
| **rules** | `promote_rule(ids…)` from studio — never from a write | `rule` form: ≥1 `derived_from` link, a version | a new version row; the old one `superseded` | `retired_at` set; readable, never deleted | the read path is 28h's (the enforcer) |

**No normal operation deletes a row.** The one destructive verb is `wipe_and_import` (the old
`reseed`): backup first; a root yielding zero loadable files is REFUSED before anything is
staged (otherwise an empty root would "complete" with `loaded=0` and retire every file-lane row —
the 2026-09-08 shape with an honest flag on it; caught by the plan's round-1 audit); then load
into a staging set, and only when the load completed with `loaded >= 1` does it retire the
file-lane rows the load did not bring back (tombstoned, as v14 does today); a load that does not
complete leaves the store as it was; `success=false` whenever removed > loaded.
`wipe`, `prune`, `import` (the blob) and `delete` keep their names and take the same backup first.

---

## 5. Where each deliverable lands

| Spec | Lands in | Notes |
|---|---|---|
| D1 durable writes | `ExperienceTool.wipeAndImport` (renamed via `rename_symbol` from `reseed`; the `KINDS` literal and the skill/seat/stop texts by authoring), `ExperienceMaintenance.load` (upsert in place), `H2ExperienceStore.upsertBySource` | S25's null-`substrate.root` path: `record` is the add-one verb; `substrateBlock` stops advertising `howToAdd` as a file path |
| D2 backup/restore | `StoreBackups`; verbs `backup`, `restore`; `MemoryView` | H2 `BACKUP TO` is an online, consistent copy of an open store — the plan's first gate proves a copy taken under a concurrent writer opens as a working store |
| D3 the story folder | `StoryWriter` on the transition to `accepted` (`promote`); import = `load` with the stamp deciding status; setting `jawata.stories.dir` | one format, one reader; `reviewed_at` on the row is what the export writes as `reviewed:` |
| D4 searchable at once | `EmbeddingIndex.drain()` called by `load`, `importEntries`, `CatalogueSeeder`; `vectorise` verb; `stats.unembedded`; `StoredEntry.embedded` marked in results; catalogue seeds `accepted`; `UsageLedger` renders observed vs derived | the backfill's start-time run stays (resume on restart) |
| D5 language | `H2ExperienceStore.insert` column 18: absence, not `"java"`; `refresh()` re-resolves only rows with a Java anchor | `by_language` already renders `none` |
| D6 four lanes | `lane` column + migration by type; `RecallQuery.lane`; rules verbs; per-lane, per-trigger sweep in `reviewSweep` | the tool lane (`ToolExperienceStore`) is untouched — it is a usage ledger, not knowledge |
| D7 the code lane | `job`/`area` forms; anchors = the existing `symbol`/`scope.symbols` columns; `CatalogueLedger`; `seats/cataloguer.md`; `catalogue` verbs; `ReDerivedJobDetector` (S9); coverage in `stats` and `MemoryView` | 10k rows sit in the one table; the bands in §8 decide search |
| D8 retrieval wired | `pipeline.rs` (map, area), `dupgate.rs` + `duplicate_check` verb (draft parsed by JDT in the engine), `stop.rs` (job rule), architect seat's detect step (population query) | guest-mode channels only; the burned-in role prompts are 28h's |
| D9 issues close | mcp#67 on D4, mcp#57 on D5; the extraction drops `type`, `category`, `tags` | `CatalogueManifest` reads none of the three |

---

## 6. What must NOT be touched

- `RecoveringExperienceStore`'s reconnect semantics — restore goes THROUGH it (close, swap, reopen), never around it.
- `CatalogueSeeder`'s supersede lifecycle and `CatalogueSources`' registry — the code lane is not a catalogue source and must not be registered as one.
- `ToolExperienceStore` (the tool lane) and `UsageLedger`'s counters — only the rendering of observed vs derived changes.
- The refactoring engines' edits — the anchor notification is a listener on the applied change, not a change to any rewrite.
- `EntryForm`'s existing checks — extended by type, never relaxed for stories.
- `editgate.rs` and `recallgate.rs` — `dupgate.rs` copies their shape; it does not change theirs.

---

## 7. The code lane's row, precisely

A `job` is an entry: `type=job`, `lane=code`, `summary` = the job in plain words, `details` = how
it is done here and how it differs from its neighbours, anchors = one or more fully-qualified
symbols in the existing anchor columns, `language` from the anchor. An `area` is an entry:
`type=area`, `scope.packages=[pkg]`, `summary` = what lives here. Both are searched by meaning
over the same composite vector every entry has — no second index. The compiler-facts net is not
a row: it is `ReDerivedJobDetector`'s finding at query time, joined to jobs by anchor for the
architect's population read. The anchor's LOCATION is never stored: `PointerResolver` answers it
at read, `refresh()` marks `location lost`, and the ledger queues the unit for the cataloguer.

---

## 8. The bands — named, measured, never swapped

| job | band | how it is derived |
|---|---|---|
| retrieval (a cue against experience/domain rows) | the broad band, no cutoff; the floor caps volume | as today |
| dedup at write (`record`) | `EmbeddingIndex.DEDUP_THRESHOLD` (~0.90) | as today, re-derived from labelled pairs |
| **the map at task start** (a task against areas + jobs) | **NOT a distance bar — the union of the identity path and meaning, as retrieval already is.** M0 measured the three distributions and they OVERLAP: 3 of 10 genuine task→job pairs score at or below the p95 of unrelated pairs, so no cutoff admits the real answers without admitting noise | AMENDED 2026-09-11 by its own measurement. It read "a distance bar derived from three measured distributions … designated task→job pairs must clear it"; they do not clear it. Sprint 27 met this and shipped a union, recording a cue that "exists precisely because embeddings alone fail it while the symbol path answers it exactly" |
| **duplicate nomination** (a draft against jobs) | meaning above the map's bar AND/OR the compiler-facts net; a hit names the job; below the bar and no net hit → "nothing" | new; the residue measurement (M0) decides whether facts alone suffice |

---

## 9. The test surface (D-THREE)

**Environment-independent — runs once, anywhere, on a temp H2 store:** durability per verb (a
story survives every maintenance verb, asserted per verb); load-then-swap with an injected mid-load
failure; the LOST verdict; upsert in place keeps id and links; a vanished file removes nothing;
backup rotation and each copy opening as a working store under a concurrent writer; restore
through the recovery wrapper; per-lane recall; rule promote / new version / retire; the job form
gate (anchor resolves — the tests' own workspace has JDT); rename carries anchors; area
summaries; the ledger's resume and re-queue on hash change; language absence and the staleness
scope; inline embedding, drained backfill, the per-row mark; the export→import round trip
byte-stable on the template; the re-derivation detector on fixtures with the token detector as
the control; the bands re-derived from the frozen pairs.

**Boundary-owned — tested per environment against its own contract (Rust unit tests):**
`dupgate::judge` (match / disposition / fail-open / kill switch); the stop rule for jobs, bounded;
the area memo fires once per session; the map block renders hits or "nothing"; the seat
materialisation of `cataloguer`.

**Only reality can verify — named smokes, not suites:** the four moments observed in a live
session (a script driving the resident and the hook binaries with recorded payloads, the transcript
as evidence — as `c9-frontdoor.sh` drove the 28d proofs); the restore click in studio; the first
bundle catalogued on the real workspace (the cost measurement); the live-search timing.

---

## 10. Migration — ordered, parity-gated, each step independently reversible (D-TWO)

Every step: `compile_workspace` 0 errors, the suite green captured to a file, and ONE mutation that
turns the step's own gate red. Steps that are refactorings use the named jawata operation; steps
that add code are authored inside a declared `jawata-author:` window.

| # | Step | Depends on | Gate that fails at HEAD | Mutation |
|---|---|---|---|---|
| M0 | **Measure**: live search over javadoc/job texts; the residue's precision on this workspace (S9 facts alone); freeze the pair sets for §8 | — | the three numbers on record in the plan | — (no code) |
| M1 | **`StoreBackups`** — `BACKUP TO`, 10 deep, path in the response; `restore` verb through the recovery wrapper; `delete` switches to it; `MemoryView` list + restore | — | a copy exists after each destructive verb and opens as a store; the 11th evicts | skip the copy on one verb → red |
| M2 | **Durable writes** — `upsertBySource` (in place); `load` removes nothing; `rename_symbol ExperienceTool#reseed → wipeAndImport` + the `KINDS` literal + skill/seat/stop texts; load-then-swap; `success=false` on removed > loaded; `substrateBlock` stops naming a file path as the add-one | M1 | the durability table green per verb; the interrupted load leaves the store unchanged; `reseed` refused as unknown | restore the pre-load delete → red |
| M3 | **Embedding** — bulk loaders drain before returning; `vectorise`; `stats.unembedded`; per-row mark; catalogue seeds `accepted`; ledger observed/derived | M1 | a loaded story recalled by meaning inside the same call chain; `awaitingReview` excludes the catalogue | skip the drain → red |
| M4 | **Language** — column 18 absence; staleness scoped to Java anchors | — | markdown with no language sits outside `java`; the sweep leaves it | restore the literal → red |
| M5 | **Lanes** — column + migration; `RecallQuery.lane`; `promote_rule` / `retire_rule` / versions; `derived_from` in `LINK_RELS`; per-lane, per-trigger sweep; `MemoryView` | M1 | a domain fact recalled without ranking against lessons; a rule shows its sources and version; the sweep reports per lane and trigger | drop the lane filter → red |
| M6 | **The story folder** — `StoryWriter` on accept; setting; `load` stamp→status; round trip; `/memorize` step 6, `/review`, the stop rule's text say `record` / `load` | M2, M3 | export→import round trip byte-stable; an unstamped file lands `candidate`; no shipped text names `reseed` | export a field the parser drops → red |
| M7 | **The code lane** — `job`/`area` forms; anchors live via `PointerResolver`; rename/move carries anchors; `CatalogueLedger` + `catalogue` verbs; `seats/cataloguer.md` + `/catalogue`; coverage; then **the first-bundle run** (cost measured before any sweep) | M5 | a job answers with its live file+line; a rename through jawata follows; a rename outside marks `location lost`; the ledger resumes and re-queues; an unread area answers "not described yet" | stop carrying anchors on rename → red |
| M8 | **Retrieval wired** — map at prompt; area on first Read; `dupgate.rs` (Block) + `duplicate_check` (draft parsed by JDT); stop rule for jobs; `ReDerivedJobDetector` + `CureCatalog` row; architect detect step reads the population | M7 | Rust: judge fixtures; engine: a draft re-deriving a fixture job is refused naming it, declared proceeds; the detector names the fixture populations and the token detector names none | disable the disposition token → red |
| M9 | **The interim measure** — the live-session smoke for the four moments; `reviewSweep` reports shown/chosen per trigger | M8 | the smoke's transcript shows each moment firing unprompted | remove one channel → the smoke names it |

Critical path: **M1 → M2 → M6** (the story path) and **M1 → M5 → M7 → M8 → M9** (the code lane); M3 and
M4 branch off M1 and run beside the rest. The long pole is M7. Nothing waits on the release of
another sprint; the seat's cron firing is 28g's, the burned-in role prompts are 28h's.

---

## 11. Decisions taken here, with the alternative each rejects

1. **One table, a `lane` column** — not a table per lane. Rejected: separate tables, because every verb, the form gate, the embedding and the sweep would fork (the second-lifecycle lesson). Cost: `lane` must be set by every writer; the migration derives it from `type` once.
2. **H2's online backup, not a raw file copy** — a raw copy of an open MVStore file is not guaranteed consistent; `BACKUP TO` is. Rejected: closing the store to copy it, which would drop attached peers.
3. **The cataloguer's memory is a table in the store, not a loose file** — same content as R5(b)'s "progress file" (unit, content stamp), one persistence, and a restored backup restores the ledger with it. If the loose file is preferred it is one class swapped; the seat's interface (`next`/`done`) does not change.
4. **The draft is parsed by the engine, not the hook** — the hook has no compiler; it forwards the path and the new text, the engine parses a working copy with JDT and asks the lane. Rejected: a regex over the payload — the regex mistake the hook crate was written to end.
5. **`dupgate.rs` is a copy of `recallgate.rs`'s shape, not a parameterisation** — two gates with one declaration lattice each stay readable; a generic gate with two token vocabularies is the abstraction nobody asked for. Ships in Block per the ruling, with the kill switch.
6. **The compiler-facts net is a detector, not rows** — it is recomputed from the workspace at query time and joined to jobs by anchor; storing it would be the derived-table-that-goes-stale the earlier spec had.
7. **The delete verb's JSON archive is replaced by the whole-store copy** — one undo artifact; the response still lists what was deleted.

## 12. Open for the plan (not decisions for Harald)

- The `job` form's "not a signature restatement" heuristic — plan-level; measured on the first bundle.
- Whether `duplicate_check` runs on `Edit` payloads whose `new_string` adds a method inside an existing class body — the working-copy parse handles it; the plan states the payload shapes covered.
- The area memo's key (package) when a file's package cannot be derived by the engine — answer "no area" and record nothing.
