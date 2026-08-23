# ARCHITECTURE — Sprint 28c rescue: anchor-independent experience

## Target

An experience is identified by its own UUID and retrieved from what it says, not from
where it was learned. A symbol, package, operation, snippet, or embodiment link is
optional provenance. Removing any of them cannot make the experience unreachable.

For an experience, the stored contract is:

- **principle** — the existing `summary`;
- **situation** — the condition under which the principle applies;
- **outcome** — `worked`, `failed_avoid`, or `unproven`;
- **provenance** — optional origin metadata, never a retrieval precondition.

Code references remain valid for code facts and as optional evidence on experiences.
They are not part of experience identity or admission.

## Picture

```text
 record / migrate / catalogue
             |
             v
 +-----------------------------+
 | Experience form             |
 | principle + situation       |
 | outcome + provenance?       |
 | code anchor? OPTIONAL       |
 +--------------+--------------+
                |
                v
 +-----------------------------+       +------------------------------+
 | H2 experience store, v10    |------>| Nominate                     |
 | existing entry columns      |       | words + embeddings rank only |
 +-----------------------------+       +---------------+--------------+
                                                        |
                                                        v
                                         +------------------------------+
                                         | ApplicabilityDecision port   |
                                         | agent reads situation +      |
                                         | principle; selects IDs or [] |
                                         +---------------+--------------+
                                                         |
                                                         v
                                              MATCH(entry) or ABSENCE
                                              never "nearest = answer"
```

## Storage and schema

The required storage shape already exists in the unpushed v10 work:
`experience_entry.situation`, `verdict`, `provenance_kind`, and `form`. The row UUID is
already independent of every code address. No new retrieval column or v11 rung is
needed.

v10 has not reached `origin/main` and the real ~2,480-entry store has not undergone its
form migration. Rewrite v10 now, before migration:

- keep the form columns needed above;
- keep all existing v1–v9 rungs unchanged;
- do not make symbol, package, operation, snippet, or embodiment non-null;
- remove the unshipped snippet, embodiment, and advice-event schema from the rescue
  baseline unless a separately approved sprint owns them.

The embedding/word documents for an experience must include both `situation` and
`principle`. Today the write path embeds summary/details, which makes the declared
situation invisible to nomination.

## Read ownership

`org.jawata.mcp.knowledge` owns one anchor-independent candidate contract and one
decision port.

1. **Existing deterministic retrieval stays for code facts.** Exact symbol, package,
   operation, and symptom containment continue to decide only the claims whose own
   contract declares those cues. They are not used to admit an anchorless experience.
2. **Nomination** takes a prose question and orders anchorless experiences from indexed
   `situation + principle`. It returns `query_id` plus candidates carrying
   `id + situation + principle + outcome`. It never returns `match`.
3. **`ApplicabilityDecision`** is the production boundary:
   `decide(query_id, selected_ids)` returns selected entries as `match`, or an empty
   selection as `absence`. The store validates that every selected id was nominated for
   that query. The architect seat is the first production consumer: it calls nominate,
   reads the candidates, and submits the decision. A scripted client cannot silently
   replace this judgement in the live acceptance proof.
4. The normal answer surface returns only `match` or `absence`. Raw nominees are
   available only through the explicit nomination operation, so an unrelated question
   can legitimately end at `absence` rather than dumping the nearest notes.

This is compatible with `AnalogyPolicy`: that class measured that no open-corpus
similarity threshold separates nonsense from answers. This design adds no threshold.
Similarity spends order, never authority. “No fitting answer” is the empty
`ApplicabilityDecision`.

## Catalogue ownership

The catalogue extractor and seeder are clients of the same form:

- every top-level pattern README becomes one `unproven` experience;
- `situation` comes from “When to Use”, `principle` from “Intent”, and consequences stay
  supporting content;
- the entry has no local symbol, package, operation, or required snippet;
- repository path and pinned commit are provenance only;
- seeding is idempotent by source identity and source hash;
- missing template sections stop bulk extraction and are manually curated in the
  committed snapshot; they do not become skips.

The corpus currently measures 187 top-level READMEs across a 194-project workspace.
All 187 measured top-level READMEs must produce entries. The extractor must not
manufacture 194 entries to satisfy a remembered approximation, and the seven
non-pattern workspace projects are not silently reclassified as pattern samples.

## End-state test surface

Environment-independent tests:

- record an experience with no symbol, package, operation, or snippet;
- nominate it from situation words that do not occur in its principle, then submit a
  fitting decision and retrieve the same UUID;
- anchor it optionally to fixture code, delete that code, run normal
  refresh/cleanup/reindex, then nominate and retrieve the same UUID without a code cue;
- similarity can nominate but cannot produce `match`;
- an empty decision for each of seven fixed unrelated questions produces `absence`.

Boundary tests:

- export/import preserves anchorless rows and their form fields;
- a v9 fixture upgrades to the rewritten v10 without losing rows;
- catalogue seed twice equals seed once;
- every source UUID, including both `migrated` and `legacy_kept` dispositions, receives
  a content-only probe; on a scratch migrated copy, clear all code anchors, run
  refresh/cleanup/reindex, and require every probe to return its original UUID.

Reality-only checks:

- the built product records and retrieves an anchorless experience through the
  nominate→decision JSON-RPC contract;
- a real architect-seat design run over the fixed fixture loads the catalogue, answers
  five design questions, and abstains on seven unrelated questions;
- seven unrelated questions return no fitting answer;
- only after those pass is the real store backed up, restored, and migrated;
- the 187 catalogue entries are seeded idempotently into the verified side-by-side
  migrated store, not left only in a throwaway test store.

## Migration from the current branch

1. Create and preserve ref `audit/sprint-28c-failed-784a43d` at commit `784a43d`.
2. Start the rescue branch from `origin/main`; do not reset or rewrite the evidence
   branch.
3. Reapply only the v10 form, write-path round trip, admission, and
   experience-survives-anchor-loss behavior.
4. Build anchor-independent retrieval and its abstention controls before any catalogue
   or real-store migration.
5. Extract and seed the catalogue against a throwaway store.
6. Back up, restore, and migrate the real store only after the built artifact passes
   the anchorless front-door gate; then seed all 187 catalogue entries into the
   side-by-side migrated store and prove a second seed changes nothing.

## Must not be touched

- released schema rungs v1–v9;
- code-fact address gating and exact symbol/package behavior;
- `AnalogyPolicy` floors or caps to fake relevance;
- H2 recovery, AUTO_SERVER, read-pool, and degraded-store honesty;
- the user's live store before the restore drill and explicit migration confirmation;
- the pattern fork's content;
- `ToolRegistry` choke wiring, `meta.steering`, snippets, embodiment guards, advice
  journaling, and refactoring-engine integration in this rescue.

