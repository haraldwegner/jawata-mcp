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
