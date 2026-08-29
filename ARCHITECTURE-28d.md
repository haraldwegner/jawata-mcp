# ARCHITECTURE-28d — the vocabulary, the detectors, the catalogue (v2, with v3 + v4 corrections)

> **v3 (2026-08-28)** does not supersede v2 wholesale; it corrects three clauses that
> measurement contradicted during Stage 6/S3, each marked `CORRECTED v3` or `v3:` inline,
> and adds one recorded-but-unbuilt section (*What the catalogue is*). The v2 amendment
> record below is left exactly as written.
>
> **v4 (2026-08-29)** corrects ONE clause, raised by the architect seat at the Stage 7
> checkpoint and verified independently before folding: *Module placement, per stream*
> specified a new package `org.jawata.mcp.refactoring.ops` on a Template Method
> skeleton. The package does not exist, and the skeleton already did —
> `AbstractApplyingRefactoringTool`, **22 subtypes**. The shipped placement is this
> document's own "reuse, do not invent a second" rule applied correctly; the address
> was what was wrong. Marked `CORRECTED v4` inline.
>
> **The pattern across v2, v3 and v4 is worth naming, since it is now three for three:**
> every correction so far has been a clause that was plausible when written and that
> nobody measured until a checkpoint forced it. This document is a baseline that later
> checkpoints diff against, so a clause it gets wrong is re-litigated at every one of
> them until amended.

## v2 AMENDMENT (2026-08-28) — the catalogue seam, superseded in part

**v1 let two catalogue sources each write their own seeding lifecycle. They diverged,
and the spec had already forbidden it** — D10: *"**One registry seeds them all**."*
`SampleSource.seed` performs NO supersession, so the samples lane carries both the
duplicate-on-edit defect the fork half fixed and the orphan defect `413ab61` fixed.

**The sanction was never in this file** — an earlier draft of the Stage 6 plan said it
was, and that was wrong. It is in `CatalogueSource.java`'s own javadoc (`:4` "with its
own **lifecycle**", `:15` "that source **owns the row's lifecycle**") and in store record
`6221732b`. Both are struck; the interface is deleted at Stage 6/S6 and the sentence must
not be carried into the replacement record's javadoc.

**Root cause was the FORMAT, not the lifecycle.** Our specimens were authored in a
bespoke shape only our own code could read → a second reader → a second lifecycle →
divergence. **Ruling (Harald, 2026-08-28): iluwatar's per-pattern form is canonical.**
Our patterns are authored in it, one extractor reads both trees, and a source becomes a
LOCATION — a record of data, not an interface carrying behaviour. There is nothing to
decline, which is the enforcement; an opt-in shared helper would not be.

**Also ruled:** two sources are required (our code never enters the MIT fork), and that
is licence's ONLY consequence — **no licence field is added anywhere**. And **no third
catalogue source is coming**: Error Prone, PMD, ArchUnit, Connascence, Fowler's site,
SpotBugs and Sonar never become rows — they are calibration corpora and ideas that become
our own code. No extension point is built for arrivals that will not come.

**What v2 supersedes, clause by clause** — each marked inline below with `SUPERSEDED v2`:

| v1 statement | Status |
|---|---|
| the `CatalogueSource` registry, refactored out of `PatternCatalogueLoader` | registry SURVIVES whole (record `6221732b` records why: four sites hardcoded one loader). `CatalogueSource` as an **interface carrying `seed`** does not — it becomes `CatalogueOrigin`, a record of `(namespace, prefix, manifestResource, workspaceRoot, authority)` |
| "excluded from our own sweeps via the source-root attribute + `excludePaths`" | **FALSE already at C3**, which recorded exclusion holding BY CONSTRUCTION (separate module, off the analysis source path) *rather than by a filter*. **← v2's own replacement is ALSO false, CORRECTED v3:** the module IS on the analysis source path; it looked otherwise only because the project model was stale. The exclusion rests on the specimens tripping nothing, measured with a control. See the samples-module clause below |
| "publicly browsable so `sample:` addresses resolve" | **FALSE.** `org.jawata.samples` has no README at all; packages are `composemethod`/`patternidiom` against slugs `compose-method`/`replace-pattern-with-idiom`. True only after Stage 6/S3–S4, then held by a standing row-side assertion |
| "The tier rides the existing `capability` facet — perform-tier entries carry the plan kind in `capability`" | describes a wire in **neither** lane: 0 of 187 fork rows carry `capability`; the 2 sample rows do and `SampleSource.entryFor` says in a comment it deliberately does not read it |
| "`OcpCure`'s hard-coded recipes become the FALLBACK" | S7 does MORE than extend this: fallback and declared cure become ONE table, reversing `CureCatalog`'s own javadoc ("the two are different questions and this table does not overwrite it"). A reversal, not a confirmation |
| "`tools.smell` may READ the store (**it already does for baselines**)" | the parenthetical was false when written — `tools.smell` had ZERO `ExperienceStore` references until C5 wired the cure lookup. The permission stands; the precedent it claimed did not exist |