Those last capabilities do not follow from the four authoritative requirement
sentences. They can be reconsidered in Sprint 28f after the store itself works.

## Addendum — D5–D8 (Sprint 28c merge)

Scope: this section covers D5–D8 only. Everything above it (D0–D4) stands unchanged,
and nothing here contradicts it. Three bindings from GATE 1 (spec audit trail,
2026-08-23) are carried in as given and are not re-opened here:

- **R5 → one mechanism.** Updating IS re-loading at a newer pinned snapshot. There is
  no standing fork-sync pipeline in this design.
- **R6 → the seat consults when it runs.** The automatic trigger stays in 28f, so
  `ToolRegistry` choke wiring, `meta.steering`, snippets, embodiment guards and advice
  journaling **remain on the "Must not be touched" list above, unchanged**. This
  addendum does not lift them; D4b's conditional clause ("lifts … only if D7's
  unprompted trigger is ruled in") did not fire.
- **R7 → no labels.** No advisory/performable field appears anywhere below.

### The one target

**Catalogue knowledge enters the store through the ingest identity the store already
has, and every consumer asks the store its whole question once. Nothing new stands
between a caller and the store.**

Everything in D5–D8 is one of two roles against that sentence: a *client of the
existing write port* (D5, D6) or a *caller that stops truncating its own question*
(D7, D8). No new port, no new column, no new schema rung, no second retrieval
pipeline. The patterns named below are named in service of that one target:

| Seam | Pattern, in service of the target |
|---|---|
| D5 loader | **Composed Method** on the start-up sequence — `openRealStore` names its tasks and performs none of them inline |
| D5/D6 identity | **Idempotent Receiver**, reusing `source_ref` + `source_hash` rather than a marker file |
| D6 successor | **Immutable value + successor link**, reusing `supersedes` from the existing link vocabulary |
| D7 entry payload | **Self-describing message** — the entry carries what the reader must cite, so the reader never reaches back |
| D8 recall | **Whole Value** — the cue set travels as one value; the caller stops choosing on the store's behalf |

### Picture

```text
 -- jawata-mcp (org.jawata.mcp) -------------------------------------------------
                                                                     dependency
   JawataApplication#openRealStore  (:652)                            direction
        |            |                                                    |
        |            +--> H2ExperienceStore#recoverOrphans  (:1458)        |
        |  (D5 wire)                                                       v
        +--> PatternCatalogueLoader#load(ExperienceStore)   [NEW]
                 |  reads  /catalogue/patterns-<commit>.json  [NEW resource]
                 |  writes ONLY provenance_kind='catalog' rows
                 v
        +--------------------------------------------------+
        | ExperienceStore  (the EXISTING write port)        |
        |   sourceUnchanged(sourceRef, sourceHash)   (:62)  |  <- D5 idempotence
        |   putWithSource(entry, sourceRef, hash)    (:56)  |  <- D5/D6 write
        |   addLink("supersedes", <older id>)               |  <- D6 successor
        |   status = ExperienceEntry.CANDIDATE       (:18)  |  <- D6 curation
        +----------------------+---------------------------+
                               |                    ^
                               | (read)             | (report accessor)
                               v                    |
        +--------------------------------------+    |
        | ExperienceRetrieval                  |    |
        |   query(RecallQuery)  H2:863..918    |    |
        |   fits(...)           ER:1091..1134  |    |  D8: the disjunction and
        |   ONE sort            ER:311..318    |    |  the single sort already
        +----------------+---------------------+    |  exist -- widen the VALUE
                         |                          |
                         v                          |
        +-------------------------------------------+------------------+
        | ExperienceTool  (the front door)                              |
        |   recall  (:714)  nominate (:747)  decide (:780)  stats (:391)|
        |   list(status=...) (:504)                                     |
        +--------------------+------------------------------------------+
                             | JSON-RPC
 -- jawata-studio -----------+---------------------------------------------------
                             |
        jawata-hook pipeline.rs::recall (:423)  <- D8 hook half: one ask, all cues
        seats/architect.md D-FOUR (:56)         <- D7 stance
        conductor.rs (:261..278) renders ~/.claude/skills/refactor/SKILL.md
        runner.rs Ceilings (:49) + ClaudeCodeAdapter.max_turns (:703) <- D7/R9
```

Arrows point the way the dependency points. Nothing in `org.jawata.mcp.knowledge`
learns about the catalogue: `PatternCatalogueLoader` depends on `ExperienceStore`,
never the reverse.

### Seam 1 — the loader (D5)

**Where it hangs.** `JawataApplication#openRealStore` (`JawataApplication.java:652`) is
the one method that opens the real store and then performs a start-up task on it:
`store.recoverOrphans(workspaceRoot)` at `:668`. The catalogue load is the second such
task and goes beside it, on the same `H2ExperienceStore` instance `:653–658` opened.

**Why exactly there and not one frame up.** `openExperienceStore` (`:629`) wraps a
failed open in `RecoveringExperienceStore` (`:644`), which serves an in-memory store
while it retries. Loading 187 catalogue rows into that fallback would recreate the
incident its own comment records at `:638–639` — "the 2026-07-19 fleet flip served 367
seed entries as if they were the DB". Hanging the loader inside `openRealStore` means a
degraded store is never seeded.

**This loader IS D3's "seeder"** — "Catalogue ownership" above assigns *"the catalogue
 extractor and seeder"*, and naming them separately would leave room for two writers of
catalogue rows with different identity keys, which would silently break both the
loads-nothing-again rule and the successor rule. One extractor (M0, offline), one writer
(this loader).

**Identity key.** Source reference plus content hash, using the two methods that exist
for exactly this and are already forwarded by the decorator:
`ExperienceStore#sourceUnchanged(sourceRef, sourceHash)` (`ExperienceStore.java:62`,
implemented `H2ExperienceStore.java:551–565`) and
`ExperienceStore#putWithSource(entry, sourceRef, sourceHash)` (`ExperienceStore.java:56`,
implemented `H2ExperienceStore.java:545`). Per-pattern `sourceRef` is
**`catalogue:java-design-patterns/<slug>/README.md` — carrying NO commit**; `sourceHash`
is the SHA-256 of that README's extracted record.

