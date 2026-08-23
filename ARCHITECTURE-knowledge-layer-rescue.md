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
                 |  writes ONLY provenance_kind='catalogue' rows
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
        conductor.rs (:261..306) renders ~/.claude/skills/refactor/SKILL.md
        runner.rs Ceilings (:51) + ClaudeCodeAdapter.max_turns (:703) <- D7/R9
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
incident its own comment records at `:637–639` — "the 2026-07-19 fleet flip served 367
seed entries as if they were the DB". Hanging the loader inside `openRealStore` means a
degraded store is never seeded.

**Identity key.** Source reference plus content hash, using the two methods that exist
for exactly this and are already forwarded by the decorator:
`ExperienceStore#sourceUnchanged(sourceRef, sourceHash)` (`ExperienceStore.java:62`,
implemented `H2ExperienceStore.java:551–565`) and
`ExperienceStore#putWithSource(entry, sourceRef, sourceHash)` (`ExperienceStore.java:56`,
implemented `H2ExperienceStore.java:545`). Per-pattern `sourceRef` is
`catalogue:java-design-patterns@<pinned-commit>/<slug>/README.md`; `sourceHash` is the
SHA-256 of that README's extracted record.

**Deliberately NOT the `.jawata-recovered` marker idiom** (`H2ExperienceStore.java:1481`,
`:1506`): that marker means "swept, never look again", which is right for a one-time
orphan import and wrong here, because D6 requires the same source to be looked at again
at a newer snapshot and compared. Marker semantics would make D6 impossible. Source-hash
semantics give a per-pattern no-op on re-run AND a per-pattern change signal at the next
snapshot — one mechanism serving both D5's "loads nothing again" and D6's "adds the new
patterns".

**Namespace isolation.** `provenance_kind` already exists (`SchemaMigrations.java:538`,
v10) and already carries `"recorded"` (`ExperienceTool.java:899`) and `"ingested"`
(`ExperienceMaintenance.java:340`, `:401`). Catalogue rows take a third value,
`"catalogue"`, in that same column. No new column, no v11 rung. Isolation becomes a
property a test can assert: the loader's write set is exactly the rows whose
`provenance_kind = 'catalogue'`, and it issues no `UPDATE`, `setStatus` or
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
vocabulary "documented in one schema string and enforced nowhere" (`EntryForm.java:88–91`).

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
| repository path + commit | `source_ref` | the loader's identity key |
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
snippet fields"*, which "Catalogue ownership" above states in the same words. A design
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
reading tool accepts as a stable key and which opens the file. The seat loses nothing:
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

- `Ceilings` carries `wall_ttl_secs`, `max_iterations`, `cost_budget_usd` only
  (`jawata-studio/src-tauri/src/runner.rs:51–63`); `max_iterations` counts passes of the
  `run_seat` DETECT loop (`:1059–1064`), not tool steps.
- The tool-step bound is the adapter's `--max-turns`, defaulted to 12 in code
  (`runner.rs:703–711`, passed at `:729–730`) and **not settable from a seat definition**:
  the frontmatter parser accepts `name, model, effort, schedule, tools, gates, ttl_secs,
  max_iterations, cost_budget_usd` and hard-errors on any other key (`:180–206`).
- When the CLI does stop on turns, `parse_text` reads the `result` event's `result` string
  and ignores its subtype (`:745–749`), so a turn-exhausted run is indistinguishable from
  a finished one — it stops, but does not say so.

The design: `Ceilings` gains `max_tool_steps`; the frontmatter parser gains the key beside
the other three; `ClaudeCodeAdapter.max_turns` is built from it instead of its default;
the adapter's result parsing distinguishes the turn-limit subtype so `run_seat` emits
`Verdict::Reaped` with a `CeilingKind` (`:68–82`). Entirely studio-side.

**Deployment, which is the measure that bites.** D7's measure names the *deployed*
`~/.claude/skills/refactor/SKILL.md`, not the source. That file is generated from
`seats/architect.md` by `conductor.rs` (`:261–306`; embed table `:16–23`; the
"every `seats/*.md` on disk is embedded" invariant `:593–612`). The D7 step is done when
a redeploy has regenerated the skill and the regenerated file carries the text.

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
(`:437–460`; the comment at `:438–441` states the rule outright). A prompt naming a class
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
  `symbols: [...]` and `symptoms: [...]` arrays carrying the complete sets.
- *Store does*: reads the arrays where present, unions them into the same clause builder
  (one clause per member, same `OR` at `:917–918`), the same `fits` disjunction, the same
  single sort. A store that does not know the arrays reads the scalars and behaves
  **exactly as today**.

**No `HOOK_CONTRACT` bump.** The version is `1` (`field.rs:24`), echoed under
`X-Jawata-Contract` (`FieldContract.java:16`) and compared for exact equality
(`query.rs:252–255`). A bump would make every older store refuse to inject. Sending
scalars *and* arrays makes the new hook's worst case against an old store identical to
today rather than silence — which is why the scalars stay.

