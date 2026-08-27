# ARCHITECTURE-28d — the vocabulary, the detectors, the catalogue (v1)

Design-mode artifact for Sprint 28d (spec:
`jawata-enterprise/docs/sprints/jawata-mcp/sprint-28d-fowler-vocabulary-pattern-catalog.md`,
GATE 1 signed 2026-08-27). The plan is written against this document; every checkpoint
diffs its changes against it (design fix or bandage?).

**Store consultation (D-FOUR, 2026-08-27):** 3 of 8 nominees selected — the
template-method pattern (`catalogue:java-design-patterns/template-method/README.md`,
consulted with intent and consequences from a PRESENT catalogue, 187 entries), the
hard-half lesson (`f14c04b2`: a refactoring that moves ownership is done when the old
path is IMPOSSIBLE, not when the new path exists), and the 28c B2 design record
(`6221732b`: the authority model). Each is cited at the seam it shaped.

## The one coherent target

Three work streams — OPERATIONS, DETECTORS, CATALOGUE — that meet only in the store,
and only as DATA. No stream calls another's code; a detector names its cure as a
catalogue entry id, the entry carries the operation kind and the address, and the
seat resolves. That is what lets three streams build in parallel without colliding,
and it is the same resolve-never-compose rule (W1) the spec already binds.

```
  STREAM 1: OPERATIONS              STREAM 2: DETECTORS         STREAM 3: CATALOGUE
  org.jawata.mcp.refactoring.ops    org.jawata.mcp.tools.smell  org.jawata.mcp.knowledge
  ExtractClassOp, MoveFieldOp, ...  CqsDetector, Coupling...    CatalogueSource registry
        |                                 |                       + org.jawata.samples
        | registers a plan KIND           | registers a KIND      + catalog/ extractor
        v                                 v                           |
  +---------------+                 +----------------+                | seeds rows
  | refactoring   |                 | detector       |                v
  | (action=plan/ |                 | registry +     |          +-----------------+
  |  apply_plan)  |                 | family sweeps  |          | experience store|
  +---------------+                 +----------------+          | (lanes, v3.17)  |
        ^                                 |  cure = entry ID --> +-----------------+
        |  entry.capability names         |  (a string, never          |
        |  the plan kind (data) --------- +   a call)                  v
        |                                                    architect seat resolves
  build/calibration/ (Error Prone + PMD, own CI cell) — reads fixtures, writes a
  report, NEVER the store, NEVER imported by src/
```

## Module placement, per stream

**Stream 1 — operations** land in a NEW package `org.jawata.mcp.refactoring.ops`,
beside the engine they use (`JdtRefactoringEngine`, `ChangeEngine`, `ParityGate`,
`PurityCheck` — all existing, none modified structurally). Each operation is a class
implementing the existing staged-change lifecycle.

- **Seam: the operation skeleton — Template Method** (consulted from the catalogue:
  *define the invariant parts of an algorithm once, defer the varying steps*; its
  stated trade-off — more classes — is the cost we pay deliberately, one class per
  operation, because the alternative is thirty operations each re-implementing
  precondition/rewrite/verify/undo). The invariant sequence: resolve target →
  precondition check (refuse with the reason) → AST rewrite → compile-verify →
  `Change` with `undoChangeId`. Hooks: the target shape, the precondition, the
  rewrite. The eight existing pattern transforms are NOT retrofitted in this sprint —
  they are the do-not-touch list's first entry.
- **The done-definition, from the hard-half lesson and binding on every operation:**
  Extract Class and Move Field MOVE things, so each operation's contract includes
  migrating the references and leaving the old shape gone — the check is a query
  (who still references the old member; zero or it is not done), never a reading.
  An operation that creates the new shape and leaves the old one reachable is
  incomplete BY CONTRACT, however green its tests.

**Stream 2 — detectors** land in `org.jawata.mcp.tools.smell`, on the existing
`AbstractAstDetector` skeleton (already a template method — reuse it, do not invent a
second). Each of the five kinds registers like the thirty before it. The coupling
metric's output shape is connascence's three axes; its thresholds are quantiles over
the scanned corpus, never absolute numbers.

- **Seam: the cure link is a LOOKUP, not a call.** `RecipeCatalog`/`OcpCure` today
  hard-code recipe strings. Target: a detector's finding carries its kind; the cure
  is resolved from the catalogue lane at answer time (entry → capability → plan kind
  + address). `OcpCure`'s hard-coded recipes become the FALLBACK when the catalogue
  namespace is absent — and that absence is STATED (W2), never silent. This kills the
  drift the spec's D6 names: the mapping lives in the seeded entries, re-resolved
  when the pin moves (W3).