**Untouched by v2:** the v3.17.0 reseed lane rule · `ReseedKeepsWhatItCannotRebuildTest`
· `TombstoneTest` · **Template Method at the operation skeleton seam** (Stream 1). That
last is a DIFFERENT seam and stands. v2 explicitly declines Template Method for the
*catalogue* seam: it needs varying steps, and once both sources share a form there are
none — the variation is constants, so it would add a class per source holding no state
and making no decision. The honest name for the catalogue shape is Fowler's *Replace
Subclass with Fields*.

---

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
  org.jawata.mcp.tools              org.jawata.mcp.tools.smell  org.jawata.mcp.knowledge
  ExtractClassTool, ...Tool         CqsDetector, Coupling...    CatalogueSources registry
  (AbstractApplyingRefactoringTool)
                                                                (CatalogueOrigin records)
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

## What the catalogue is (v3, 2026-08-28) — RECORDED, NOT BUILT

> **Scope status: the frame is recorded; nothing here is implemented, and whether it is
> built in 28d is an OPEN DECISION.** It appears in neither the spec's deliverables nor
> Stage 10's text, so an executor must not read this section as a work item. It is here
> because it changes what the catalogue *is*, which conditions how the sections below
> read.

```
BEFORE  = the smell          → detected; on an entry, situation + symptoms
AFTER   = the target state   → THE CATALOGUE ROW. Not a cure.
CURE    = the route B → A    → ordered refactoring steps
```

**The 187 catalogue rows are AFTERs.** They have been spoken of as cures throughout this
sprint, and they are not: a pattern README describes the destination, never the journey.
That is why an address can resolve perfectly and still not tell anyone what to do, and why
"the cure resolves" measures the wrong thing.

**AFTER has two kinds, and only one of them is an address.**

- A *named target form* — a catalogue pattern. "Become a State machine."
- A *definitional end state* — the cure's own completion. Encapsulate Field →
  *encapsulated: the slot owns its state.* No pattern is named because none is needed.

**The definitional AFTER is the more checkable one**, which inverts the obvious
expectation. "The slot owns its state" is `analyze(kind=encapsulation)` returning an empty
external-mutator set; "the old shape is gone" is a reference query returning zero. A
pattern-address AFTER only says *you should now look like this*, which is the harder thing
to verify. **The discipline this needs:** if the AFTER is implicit, NAME THE CHECK that
decides it — an implicit AFTER with no check is prose, and its cure is advisory.

**The pair (B, A) is the entry identity.** B1→A, B2→A, B→A1 and B→A2 are four entries. One
entry per pair, carrying possibly SEVERAL cures; `links[]` already admits repeated
`cured_by`, so multiplicity needs no new column.

**A cure is ordered steps naming operations that already exist** — `extract`,
`move_method`, `encapsulate_field`, the `refactor_to_pattern` kinds — so *does this step
exist* is a registry lookup rather than a new checker. A step no tool can express means
the route is not runnable, which is information, not a gap to paper over.

**A guard is detector presence at the site**, composing the 41 existing kinds; not an
expression language, which is what made this look expensive. **Presence only, never
counts:** each detector already carries a calibrated threshold, so a count comparison would
be a threshold on a threshold tuned against no data. A count that genuinely separates two
routes belongs in a DETECTOR, where calibration already has tests and a baseline.

**Tier is derived, not assigned.** One route with all steps existing → perform
(`cured_by`); several routes with nothing to choose between them, or missing steps, or no
route at all → advise (`detected_by`). Several routes and no guard means choosing needs
judgement, and the standing rule is that `cured_by` is filled only when it does not — a
cure that is right half the time is worse than none.