**The commit is provenance, never identity, and getting that wrong would have made D6
impossible.** An earlier draft wrote `...@<pinned-commit>/<slug>/README.md`.
`sourceUnchanged` is `WHERE source_ref = ? AND source_hash = ?`
(`H2ExperienceStore.java:551-565`), so a ref containing the commit changes for EVERY
pattern at a new snapshot, no stored row matches any new ref, and Seam 2's table below
would classify all 187 as "absent -> insert, report as added". Rows 2, 3 and 4 of that
table — the no-op, the successor, the upstream retirement — would have been unreachable
by construction, and D6 would have failed on a design defect rather than on code. The
slug is the pattern's stable identity across snapshots; the commit lives in `details` and
in the report.

**The read that supplies the older row's id, named — because `sourceUnchanged` cannot.**
It returns a boolean, and no lookup by `source_ref` exists on the port. The loader makes
**one `store.all()` pass at start** over the rows whose `provenance_kind` is `catalog`.

**`source_ref` is NOT unique, and the design must not pretend it is.** Row 3 below inserts
a successor carrying the SAME `source_ref` and keeps the old row (`insert` at
`H2ExperienceStore.java:567` is a bare INSERT with no delete), so after one supersede two
rows share the ref — and after two, three. A single-valued `sourceRef -> id` map would
hold an arbitrary one of them, and "hash equal -> no-op" could miss, writing a fresh
successor at EVERY server start. That is the first draft's unreachability defect in
mirror image, so the two reads are separated deliberately:

- **the no-op decision uses `sourceUnchanged(ref, hash)`** — set membership over
  (ref, hash), immune to how many rows share the ref: if ANY catalogue row already holds
  this exact content, there is nothing to do;
- **the id for the successor link comes from the pass**, which keeps per `sourceRef` the
  CHAIN HEAD — the row no other catalogue row `supersedes`. A successor always links to
  the current head, never to a superseded ancestor.