**Stream 3 — catalogue** lands in `org.jawata.mcp.knowledge` (the `CatalogueSource`
registry, refactored out of `PatternCatalogueLoader`; boot AND reseed iterate it —
the 28c B2 design record, inherited whole), plus:

- `org.jawata.mcp.catalog` — the derivation extractor (dev-time; reads the fork
  checkout, writes the snapshot JSON + the tier per entry). The tier rides the
  existing `capability` facet (schema v10) — **no schema change in this sprint**:
  perform-tier entries carry the plan kind in `capability`; advise-tier carry none.
- `org.jawata.samples` — a NEW Maven module in this repo: compiles in every build,
  ABSENT from the dist assembly (the completeness enforcer pins the dist content),
  excluded from our own sweeps via the source-root attribute + `excludePaths`,
  publicly browsable so `sample:` addresses resolve.
- `build/calibration/` — Error Prone + PMD at pinned versions, own CI cell
  (skip-is-failure there and only there), writes the agreement report. **No src/
  code may import or invoke it; it may never write the store** — asserted, not
  assumed: the store's row count is compared before/after a calibration run.

## Dependency direction (who may know whom)

- `knowledge` imports NOTHING from `tools` — the standing rule; the one recorded
  violation (the snippet record importing `tools.shared.TokenShape`, C7 F2) is
  pre-existing, Harald's ruling pending, and this sprint must not add a second.
- `tools.smell` may READ the store (it already does for baselines); it may not
  import `refactoring.ops`. The only detector→cure connection is the entry id.
- `refactoring.ops` imports the engine and JDT; never `tools.smell`, never the
  extractor.
- `org.jawata.samples` imports nothing of ours and nothing of ours imports it —
  it is addressed by path, compiled for honesty, and otherwise inert.
- `catalog` (extractor) is dev-time: it may import `knowledge` (to write entries
  through the form gate) and read the fork checkout; nothing imports it.

## Must not be touched

- The eight existing pattern transforms and their tests.
- `TokenShape` and the pinned groupId fixture (`b08a9882bf44`).
- The store schema (v15) — the tier uses the existing `capability` facet.
- The v3.17.0 reseed lane rule and its contract tests
  (`ReseedKeepsWhatItCannotRebuildTest`, `TombstoneTest`) — step-1 gate is that they
  pass UNCHANGED.
- The dist content set — the enforcer proves the samples module stays out.

## Migration path (ordered, parity-gated; each step independently reversible)

1. **Extract the `CatalogueSource` seam** from `PatternCatalogueLoader`
   (`refactoring(action=plan)` where the shape allows; the registry interface is new
   code). Gate: catalogue loader tests + reseed contract tests green UNCHANGED.
2. **Add `org.jawata.samples` + its source** (registered, seeds `sample:` rows).
   Gate: every `sample:` address opens in the workspace; dist byte-identical;
   own-sweep baselines unchanged.
3. **The five detectors**, one at a time on `AbstractAstDetector`. Gate per kind:
   proof-of-life fixture non-zero BEFORE any zero counts; frozen-fixture ranks
   stable across two runs.
4. **The cure lookup** — `RecipeCatalog` consults the catalogue lane first,
   hard-coded recipes as stated fallback. Gate: the W1/W2/W3 assertions from the
   spec's D6, including the non-zero-then-zero drift pair.
5. **The operations**, in D1-survey order, each on the operation skeleton with the
   done-definition (old shape gone, checked by query). Gate per op: parity
   (compile 0/0 + purity) + undo restores byte-identical + the reference query
   returns zero.
6. **The extractor + snapshot + tiers** (D7/D8), then the calibration cell (D9).
   Gate: counts predicted before, verified after; store row count untouched by
   calibration.

## The test surface this design buys (D-THREE)

- **Environment-independent** (run anywhere): operation fixtures, detector
  proof-of-life + rank stability, registry contract tests, samples compilation —
  all in-repo, no external checkout in any gate (the fork is dogfood; its findings
  become fixtures — store lesson `c047cb60`).
- **Boundary-owned** (one CI cell each): the calibration harness (needs Error
  Prone/PMD); the existing gradle-cell precedent is the shape.
- **E2E smoke** (front door, built dist): a design question answered with a pattern
  whose address resolves; a perform-tier entry whose plan kind actually stages; the
  per-namespace degradation line when a source is absent.