**Zero cures is a NORMAL state.** A cure is unfillable until its refactoring steps exist,
which is precisely what Half A is building; the pair becomes `perform` on the day the
operations land, with no re-authoring. An absent cure must therefore mean *absent* — never
an empty string, a placeholder, or a defect the quality lane reports. The store already
carries the scar of the opposite: 187 rows were made to carry `verdict: unproven` because
their type demanded an outcome they could not have.

## Module placement, per stream

> **CORRECTED v4 (2026-08-29), at the Stage 7 checkpoint — this clause specified a
> package that was never built, and it should not be. Both facts below were measured,
> not recalled.**
>
> **`org.jawata.mcp.refactoring.ops` DOES NOT EXIST.** `refactoring/` holds 16 files of
> machinery (`ChangeEngine`, `JdtRefactoringEngine`, `ParityGate`, `PurityCheck`,
> `CreateCompilationUnitChange`, …) and no `ops` subpackage.
>
> **And the skeleton this clause specifies already existed when it was written.**
> `org.jawata.mcp.tools.AbstractApplyingRefactoringTool` implements exactly the
> invariant sequence described below — resolve → precondition → rewrite →
> compile-verify → `Change` with `undoChangeId` — with `prepareChange` as its hook.
> Measured: **22 subtypes**, all in `org.jawata.mcp.tools`.
>
> So Stage 7 landed Extract Class as `ExtractClassTool extends
> AbstractApplyingRefactoringTool`, and that is this document's OWN rule applied
> correctly: the Stream 2 paragraph says *"already a template method — reuse it, do not
> invent a second."* Building `refactoring.ops` would have created a second Template
> Method for a skeleton with 22 residents.
>
> **The shipped placement is right and the document's address was wrong.** Operations
> live as tools under `org.jawata.mcp.tools`, on the existing skeleton. Recorded here
> so no later checkpoint re-litigates it, and so nobody creates the package to match a
> diagram. The dependency-direction rules below still bind — read `refactoring.ops`
> there as "the operation tools".

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
  **SUPERSEDED v2 — this is a REVERSAL, not an extension.** Stage 6/S7 does not make
  `OcpCure` a separate fallback type; it **dissolves both `OcpCure` and `RecipeCatalog`
  into `CureCatalog`'s existing `recipe` column**, because two tables of one fact drift.
  That strikes `CureCatalog`'s own javadoc sentence — *"`RecipeCatalog` keeps its own,
  narrower answer: what can be RUN. The two are different questions and this table does
  not overwrite it."* **No spec sentence demands this**; the spec says only to READ those
  files ("the started cure map, not a blank page"). It is a deliberate design decision
  beyond the spec, recorded as one rather than booked as a risk. It changes user-visible
  detector text (`OcpCure.HINT` is appended verbatim by `OcpDetector`,
  `DivergentChangeDetector:73`, `ShotgunSurgeryDetector:69`), so its gate is a
  message-string diff — finding counts alone would miss the drift.

**Stream 3 — catalogue** lands in `org.jawata.mcp.knowledge` (the `CatalogueSource`
registry, refactored out of `PatternCatalogueLoader`; boot AND reseed iterate it —
the 28c B2 design record, inherited whole), plus:

> **SUPERSEDED v2 — the registry survives, the interface does not.** Record `6221732b`
> records why the registry exists (four production sites hardcoded one loader's class
> and prefix), and that reason is untouched — `CatalogueSources`, `owning()`,
> `isCatalogue()`, the per-namespace stats block and the boot-AND-reseed iteration all
> stand. What changes is the ELEMENT TYPE. `CatalogueSource` is an interface carrying
> `seed`, and an interface with a behaviour hook is an invitation that has already been
> accepted wrongly once: `SampleSource.seed` is nine lines that skip supersession, and
> nothing in the type system noticed. It becomes
> a record. One `CatalogueSeeder` owns the lifecycle; a source has no method to
> decline it.
>
> **CORRECTED v3 (2026-08-28) — the record shape written here was never built, and
> two of its five components were rejected BY NAME during implementation.** As
> built it is
> **`CatalogueOrigin(namespace, manifestResource, workspaceRoot, retiredPrefixes)`**.
> `prefix` is DERIVED (`"catalogue:" + namespace + "/"`), not stored — storing both
> would let them disagree, and every ownership question in the lane keys on that one
> string. `authority` is NOT a component either: both origins derive it from their own
> manifest (the fork from its `pinned_commit`, ours from its `authority` field), so a
> stored value would be wrong for the fork the moment the pin moves and would report a
> stale pin without saying so; `CatalogueAddresses.authorities()` reads it via
> `CatalogueManifest.authorityOf(o)`. And `retiredPrefixes` — absent from the shape
> above and carrying the whole S3a/S4 migration — is what supersedes rows under a
> spelling the origin used to own. `workspaceRoot` is as described: it is what makes
> D10's "address opens in the workspace" computable at all.