**Store-side change, plainly:** `RecallQuery` (`RecallQuery.java:15–16`, a five-component
record) gains two list components; `query` and `fits` iterate them. That is the entire
Java change.

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

**M1 — freeze the snapshot artifact.** *Authored, new files.*
`org.jawata.mcp/resources/catalogue/patterns-<commit>.json` + sidecar (pinned commit, MIT
licence verdict, extraction date). Verify: 187 records; a probe that
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
`get_call_hierarchy(direction="incoming", symbol="…PatternCatalogueLoader#load")` names
`JawataApplication#openRealStore` in `org.jawata.mcp/src`, not a test;
`build/unwired-gate.sh` exits 0 with `git diff --exit-code build/unwired-baseline.txt`
clean. Reverse: remove the line.

**M4 — compose the start-up sequence.** *Tool.*
`refactor_to_pattern(kind="compose_method", symbol="org.jawata.mcp.JawataApplication#openRealStore")`
over the start-up-task statements, or `extract(kind="method", methodName="runStartupTasks")`
over that range. Verify: `compile_workspace` 0/0 and M3's call-hierarchy check still names
a production caller. Reverse: `refactoring(action="undo")`.

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

**M7 — widen the recall value.** *Tool, then authored.* `change_method_signature` on
`RecallQuery`'s canonical constructor to add `List<String> symbols` and
`List<String> symptoms`, **keeping a five-argument convenience constructor** so existing
construction sites (including `ExperienceTool.java:715–720` and every test) compile
unchanged. Then `compile_workspace(clean=true)` — a record's canonical constructor shape
change is precisely the case where the incremental builder skips recompiling consumers and
a plain incremental build can report a false 0/0. Then author the two iterations: the
clause loop in `H2ExperienceStore#query` (`:869–916`, joined `:917–918`) and the
disjunction in `ExperienceRetrieval#fits` (`:1105–1124`). **Do not touch** the comparator
chain at `:311–318` or the fit gate's semantics. Verify: `compile_workspace(clean=true)`
0/0; a test proving two symptom cues are a union, not an AND. Reverse:
`refactoring(action="undo")` for the signature, revert for the loops.

**M8 — stop the hook truncating.** *Authored, Rust.* `pipeline.rs::recall` (`:423–474`):
replace the first-answer-wins loop (`:437–460`) with one ask carrying the scalar best cues
plus the arrays. `HOOK_CONTRACT` stays `1` (`field.rs:24`). Verify: the hook's own suite;
then D8's real measure — an installed client session whose prompt carries a symbol cue and
a symptom cue that each have a recorded experience injects both. Reverse: revert; the
store's array support is inert without a sender.

**M9 — the entry payload and the seat.** *Authored.* Loader writes intent into `summary`,
"When to Use" into `situation`, and into `details` — unparaphrased — the consequences,
the MIT attribution, and the reference-implementation TYPE as a labelled text line;
repo path and commit into `source_ref`. **Every anchor column stays empty,
`package_name` included** (GATE 2 correction above). Seat: extend
`seats/architect.md` D-FOUR (`:56–71`) to require naming intent, consequences and the
resolving address, or saying the store had nothing. Verify: redeploy, then read
`~/.claude/skills/refactor/SKILL.md` and confirm the text is in the **deployed** file
(`conductor.rs:261–306`); then a design-mode run over the frozen catalogue questions.
Reverse: revert and redeploy.

**M10 — the tool-step ceiling.** *Authored, Rust.* `Ceilings` gains `max_tool_steps`
(`runner.rs:51–63`); the frontmatter parser gains the key beside the other three
(`:192–205`, whose `other =>` arm at `:206` currently rejects it);
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
- the loader against an in-memory store: 187 in, 187 rows out, all
  `provenance_kind = 'catalogue'`, all `verdict = 'unproven'`, all `status = 'candidate'`,
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
- a hand-edited catalogue row survives a same-snapshot load untouched;
- `RecallQuery` with two symptom cues is a union, not an AND (the counter-assertion to
  `H2ExperienceStore.java:903–915`);
- multi-cue candidates are ranked by one comparator chain — same input, same order, no
  second sort;
- the loader issues no write against any row whose `provenance_kind != 'catalogue'`.

**Boundary tests** (a real H2 file, a real resource, a real seat file):

- the snapshot resource is reachable from the packaged jar by `getResourceAsStream`;
- a v9 store file upgrades and then loads the catalogue without losing a row;
- export/import round-trips a catalogue row with its `supersedes` link intact;
- `experience(kind=stats)` returns the `catalogue` block, degrading with a reason rather
  than a zero when the snapshot is absent;
- `experience(kind=list, status="candidate")` returns exactly the rows the report named;
- the loader does not run when `openRealStore` throws — a `RecoveringExperienceStore`
  fallback is never seeded;
- `build/unwired-gate.sh` exits 0 against the built dist with
  `build/unwired-baseline.txt` unmodified — a boundary check, covering **only the Java
  half**;
- a seat definition carrying `max_tool_steps` parses; an unknown key still errors;
- `~/.claude/skills/refactor/SKILL.md`, after a redeploy, contains the consultation text —
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