So: `sourceUnchanged` true -> no-op; false and the ref is known -> insert a successor
linked to that ref's head; ref unknown -> insert; a stored ref missing from the snapshot
-> retired upstream. This adds no port method and
no column, so the target sentence stays true, and the cost is measured rather than
assumed: `store.all()` over 2,500 rows is **25 ms** (Stage 1's clause-6d measurement) —
one traversal per start, beside the orphan sweep already running there.

**Deliberately NOT the `.jawata-recovered` marker idiom** (`H2ExperienceStore.java:1481`,
`:1506`): that marker means "swept, never look again", which is right for a one-time
orphan import and wrong here, because D6 requires the same source to be looked at again
at a newer snapshot and compared. Marker semantics would make D6 impossible. Source-hash
semantics give a per-pattern no-op on re-run AND a per-pattern change signal at the next
snapshot — one mechanism serving both D5's "loads nothing again" and D6's "adds the new
patterns".

**Namespace isolation.** `provenance_kind` already exists (`SchemaMigrations.java:538`,
v10) and its vocabulary is already documented as FIVE values —
`recorded / ingested / catalog / seat_run / migrated` (`ExperienceEntry.java:44`) — of
which `"recorded"` (`ExperienceTool.java:899`) and `"ingested"`
(`ExperienceMaintenance.java:340`, `:401`) have writers today. **Catalogue rows take the
already-documented `"catalog"`** — not a new sixth spelling. This addendum's first draft
said "a third value, `catalogue`", which was wrong twice: the vocabulary is not three
long, and a second spelling of a documented term is the drift `EntryForm`'s own javadoc
condemns. No new column, no v11 rung. Isolation becomes a
property a test can assert: the loader's write set is exactly the rows whose
`provenance_kind = 'catalog'`, and it issues no `UPDATE`, `setStatus` or
`deleteBySource` against any other row. R8's live-store concern is met the way
`recoverOrphans` meets it — `synchronized` per-entry inserts through the same connection
discipline (`H2ExperienceStore.java:567`), never a table-level operation.

**The snapshot artifact** ships in the bundle exactly as the embedder's model does
(`MiniLmEmbedder.java:34`, `:151`, reading `/embed/model-f16.safetensors` from
`org.jawata.mcp/resources/embed/`): `org.jawata.mcp/resources/catalogue/patterns-<commit>.json`,
with a sidecar recording the pinned commit, the fork's MIT licence verdict and the
extraction date. "A newer jawata carries a newer snapshot" is then a property of the
build, not of a network fetch.

**Start-up report — the surface, assigned (D4b's open question).** Two surfaces, one
report object, because the readers differ:

1. **A log line at start**, mirroring `recoverOrphans`'s own
   (`H2ExperienceStore.java:1514–1516`, which logs only when something happened). Same
   rule: a start that loaded nothing logs nothing.
2. **`experience(kind=stats)`**, under a new `catalogue` block. `stats()` is already the
   composed diagnostic surface (`ExperienceTool.java:391–431`, adding `quality` and
   `embedding` the same way), and the block degrades in the style already established
   there (`:419–422`: `available:false` plus a reason, never a misleading zero).

The report is reviewable rather than merely countable because it names the review query
instead of duplicating it: candidates are already enumerable through
`experience(kind=list, status="candidate")` (`ExperienceTool.java:504`, passing `status`
at `:508` into `ExperienceStore#listEntries`, `ExperienceStore.java:136`). The block
carries counts, the snapshot commit, the per-pattern change list for D6, and that query
string.

**Smell prevented.** *Divergent change* in `openRealStore` — a method that opens a
store, stamps provenance, sweeps orphans and parses a catalogue changes for four
unrelated reasons.

**Production caller.** `org.jawata.mcp.JawataApplication#openRealStore`
(`JawataApplication.java:652`, main source root `org.jawata.mcp/src`), one statement
after `:668`. This is the point of the seam: `build/unwired-baseline.txt` already lists
`ExperienceMaintenance#load(Path)` as a capability with only test callers, and the
loader must not become the second. Verified by
`get_call_hierarchy(direction=incoming, symbol="…PatternCatalogueLoader#load")`
returning a non-test caller, and `build/unwired-gate.sh` staying green **with no
baseline line added**.

### Seam 2 — the update (D6)

Same loader, same call site, same identity key. "Updating" is not a second code path; it
is what the loader does when `sourceUnchanged` says *changed*. Four outcomes per
snapshot pattern, exhaustive:

| Snapshot vs store | Loader action |
|---|---|
| source_ref absent | insert, `status = CANDIDATE`, report as **added** |
| source_ref present, hash equal | nothing at all — no write, no touch |
| source_ref present, hash differs | insert a NEW row (new UUID, new hash), `status = CANDIDATE`, link to the old row, report as **successor** |
| source_ref present, absent from snapshot | nothing at all — the row stays, reported as **retired upstream** |

**The successor link — reused, not invented.** `EntryForm.LINK_RELS`
(`EntryForm.java:107–111`) is the derived, closed vocabulary: `handled_by, fixed_by,
detected_by, supersedes, cured_by, related, undo`. **`supersedes` is the relation,
written on the NEW row pointing at the older one**, through the existing
`ExperienceEntry.Builder#addLink(rel, target)` (`ExperienceEntry.java:218`; the `Link`
record at `:50`). Adding a `successor` rel would make the catalogue the only writer of an
eighth relation and re-open exactly the defect that constant's javadoc records — a
vocabulary "documented in one schema string and enforced nowhere" (`EntryForm.java:87–91`).

Direction is fixed: **the newer entry declares `supersedes` → older id.** The older row
is never written to, which is what makes "a pattern's earned record stays bound to the
version it was earned on" true in the data rather than only in prose — its `status`,
`verdict` and any user edit are untouched by construction, because the loader's only verb
against an existing catalogue row is *read the hash*.

**Curation.** `status = ExperienceEntry.CANDIDATE` (`ExperienceEntry.java:18`; the builder
already defaults to it at `:165`). `ACCEPTED` (`:19`) is what a human later promotes to
through `experience(kind=promote)` (`ExperienceTool.java:369`). Nothing is auto-applied
because the loader never calls `setStatus`.

**A user-edited catalogue row is not reverted**, and this falls out of the same rule: an
edit changes the body but not the `source_hash`, so at the same snapshot the loader
writes nothing; at a newer snapshot the edited row is superseded rather than overwritten,
and the human sees both in the candidate list.

**The one-mechanism property is CHECKED, not merely designed.** R5 binds the design to a
single update path, and nothing in the first draft would fail if a second catalogue
writer or a fetch path were added later. The BOUNDARY tier asserts it by call
hierarchy — a loaded workspace is needed, so it cannot be environment-independent — in
the style the wiring gate already uses: **the only production caller of the catalogue
write path is `PatternCatalogueLoader#load`, and it is reached only from
`JawataApplication#openRealStore`, through exactly one extracted method after M4.** What
a call hierarchy does NOT prove is that nothing else writes `provenance_kind='catalog'`,
which is the property R5 actually binds — so the boundary tier asserts the write set
directly as well.

**Smell prevented.** *Speculative generality* — R5's ruling in code form: one mechanism
whose update behaviour is a branch on a hash it already computes, rather than a second
pipeline with its own scheduling, failure modes and tests, for a corpus that gains about
three patterns in a busy month.

**Production caller.** Identical to D5 — `JawataApplication#openRealStore:652`. The
report accessor's production caller is `ExperienceTool#stats` (`:391`), reached from the
`stats` verb at `:376`. D6 adds no capability needing its own wire; it adds branches to
one that has one.

### Seam 3 — the consultation (D7)

The seat's stance is committed: `seats/architect.md:56–71` (D-FOUR, studio `3dc39a6`)
tells design mode to call `experience(kind=nominate)` in prose, read each candidate's
situation, then `experience(kind=decide)` with the ids that apply — and that selecting
nothing is a real answer. The engine side exists: `ExperienceTool#nominate` (`:747–769`)
and `#decide` (`:780–790`).

D7 adds **not a new call** but what the catalogue entry must CARRY so the seat can cite
intent, consequences and a resolving address without reaching back into the fork:

| The seat must cite | Stored as | Existing carrier |
|---|---|---|
| intent | `summary` (the principle) | the form's principle field |
| when it applies | `situation` | `SchemaMigrations.java:536`, v10 column |
| consequences | `details`, a labelled section | `SymbolFact.Builder#details` (`SymbolFact.java:142`), emitted at `:80–81` |
| canonical address | `details`, as text | the same labelled block |
| repository path | `source_ref` | the loader's identity key (slug only, no commit) |
| the pinned commit | `details` | the provenance block, never the identity key |
| how it turned out | `verdict = "unproven"` | `EntryForm.VERDICTS` (`EntryForm.java:41`) |

**No new column is justified, and the address is the check that settles it — with one
correction to this addendum's first draft, made at GATE 2.** The first draft took the
resolving address to be a pattern's Java package in the `patterns` workspace — `com.iluwatar.circuitbreaker`,
`com.iluwatar.strategy` (verified in the pinned fork, `circuit-breaker` App at line 25).
The first draft put it in the indexed `package_name` column, because jawata can resolve
that directly. **That was wrong and is retracted.** The signed spec's acceptance
statement is absolute:

> *"The sprint fails if any acceptance proof supplies a symbol, package, operation, or
> snippet to make the expected experience reachable."*

and D3's measure requires all 187 rows to have *"empty symbol, package, operation, and
snippet fields"*, which "Catalogue ownership" above states in substance. A design
that writes `package_name` fails a signed measure and makes catalogue rows reachable by
an ordinary package cue — the precise thing this sprint exists to stop. Convenience for
the reader is not a reason to weaken the one requirement the sprint is named after.

**So the address is TEXT in `details`, beside intent and consequences** — and it names a
TYPE, not a package, because that is what resolves. Measured against the live `patterns`
workspace at GATE 2: `search_symbols(query="com.iluwatar.circuitbreaker*")` returns **0
results**, while `search_symbols(query="DefaultCircuitBreaker")` returns the file.
`search_symbols` matches symbol names, not package strings. The first correction to this
paragraph said a package string "resolves exactly as an indexed value would"; that was
false and is itself corrected here.

The line therefore reads `Reference implementation:
com.iluwatar.circuitbreaker.DefaultCircuitBreaker` — a fully-qualified TYPE, which every
reading tool accepts as a stable key and which opens the file.

**This is also how D5's "the pattern's Java package … as provenance" is discharged**, and
saying so is not decoration: a reader of the retraction alone cannot tell whether that
clause was met or dropped. The fully-qualified type CONTAINS the package as its prefix,
so the provenance D5 asks for is carried, in text, with no anchor column written. The
env-independent tier asserts it: the `details` line's package prefix equals the pattern's
own Java package. The seat loses nothing:
resolution is a tool call either way, and the tool takes a name. Every anchor column stays
empty, and D3's measure and the migration's "every anchor field empty" agree rather than
contradict.

**Consequences are prose, not a field**: `details` under a fixed heading the extractor
writes, taken unparaphrased from the README's "Benefits and Trade-offs" section (present
in the fork's template — `circuit-breaker/README.md:199`, beside "When to Use" at `:183`
and Intent at `:18`), with the MIT attribution in the same block. `details` already
round-trips through `body_json` and is already rendered to the caller.

**Tool-step ceiling (R9), located.** D7 requires a design-mode run that exceeds its
tool-step ceiling to stop and say so. Today it cannot:

- `Ceilings` (`runner.rs:49`) carries `wall_ttl_secs`, `max_iterations`, `cost_budget_usd` only
  (`jawata-studio/src-tauri/src/runner.rs:51–63`); `max_iterations` counts passes of the
  `run_seat` DETECT loop (`:1059–1064`), not tool steps.
- The tool-step bound is the adapter's `--max-turns`, defaulted to 12 in code
  (`runner.rs:703–711`, passed at `:729–730`) and **not settable from a seat definition**:
  the frontmatter parser accepts `name, model, effort, schedule, tools, gates, ttl_secs,
  max_iterations, cost_budget_usd` (the accept-list starts at `:179`) and hard-errors on
  any other key (the `other =>` arm, `:207`).
- When the CLI does stop on turns, `parse_text` reads the `result` event's `result` string
  and ignores its subtype (`:745–749`), so a turn-exhausted run is indistinguishable from
  a finished one — it stops, but does not say so.

The design: `Ceilings` gains `max_tool_steps`; the frontmatter parser gains the key beside
the other three; `ClaudeCodeAdapter.max_turns` is built from it instead of its default;
the adapter's result parsing distinguishes the turn-limit subtype so `run_seat` emits
`Verdict::Reaped` with a `CeilingKind` (`:68–82`) — **which is a closed three-variant enum
today (`WallTtl`, `MaxIterations`, `CostBudget`, `runner.rs:71–75`), so M10 adds a fourth,
`ToolSteps`**; without it the run stops without being able to name which ceiling. Entirely studio-side.

**Production callers:** `run_seat` (`runner.rs:1059–1064`) reads the ceiling and
`build_command` (`:722–735`) passes it to the CLI. **This is Rust and therefore OUTSIDE
`build/unwired-gate.sh`**, which sweeps the Java repository only — the same declaration
Seam 4 makes, for the same reason: a green Java gate is not evidence about this
capability and must never be reported as if it were.

**The seat's two modes, which the first draft left out entirely.** D7 carries four
clauses of Harald's 2026-08-21 ruling and this addendum addressed one. The other three:

- **Design mode uses tools.** `seats/architect.md:7` declares `tools: []`, while the same
  file's rule 5(a) (`:99`) demands *"DERIVE the consumer set. Never state it from
  memory"* and D-FOUR requires two `experience(…)` calls — a declaration that contradicts
  the seat's own instructions. Measured twice, and the second measurement changes the fix. First: the field is
  **inert** — `SeatDefinition.tools` is parsed at `runner.rs:183`/`:224` and read only
  inside `#[cfg(test)]` (`runner.rs:2459`); no `--allowedTools` flag is ever built.
  Second: **the key is never deployed at all** — `render_claude_skill`
  (`conductor.rs:261-278`) emits `name`, `description`, the lane-1 contract and the
  seat's `stance` BODY; `tools:` is frontmatter and is not rendered, which the live
  artefact confirms (`~/.claude/skills/refactor/SKILL.md` carries `name` and
  `description` only). Setting it would fix nothing and could not be checked. **M9b
  instead states the rule where agents actually read it — a sentence in the stance
  body**, which is rendered verbatim, and the boundary tier asserts the deployed file
  carries it. Changing `tools:` is deliberately left out: it needs a reader first, and
  naming that reader is 28f's seat work.
- **Watch mode consults and does not re-derive**, and a watch-mode "no" is a DECISION that
  stops for the human's word. The deployed WATCH MODE block (`seats/architect.md:73–76`)
  carries "read detector evidence and reviewed diffs, and argue for DESIGN-level fixes"
  plus the target-architecture comparison, but neither the consult-without-re-deriving
  rule nor the stops-for-his-word rule. M9b adds both clauses there.
- **The tool-less rule moves to the text-reading roles** (sprint/plan auditor,
  communicator) — **NOT in this sprint**: the spec's own Deferred section homes it to
  28f. Recorded so a reader can tell deferred from overlooked, the same way R6 is.

**Deployment, which is the measure that bites.** D7's measure names the *deployed*
`~/.claude/skills/refactor/SKILL.md`, not the source. That file is generated from
`seats/architect.md` by `conductor.rs` (`render_claude_skill`, `:261–278`; embed table `:16–23`; the
"every `seats/*.md` on disk is embedded" invariant `:593–612`). The D7 step is done when
a redeploy has regenerated the skill and the regenerated file carries the text.

D7 names TWO firing points — `/refactor` and the sprint design step — and they reach the
stance the SAME way, which is why one deployed file carries it. `/sprint` is not rendered
from the seat embed table at all: `EMBEDDED_SEATS` (`conductor.rs:15–24`) carries the
eight `seats/*.md` only, and its neighbouring `COMMAND_MAP` doc records that the
spec-editor/spec-auditor pair "live in /sprint and render NO command"
(`conductor.rs:27–28`); `/sprint` comes from `UTILITY_MAP` (`:360–363`) with its body
`include_str!("../../skills/sprint.md")` (`:384`). The sprint design step INVOKES the
architect seat rather than restating its stance, so the seat's own deployed skill is the
single artefact that must carry it — and the boundary tier checks that one file. An
earlier draft claimed both were rendered from the embed table and that both were checked;
both halves were false.

**Smell prevented.** *Feature envy* / *message chains* — an entry that did not carry
intent, consequences and address would force the seat to hold a store row and then go to
the fork for the rest.

**Production caller.** The payload is rendered by the production read path:
`ExperienceRetrieval#answerFor(decision)` called at `ExperienceTool.java:789` inside
`decide`; candidate maps built at `:760–768` inside `nominate`. Both front-door verbs
(`:362–363`). The seat is the consumer, over JSON-RPC.

### Seam 4 — the multi-cue recall (D8)

**Verdict first: the store side already merges several cues correctly and needs ONE
change — the ability to carry more than one cue of the same kind. The hook side is where
cues are thrown away.**

What the store already does with several cues set at once:

- `H2ExperienceStore#query(RecallQuery)` (`:863`) builds one clause per set cue — symbol
  `:869–883`, package `:884–891`, operation `:892–895`, external system `:896–899`,
  symptom `:900–916` — and joins them with `String.join(" OR ", clauses)` at `:917–918`.
  Candidates are already a union.
- `ExperienceRetrieval#fits` (`:1091–1134`) evaluates all four subject criteria eagerly
  and admits on the disjunction `symbolOk || packageOk || symptomOk || externalOk`
  (`:1105–1124`).
- The merged set is ranked **once**, by one comparator chain — specificity › member
  affinity › confidence › meaning band › recency (`:311–318`) — after one semantic scan
  serving both tie-breaking and analogy nomination (`:230`).

So "the store ranks the merged result once" is, for one symbol plus one symptom, already
true. Nothing in the ranking, the fit gate or the SQL needs redesigning, and D8 must not
touch them.

**What the hook throws away.** `pipeline.rs::recall` (`:423`) builds `cues.symbols` and
`cues.symptoms`, both `Vec<String>` (`cue.rs:35–47`), then chains them into ONE iterator
and issues a **separate single-key ask per cue**, returning on the first that answers
(`:437–460`; the comment at `:432–435` states the rule outright). A prompt naming a class
and describing a problem asks `{kind:recall, symbol:"Foo"}`, gets an answer, and never
asks the symptom.

**Why the hook cannot merge on its own side.** Two asks produce two independently ranked
lists; concatenating them is a second ranking authority, which the contract forbids. And
the cues cannot be flattened into today's scalar fields: the symptom clause tokenises its
single string and ANDs the tokens (`H2ExperienceStore.java:903–915`), so joining two
phrases would require every word of both to match — strictly narrower than either alone.

**The contract:**

- *Hook sends*, in ONE `experience(kind=recall)` call: the existing scalar `symbol` and
  `symptom` keys set to the highest-priority cue of each kind, **plus** new
  `symbols: [...]` and `symptoms: [...]` arrays carrying the complete sets. **Both names
  already exist on `kind=record` with a different meaning** (`ExperienceTool.java:859`,
  `:868`, `:887` — there they are an entry's own scope and its recorded symptoms). Reusing
  them on `kind=recall` is deliberate — a cue list is what a reader expects under those
  names — and the schema documents the per-kind meaning so the overload is stated rather
  than discovered.
- *Store does*: reads the arrays where present, unions them into the same clause builder
  (one clause per member, same `OR` at `:917–918`), the same `fits` disjunction, the same
  single sort. A store that does not know the arrays reads the scalars and behaves
  **exactly as today**.

**No `HOOK_CONTRACT` bump.** The version is `1` (`field.rs:24`), echoed under
`X-Jawata-Contract` (`FieldContract.java:16`) and compared for exact equality
(`query.rs:252–255`). A bump would break injection against older stores — precisely: an ABSENT echo proceeds
unverified (`query.rs:248–250`), so only a present-but-different value refuses, which is
exactly what a bump would produce. Sending
scalars *and* arrays makes the new hook's worst case against an old store identical to
today rather than silence — which is why the scalars stay.

**One more thing the widened record must answer:** `RecallQuery#isEmpty()` short-circuits
`query` (`H2ExperienceStore.java:864`) and today asks only about the five scalars, so a
query carrying ONLY the new arrays would return an absence. The hook always sends the
scalars too, so no live call hits it — but the record's emptiness rule must count the
arrays, or the widening holds only by the caller's good manners.

**Store-side change, plainly — and it is THREE places, not two.** `RecallQuery`
(`RecallQuery.java:15–16`, a five-component record) gains two list components; `query`
and `fits` iterate them; **and `ExperienceTool#recall` (`:714`) must READ the new
arguments into them.** That third piece is not optional bookkeeping: `recall` is the only
place JSON args become a `RecallQuery` (`:715–720`), so leaving it unchanged means the
hook's arrays arrive over the wire and are silently dropped, and the new components are
populated only by a test constructing `RecallQuery` directly. That is precisely the
"capability whose callers are all test code" shape D4b's measure forbids — and the shape
D8 itself exists to end. The helper is already in the file: `strings(args, …)` at
`:1001`, used by `decide` at `:782`.

**Smell prevented.** *Middle man* — the hook currently decides, on the store's behalf,
which part of the question is worth asking, while holding strictly less information than
the store does.

**Production callers.** Store half: `ExperienceTool#recall` (`:714`, constructing the
`RecallQuery` at `:715–720`, dispatched at `:361`). Hook half: `pipeline.rs::recall`
(`:423`) — Rust, and therefore **outside** `build/unwired-gate.sh`, which sweeps the Java
repository against the built dist. The gate proves the Java half is wired; the hook half
is proved only by D8's own measure, a real client session on the installed hook. Saying so
here is the point: a green Java gate is not evidence about the Rust side and must never be
reported as if it were.

### Migration — ordered, each step independently verifiable and reversible

`compile_workspace` + `get_diagnostics` at 0 errors gates every Java step;
`refactoring(action=undo, undoChangeId=…)` reverses every tool-applied step, `git revert`
of the single step commit every authored one. **A revert is not complete until the
artifact is rebuilt and the revert is verified IN THE BYTECODE** — this branch lost hours
to a control that was reverted in source and left in the jar.

**M0 — the extractor, and which side of the gate it sits on.** *Authored, new class.*
`org.jawata.mcp.catalog.CatalogExtractor`, in the **test/build source root, not the
product** — it runs offline against the pinned fork to PRODUCE the snapshot, and no
shipped code calls it. Stating the side is the point: an extractor authored into the
product with no production caller is exactly the hollow shape `build/unwired-gate.sh`
exists to catch, and `build/unwired-baseline.txt` already carries one such member.
**The extractor composes the record; the loader WRITES what the extractor produced and
composes nothing** — the first draft assigned that job twice and left neither owning it.
The LOADER (M2) is D3's "seeder"; this extractor only produces the snapshot — one
catalogue writer, not two, which is
what makes D5's "loads nothing again" and D6's successor rule provable at all.
Verify: run it at the pinned sha and diff the snapshot against the committed one — byte
identical. Reverse: delete the class; the snapshot is already committed.

**M1 — freeze the snapshot artifact.** *Authored, new files.*
`org.jawata.mcp/resources/catalogue/patterns-<commit>.json` + sidecar (pinned commit, MIT
licence verdict, extraction date). **D5 says "each ENTRY carries … the fork's licence
verdict", and the sidecar is the snapshot, not the entry** — so the verdict is written
onto every record too, in the same `details` provenance block as the attribution. One
fork, one verdict, and per-entry is what the sentence says. Verify: 187 records; a probe that
`getResourceAsStream("/catalogue/…")` finds it from the built jar as
`MiniLmEmbedder.java:151` finds the model. Reverse: delete both.

**M2 — author the loader, unwired.** *Authored, new class.*
`org.jawata.mcp.knowledge.PatternCatalogueLoader#load(ExperienceStore)` returning a report
record, using only `sourceUnchanged` and `putWithSource`. **Sample before bulk** (D5's own
requirement): a bounded-count mode, so M3's first verification loads one pattern and reads
it back before the full snapshot is enabled. Verify: `compile_workspace` 0/0; a
memory-store test seeds twice with no second-run additions. Reverse: delete the class.

**M3 — wire the single production call.** *Authored, one statement* in
`JawataApplication#openRealStore` after `:668`. One line inside an existing method is not
a refactoring; no tool kind applies. Verify: `compile_workspace` 0/0;
`get_call_hierarchy(direction="incoming", symbol="…PatternCatalogueLoader#load")` names a
production caller in `org.jawata.mcp/src`, not a test — `openRealStore` before M4, the
extracted method after it, and `openRealStore` transitively in both cases;
`build/unwired-gate.sh` exits 0 with `git diff --exit-code build/unwired-baseline.txt`
clean. Reverse: remove the line.

**M4 — compose the start-up sequence.** *Tool.*
`refactor_to_pattern(kind="compose_method", filePath=<JawataApplication.java>,
sections=[…])` — the range-targeted kinds take `filePath` plus zero-based `sections`
coordinates; `symbol=` addresses `form_template_method` and will not target this one.
The sections are the start-up-task statements.

**This step MOVES the loader's direct caller, and the assertion must move with it.** After
composing, `PatternCatalogueLoader#load` is called from the extracted method (say
`runStartupTasks`), not from `openRealStore` directly — so "it is reached only from
`JawataApplication#openRealStore`" becomes false as a DIRECT claim and true as a
transitive one. Every statement of it below is written transitively for this reason:
**reached only from `JawataApplication#openRealStore`**, through exactly one extracted
method. Verify: `compile_workspace` 0/0, and the incoming call hierarchy of
`PatternCatalogueLoader#load` names `runStartupTasks`, whose own incoming hierarchy names
`openRealStore` — both in `org.jawata.mcp/src`, neither a test.
Reverse: `refactoring(action="undo")`.

**M5 — the report surface.** *Authored, one block* in `ExperienceTool#stats`
(`:391–431`), beside `quality` (`:396`) and `embedding` (`:423`), degrading as `:419–422`
does. Verify: through the built dist over JSON-RPC, `experience(kind=stats)` returns the
block naming the `experience(kind=list, status="candidate")` review query. Reverse: revert.

> **Release 2 fence.** D5 complete. Before the release word: the D2 front-door check (five
> positive, seven control questions) on the installed build, plus D5's own
> installed-product observation. The migrated real store carries the catalogue per D3.

**M6 — the update branches.** *Authored, inside the M2 class.* The changed-hash branch:
new row, `status = CANDIDATE`, `addLink("supersedes", olderId)`. No new rel, no schema
change, no write to the older row. Verify: the snapshot-N-then-N+1 boundary test — added
rows appear, one successor per changed pattern, zero updates to existing rows, zero
deletions; loading N+1 twice adds nothing. Reverse: revert; M1–M5 keep working.

**M7 — widen the recall value, AND the entry point that fills it.** *Tool, then authored.*
`change_method_signature` on `RecallQuery`'s canonical constructor to add
`List<String> symbols` and `List<String> symptoms`, **keeping a five-argument convenience
constructor** so existing construction sites and every test compile unchanged — **except
`ExperienceTool#recall` (`:714`), which is deliberately changed** to read
`strings(args, "symbols")` and `strings(args, "symptoms")` into the widened constructor.
A convenience constructor that quietly keeps the ONE production entry point on the old
five is how this capability would ship inert. Then `compile_workspace(clean=true)` — a record's canonical constructor shape
change is precisely the case where the incremental builder skips recompiling consumers and
a plain incremental build can report a false 0/0. Then author `RecallQuery#isEmpty()` to count the arrays — without it an
arrays-only query short-circuits to absence at `H2ExperienceStore.java:864` — and the
two iterations: the clause loop in `H2ExperienceStore#query` (`:869–916`, joined `:917–918`) and the
disjunction in `ExperienceRetrieval#fits` (`:1105–1124`). **Do not touch** the comparator
chain at `:311–318` or the fit gate's semantics. Verify: `compile_workspace(clean=true)`
0/0; a test proving two symptom cues are a union, not an AND. Reverse:
`refactoring(action="undo")` for the signature, revert for the loops.

**M8 — stop the hook truncating.** *Authored, Rust.* `pipeline.rs::recall` (`:423–473`):
replace the first-answer-wins loop (`:437–460`) with one ask carrying the scalar best cues
plus the arrays. `HOOK_CONTRACT` stays `1` (`field.rs:24`). Verify: the hook's own suite;
then D8's real measure — an installed client session whose prompt carries a symbol cue and
a symptom cue that each have a recorded experience injects both. Reverse: revert; the
store's array support is inert without a sender.

**M9a — the entry payload (jawata-mcp).** *Authored.* Loader writes `type = "lesson"`
(an `EntryForm.EXPERIENCE_TYPES` member, so `verdict` is meaningful on the row and D3's
"an `unproven` experience" is literally true), intent into `summary`,
"When to Use" into `situation`, and into `details` — unparaphrased — the consequences,
the MIT attribution, and the reference-implementation TYPE as a labelled text line;
the repository path into `source_ref` (slug only) and the pinned commit into the
`details` provenance block — never into the identity key. **Every anchor column stays empty,
`package_name` included** (GATE 2 correction above). Verify: the provenance assertions in the env-independent tier above.
Reverse: revert this commit; the seat is untouched.

**M9b — the seat (jawata-studio).** *Authored.* Extend `seats/architect.md` D-FOUR
(`:56–71`) to require naming intent, consequences and the resolving address, or saying
the store had nothing; add the two WATCH MODE clauses (consult without re-deriving; a
"no" stops for the human's word); and add the design-mode-uses-tools sentence to the
stance BODY — not the `tools:` frontmatter, which `render_claude_skill` never emits.
**`seats/architect.md` is the ONLY seat file this sprint edits** — the auditor and
communicator placement is 28f's. Verify: redeploy, then read
`~/.claude/skills/refactor/SKILL.md` and confirm the text is in the **deployed** file
(`conductor.rs:261–278`); then a design-mode run over the frozen catalogue questions.
Reverse: revert and redeploy.

**M10 — the tool-step ceiling.** *Authored, Rust.* `Ceilings` gains `max_tool_steps`
(`runner.rs:51–63`); the frontmatter parser gains the key beside the other three
(the accept-list from `:179`, whose `other =>` arm at `:207` currently rejects it);
`ClaudeCodeAdapter.max_turns` (`:703–711`) is built from it; `parse_text` (`:745–749`)
distinguishes the turn-limit subtype so `run_seat` emits `Verdict::Reaped` with a
`CeilingKind` (`:68–82`). Verify: a seat file declaring the key loads; a run that exceeds
it reports the ceiling by name. Reverse: revert.

> **Release 3 fence.** D6, D7, D8 complete. Front-door check again on the built product,
> plus D7's deployed-skill check and D8's real-session check.

### End-state test surface (D5–D8)

**Environment-independent tests** (no store file, no server, no client):

- the extractor turns one committed README fixture into one record with intent, situation
  and consequences populated and the reference implementation as a labelled TEXT line in
  `details`, with **no symbol, package, operation or snippet COLUMN set**;
- that record's provenance is complete and asserted field by field: the **MIT
  attribution** block present, `source_ref` carrying the repository path and NO commit, the pinned
  commit present in the `details` provenance block, the **licence verdict** present, and the `details` reference line's **package
  prefix equal to the pattern's own Java package** (D5's "Java package … as provenance",
  discharged in text);
- the `details` prose is **byte-identical** to the README section it came from —
  "unparaphrased" asserted, not intended;
- **the bounded-count mode loads exactly ONE pattern**, and the round-tripped entry's
  `summary` passes the shape checks rather than being heading-shaped — D5 attaches a
  reason to sample-before-bulk ("the loader has produced heading-shaped entries
  before"), so it is a gate;

- the loader against an in-memory store: 187 in, 187 rows out, every `type` an experience
  type (`EntryForm.EXPERIENCE_TYPES` — D3 calls each row "an `unproven` experience", and
  `verdict` is meaningful only on those), all
  `provenance_kind = 'catalog'`, all `verdict = 'unproven'`, all `status = 'candidate'`,
  and **every anchor column empty — symbol, package, operation, snippet**;
- a catalogue row is NOT returned for a bare package cue naming its reference package,
  nor for a symbol cue naming its reference type — the direct assertion that the
  catalogue stayed anchor-free;
- a second load at the same snapshot writes nothing — asserted on the WRITE COUNT, not
  only the row count, so a delete-then-reinsert cannot pass;
- snapshot N → N+1: added rows are candidates; each changed pattern yields one new row
  carrying `supersedes` → the older id; the older row's `status`, `verdict` and body are
  byte-identical before and after; an upstream-deleted pattern's row survives;
- N+1 loaded twice adds zero;
- a hand-edited catalogue row survives a same-snapshot load untouched, **and at a newer
  snapshot is superseded rather than overwritten — its own body unchanged**;
- `RecallQuery` with two symptom cues is a union, not an AND (the counter-assertion to
  `H2ExperienceStore.java:903–915`);
- multi-cue candidates are ranked by one comparator chain — same input, same order, no
  second sort;
- an arrays-only `RecallQuery` is NOT empty — the emptiness rule counts the new
  components, so the widening does not hold merely by the caller also sending scalars;

**Boundary tests** (a real H2 file, a real resource, a real seat file, a real front door):

- **a `recall` call carrying `symbols`/`symptoms` arrays over JSON-RPC reaches `query`
  with both populated** — the check that fails if `ExperienceTool#recall` is left on the
  five-argument constructor, which is the only way this capability can ship
  built-but-unreachable; it needs the front door, so it belongs here and not above;
- the loader issues no write against any row whose `provenance_kind != 'catalog'` —
  a write-set assertion over a real store, R8's namespace-isolation half (R5's
  one-mechanism half is the two-part bullet below);

- the snapshot resource is reachable from the packaged jar by `getResourceAsStream`;
- a v9 store file upgrades and then loads the catalogue without losing a row;
- export/import round-trips a catalogue row with its `supersedes` link intact;
- `experience(kind=stats)` returns the `catalogue` block, degrading with a reason rather
  than a zero when the snapshot is absent;
- `experience(kind=list, status="candidate")` returns exactly the rows the report named;
- the loader does not run when `openRealStore` throws — a `RecoveringExperienceStore`
  fallback is never seeded;
- **one catalogue writer** (R5's one-mechanism property), in BOTH halves, because the
  call hierarchy alone cannot carry it: (a) the only production caller of the catalogue
  write path is `PatternCatalogueLoader#load`, reached only from
  `JawataApplication#openRealStore`; and (b) **no production site other than that loader
  writes `provenance_kind = 'catalog'`** — derived from the incoming references of the
  constant holding the value, which is what R5 actually binds. Both need a loaded
  workspace, so both live here rather than in the environment-independent tier;
- `build/unwired-gate.sh` exits 0 against the built dist with
  `build/unwired-baseline.txt` unmodified — a boundary check, covering **only the Java
  half**;
- a seat definition carrying `max_tool_steps` parses; an unknown key still errors;
- the deployed skill's BODY carries the FOUR stance changes M9b makes — (1) design mode
  names the pattern's intent, its consequences and its resolving address, or says the
  store had nothing; (2) design mode uses tools; (3) watch mode consults and does not
  re-derive; (4) a watch-mode "no" stops for the human's word — and does **not** carry
  the tool-less-roles clause, which is 28f's. (The ruling's fourth clause, the tool-step ceiling, is a `runner.rs` change,
  not skill text, and is checked below; `tools:` frontmatter is not rendered at all, so
  no test may look for it.) A test demanding all four would fail by construction or drag
  deferred scope in;
- `~/.claude/skills/refactor/SKILL.md`, after a redeploy, contains the NEW requirement
  this sprint adds — name the pattern's intent, its consequences and its resolving
  address, or say the store had nothing — and not merely the D-FOUR text studio
  `3dc39a6` already deployed —
  the source file being right is explicitly not this check.

**Reality-only — nothing else can establish these:**

- a **fresh install**: first start yields exactly one entry per snapshot pattern; second
  start yields the same count (D5);
- a store already holding user entries: every user row byte-identical before and after a
  load (D5);
- a **resident open on the same store** while the loader runs: neither blocks nor corrupts
  (D5, R8);
- starting the **installed** server at a jawata carrying snapshot N, then at one carrying
  N+1, over one store: adds, supersedes, overwrites nothing, deletes nothing, and the
  start-up report lists it (D6 — a server start, not a hand-driven load);
- a **real architect-seat design-mode run** answers a covered question with intent,
  consequences and an address that opens in the `patterns` workspace, and selects nothing
  on an uncovered one, with the ask and the decision visible in its transcript (D7);
- a design-mode run that exceeds its tool-step ceiling stops and says which ceiling (D7/R9);
- a **real client session** whose prompt carries both a symbol cue and a symptom cue, each
  with a recorded experience, injects both (D8) — observed through the installed hook,
  never a unit test.