- `org.jawata.mcp.catalog` — the derivation extractor (dev-time; reads the fork
  checkout, writes the snapshot JSON + the tier per entry). **SUPERSEDED v2 in scope:**
  it becomes `CatalogExtractor(root, origin)` and runs once PER ORIGIN, so the samples
  tree is extracted the same way rather than hand-written — that is Stage 6/S5, pulled
  forward out of Stage 10. It lives in the **test** source root and stays there: it has
  no production caller, and `build/unwired-gate.sh` exists to catch one that has none.
  ~~The tier rides the existing `capability` facet (schema v10): perform-tier entries
  carry the plan kind in `capability`~~ — **SUPERSEDED v2: that wire exists in NEITHER
  lane.** 0 of 187 fork rows carry `capability`; the 2 sample rows do and nothing reads
  them. It becomes a first-class manifest field on both origins, which is what lets
  `CureCatalog` stop hardcoding recipes. **No schema change in this sprint** still holds
  — schema v15 is untouched.
  **v3 (2026-08-28):** under the model recorded in *What the catalogue is*, `capability`
  names a **STEP** — one operation a cure's route may invoke — not the cure itself. A cure
  is the ordered route; a capability is one move within it. Whatever wires this field must
  say which of the two it means, or it will be read as the cure and the route will have no
  home.
- `org.jawata.samples` — a NEW Maven module in this repo: compiles in every build,
  ABSENT from the dist assembly (the completeness enforcer pins the dist content),
  ~~excluded from our own sweeps via the source-root attribute + `excludePaths`~~,
  ~~publicly browsable so `sample:` addresses resolve~~.
  **SUPERSEDED v2 — both clauses**, and the v2 replacement is itself **CORRECTED v3
  (2026-08-28, measured at Stage 6/S3)**.

  ~~The exclusion holds BY CONSTRUCTION (separate module, off the analysis source path),
  NOT by a filter; C3 measured that.~~ **v3: the module is ON the analysis source path.**
  A re-import puts it there as two source roots (775 → 779 files, 45 → 47 packages); it
  appeared off-path only because the project model was STALE — the module was added after
  the running server had imported the workspace. What the exclusion actually rests on is
  that the specimens trip nothing: `long_method` reads **263 with and without**
  `excludePaths=["org.jawata.samples"]`. That is asserted with its control, because an
  ineffective filter and an empty result are the same output — excluding the
  `tools` path instead drops 263 → 67, so the mechanism demonstrably works.
  **Do not add an `excludePaths` entry for the samples module**; it would be a filter with
  nothing to filter, and it would move a baseline for a reason unrelated to the code.

  ~~no `sample:` address resolves: there is no README in the module~~ — true when v2 was
  written, fixed by S3. **The lesson v3 records is why it went unnoticed:** the module was
  absent from the analysis model entirely, so **0 of 4** specimen types resolved and any
  "does the address open?" check was vacuous rather than failing. A ROW-SIDE assertion is
  necessary but NOT sufficient — it must be paired with the module actually being present
  in the model, or it certifies nothing.

  Stage 6/S3 authors one README per slug in iluwatar's form with the specimens beside it.
  That form is one source root PER SLUG, and a Maven module carries exactly one
  `<sourceDirectory>`, so `build/samples/pom.xml` becomes an aggregator with **ONE MODULE
  PER SLUG**. ~~`build-helper` or a module per slug~~ — **v3: `build-helper` is
  ELIMINATED, by measurement, not preference.** jawata's project importer does not index
  `add-source` roots: `build/tests-mcp` adds `src/extra/java` that way, and
  `search_symbols SpikeTestMain` resolves it ONLY inside the built jar, never as source.
  Under `add-source` the build would go green while every samples-lane address failed to
  open — the exact failure this stream exists to end. Do not re-open this option.

  S4 then unifies the scheme to `catalogue:jawata-samples/<slug>/README.md`,
  held by a standing ROW-SIDE assertion over `store.all()` (a manifest cannot vouch for
  itself — a manifest-side check cannot see a row no manifest claims).
