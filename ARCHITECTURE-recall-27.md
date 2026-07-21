# ARCHITECTURE — Sprint 27: semantic recall (Version 1)

> Design-mode artifact per the /sprint contract. Baseline: the SIGNED spec
> `jawata-enterprise/docs/sprints/jawata-mcp/sprint-27-embeddings.md`
> (GATE 1, Harald 2026-07-21). The Phase-B plan is written against THIS
> document; the architect's watch mode diffs checkpoint changes against it.
> Target: jawata-mcp v3.4.0 + a small jawata-studio release (launch flag).

## The picture

```
                                cue
     (question hook text · choke target · seat ask · session primer)
                                 │
                ┌────────────────┴───────────────────┐
                │           NOMINATORS               │
                │  keyword/alias (existing) ∪        │
                │  EmbeddingIndex.nearestK (NEW)     │
                │  [K + floor = volume caps only]    │
                └────────────────┬───────────────────┘
                                 │ candidates (no status)
                ┌────────────────┴───────────────────┐
                │        FIT RULES, BY KIND          │
                │  FACT  → hard gate (address-bound  │
                │          exact checks, terminal)   │
                │  EXPERIENCE → rank (equality =     │
                │          boost), cap, label with   │
                │          basis-in-words+provenance │
                └────────────────┬───────────────────┘
                                 │
      ┌──────────────────────────┼──────────────────────────┐
      │ recall answers           │ precedent lane            │ D6 counters
      │ (question hook, primer,  │ identity → warn+charge    │ fired/followed/
      │  seat recall)            │ similarity → advisory     │ defected/outcome
      └──────────────────────────┴──────────────────────────┘
                                 │
                    Sprint 33 reads the quality ledger
```

## Where new code lands (modules + key types)

**NEW package `org.jawata.mcp.embed`** — the owned embedder, PURE (depends on
nothing jawata; no I/O beyond loading bundled resources):
- `MiniLmEmbedder` — WordPiece tokenizer + 6 encoder layers + mean pooling.
- `MatMul` (seam interface) + `ScalarMatMul` (always) + `VectorApiMatMul`
  (used when `jdk.incubator.vector` is present; silent scalar degrade).
  Conditional third impl (host BLAS via FFM, single symbol `sgemm`) ONLY if
  the latency gate fails — discovered, never bundled.
- `EmbedderIdentity` — {model, dim, version}; different identities are never
  compared.
- Bundled resources: weights + tokenizer vocab (Apache-2.0 checkpoint,
  MiniLM-class; fp16/fp32 decided at plan). Golden vectors COMMITTED as test
  resources; parity gate cosine ≥ 0.999 vs reference.

**`org.jawata.mcp.knowledge`** (extended, no reshape):
- `SchemaMigrations` v7 (additive): embedding column(s) + identity cols on
  the entry table AND the `tool_experience` lane; embedding cache keyed by
  `source_hash`.
- `EmbeddingIndex` — brute-force cosine over the store (no vector index at
  this scale); re-embed on entry change and on identity change (self-heal,
  mirrors `LOADER_VERSION`).
- `ExperienceRetrieval` — Phase-1 nominator union; the KIND-split fit rules
  (fact gate unchanged from Sprint 21; experience ranking with
  equality-boosts, caps, basis-in-words labeling, provenance rendering).
- Write-path dedup hook (D5): nearest-existing ≥ threshold ⇒ duplicate-of-N
  proposal in the record response.
- `QualityLedger` (D6) — counters: fired per surface / followed vs defected /
  outcome-after / gate checked-passed-rejected per criterion. Surfaced via
  the existing stats path. Read-only view is the 27→33 boundary.

**`org.jawata.mcp.learn`** (extended):
- `EmbeddingPrecedentRetriever implements PrecedentRetriever` — replaces
  `KeywordPrecedentRetriever` behind the UNCHANGED seam; semantic gather
  UNIONS with (never replaces) the deterministic identity filter.
- `IdentityMatch` — the target-identity check EXTRACTED from what is implicit
  in `KeywordPrecedentRetriever.recentMatching` today, FQN-preferred
  (harden `ToolExperienceRecorder.target`); the ONLY authority that arms
  `PrecedentLedger`.
- `PrecedentSteer` — advisory tier added (similar-case line, capped top-1/2,
  basis in words); warn tier text unchanged.

**`org.jawata.mcp.tools`** — NO new tool (toolCount stays 45): stats/recall
answer shapes gain the ledger counters + experience labeling. `ToolRegistry`
choke ORDER unchanged: precedentCharge → execute → steering → precedent →
tap → watch → architectGate → serverChecks.

**jawata-studio (small release):** the runtime spawn spec + `build/run-suite.sh`
+ the boot's own launch add `--add-modules jdk.incubator.vector`; flagless
degrades to scalar; the active backend is reported in `health_check`.

## Dependency direction

`embed` ← `knowledge` ← `learn` ← `tools` (strictly leftward; `embed` depends
on nothing jawata). The `PrecedentRetriever` interface stays in `learn`,
UNCHANGED — the Sprint-27 swap happens behind it, and a test double proves
the seam as it did in 26a.

## Must NOT touch

- The choke pipeline order and `PrecedentLedger` charge semantics (identity
  only — v3.3.1 as-built, dogfood-proven).
- The 26a capture selectivity (outcome-bearing events only; routine reads
  capture NOTHING).
- The Sprint-21 fact gate semantics (address-bound, terminal, honest
  nothing) — 27 puts nominators in front of it and measures it; never
  weakens it.
- toolCount 45 — the injector adds no MCP tool.
- The one-universal-archive dist doctrine (22d): weights ship IN the
  archive; BLAS (if ever) is DISCOVERED on the host, never bundled.
- The keyword nominator — it keeps running (union), it is the degrade path
  (a broken embedder = exactly v3.3.1 behavior) and the R1 baseline.

## The 27→33 boundary

27 ships capability + measurement; 33 reads the `QualityLedger` for the
good-enough verdict (keep / improve / replace-with-better — never bare
removal). Nothing in 27 may make that verdict early, and nothing may hide
the numbers it needs.