- `build/calibration/` — Error Prone + PMD at pinned versions, own CI cell
  (skip-is-failure there and only there), writes the agreement report. **No src/
  code may import or invoke it; it may never write the store** — asserted, not
  assumed: the store's row count is compared before/after a calibration run.

## Dependency direction (who may know whom)

- `knowledge` imports NOTHING from `tools` — the standing rule; the one recorded
  violation (the snippet record importing `tools.shared.TokenShape`, C7 F2) is
  pre-existing, Harald's ruling pending, and this sprint must not add a second.
- `tools.smell` may READ the store ~~(it already does for baselines)~~; it may not
  import `refactoring.ops`. The only detector→cure connection is the entry id.
  **SUPERSEDED v2 — the parenthetical was false when written:** `tools.smell` held ZERO
  `ExperienceStore` references until C5 wired the cure lookup. The permission stands; the
  precedent it claimed did not exist. Measured `forbidden_edge` production count for
  `knowledge → tools`: **0**.
- The OPERATIONS import the engine and JDT; never `tools.smell`, never the
  extractor.
  **CORRECTED 2026-08-29 at C8 (the second falsified clause in this document, after
  S7.8 fixed a different one):** this said `refactoring.ops`, and no such package
  exists. Measured: `search_symbols("RefactoringOperation*")` → **0**;
  `find_references(implementations, AbstractApplyingRefactoringTool)` → **23
  production tools, every one in `org.jawata.mcp.tools`**, `ExtractClassTool` and both
  Stage 8 additions among them. `ExtractClassOp` and `MoveFieldOp` never existed. The
  stream-1 box above is corrected to match. The dependency RULE is unchanged and was
  always true — only the package it named was wrong, which is exactly how a falsified
  clause survives: the sentence around it is correct, so nothing reads oddly.
  **If the `refactoring.ops` split is genuinely wanted it is a migration step with 23
  members and a gate**, not a description of the present.
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

> **STEPS 1, 2 AND 4 ARE SUPERSEDED — read the notes attached to them.** They are the
> v2 plan and are kept because the reasoning is still worth following, but three of
> them name mechanisms that no longer exist. Stage 6 (2026-08-28) collapsed the
> catalogue to one form and one lifecycle, which retired the `CatalogueSource` seam
> step 1 creates and the `sample:` prefix step 2 seeds.

1. ~~**Extract the `CatalogueSource` seam** from `PatternCatalogueLoader`~~
   (`refactoring(action=plan)` where the shape allows; the registry interface is new
   code). Gate: catalogue loader tests + reseed contract tests green UNCHANGED.
   **SUPERSEDED:** the seam was extracted and then DELETED at Stage 6 — an interface
   carrying `seed` is an invitation each source can decline, and one did. A source is
   now a `CatalogueOrigin` record with no method to implement wrongly.
2. ~~**Add `org.jawata.samples` + its source** (registered, seeds `sample:` rows).
   Gate: every `sample:` address opens in the workspace~~; dist byte-identical;
   own-sweep baselines unchanged.
   **SUPERSEDED:** no `sample:` row is seeded and no `sample:` address is expected to
   open. S4 moved the lane to `catalogue:jawata-samples/`; `sample:jawata-samples/` is
   now a RETIRED prefix that `CatalogueSeeder` supersedes on the next seed
   (`CatalogueSources.java`). The dist and sweep halves of the gate stand unchanged.
3. **The five detectors**, one at a time on `AbstractAstDetector`. Gate per kind:
   proof-of-life fixture non-zero BEFORE any zero counts; frozen-fixture ranks
   stable across two runs.
4. **The cure lookup** — ~~`RecipeCatalog`~~ consults the catalogue lane first,
   hard-coded recipes as stated fallback. Gate: the W1/W2/W3 assertions from the
   spec's D6, including the non-zero-then-zero drift pair.
   **SUPERSEDED in its NAME only:** `CureLookup` does the consulting, and
   `RecipeCatalog` no longer exists — S7 folded it and `OcpCure` into `CureCatalog`,
   which already held every mapping both carried. The step and its gate are otherwise
   as executed.
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
