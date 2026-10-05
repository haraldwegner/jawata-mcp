# ARCHITECT-REPORT-C9a.md — the hired-brain driver, watch mode

**Seat:** architect, watch mode. **Checkpoint:** C9a, Sprint 28g Stage 9a.
**Date:** 2026-10-05. **Baseline diffed against:** `ARCHITECTURE-driver.md` (v1, 2026-10-05).
**Steps under review:** M0–M5b. **Run read-only** (a full suite was live; this plan records an
auditor's `mvn clean` deleting the directory a concurrent suite was writing into).

## VERDICT: DESIGN FIX

Six steps, and no bandage among them. What the watch found instead are four unfinished edges of a
design moving *toward* the target picture rather than away from it. One is a latent defect in the
artifact a human decides from.

## The ruling asked for first: is `PlanLedger` the step contract, or the lane?

**`PlanLedger#advanceIfGatePassed` IS part of the step contract the hired executor may know.** But
the file is still not compliant, for an adjacent reason.

Measured in both directions, complete scans (1162 files each):

| Edge | Result |
|---|---|
| `learn` → `execution` (the lane knowing the executor) | **0 findings** — that half of the rule HOLDS, and it is a real absence rather than a failure to look |
| `execution` → `learn` (the executor knowing the lane) | **12 findings**, 5 of them in production `HiredRun.java`: `GateOutcome`, `GatedStep`, `LearnerEvent`, `PlanId`, `PlanLedger` |

**Why the method is the contract.** Its signature is `(PlanId, GateOutcome) → Advance` — it names
zero of the lane's deciding types. And the architecture's **own dependency graph already settles
two of the five imports against its own prose**: line 68 reads *"CallerIdentity, PlanId,
GateOutcome (leaf values, no outbound deps)"*, putting them at the base of the stack, which is what
a shared contract is made of. The prose two lines later says "must not know the lane". **The graph
and the prose contradict each other**, and the picture's box is why.

**Why the file is nonetheless non-compliant.** `HiredRun` used **three** ledger methods, not one —
counted independently by `find_quality_issue(kind=feature_envy)`: *"Method 'issue' accesses
PlanLedger members 3 times vs 0 of its own."*

- `advanceIfGatePassed` — the contract. May know it.
- `open(steps)` — the executor **mints lane state**, admitting a plan to the lane's LRU store.
- `currentStep(plan)` — reads lane state back for a number it already holds. (Finding 2.)

**So the architecture needs the contract named — and a type is not enough. It needs a PACKAGE**,
because `forbidden_edge` is the only mechanical check available and it keys on packages. Today the
rule is unfalsifiable: a package reading says 12 violations, a knowledge reading says 1.

**A second box with no referent.** `search_symbols(query="Driver")` returns three classpath
binaries (`java.sql.Driver` ×2, H2) and **zero workspace source**. The picture's `Driver (from
ServerChecks)` box has no type either. Two of the picture's boxes lack a referent, and both are
load-bearing for the one rule under §*"Three things this picture FORBIDS, so a diff can be checked
against it."*

**And the deliberate-duplicate exception survives on a technicality:** `GatedStep`/`GateOutcome`/
`PlanId` are referenced from 5 files, none of them `ServerChecks`, `EventTap` or `SessionLedger`.
The rented path has **zero** step definition, not a second one — so the two executors are said to
*share* a contract that today only the hired one has.

## Findings — three proposals

### 1. Name the step contract as a PACKAGE, so the architecture's own rule becomes falsifiable
The three contract value types sit in the lane's package, which is what makes the rule
interpretable. Moved out, `forbidden_edge(from="org.jawata.mcp.execution",
forbidden="org.jawata.mcp.learn")` becomes the mechanical gate §"so a diff can be checked against
it" asks for: 12 findings drop to 4, and the surviving 4 are the genuine question (2× `LearnerEvent`,
journal vocabulary both paths legitimately write; 2× `PlanLedger`, which is Finding 2's).

**Cost, derived not estimated:** 90 references (`GatedStep` 29, `GateOutcome` 32, `PlanId` 29)
across **5 files total**. No other production consumer exists.
**ACTUATOR:** `move(kind=class)` ×3 into `org.jawata.mcp.step`, staged and read before applying;
then the `forbidden_edge` call as the standing gate.
**HOME:** this stage or the wiring decision. **Honest caveat from the watch:** extracting an
*interface* for the acceptance — so `HiredRun` imports no `PlanLedger` at all — is **not** in this
proposal, because `Advance` is a nested record of `PlanLedger` and the interface's return type
would still point into the lane. The moment the wiring decision is answered, `issue`'s signature
changes anyway, and that is the cheapest moment to narrow it.

### 2. `HiredRun` re-asked the ledger for a number it was already handed — and the proposal record paid
**Rule fired: give the object its behaviour.** Every `Advance` the loop collects already carries
`currentStep()` by value; the loop discarded it and re-fetched from shared mutable state.

**The latent defect at its real strength and no further.** `PlanLedger` is an access-ordered LRU
bounded at `MAX_PLANS = 16`. Evicted between the loop and the re-read, `currentStep` returns empty,
`.orElse(-1) + 1` yields **0**, and the proposal reports **0 of N steps passed for a run where
every gate passed**, with `complete()` false. An empty result reported as a real one, in the one
artifact whose purpose is that a human reads it and decides. **The window needs 16 `open` calls
inside a two-statement window, which needs a second thread, and nothing starts one. Latent, not
live** — the load-bearing argument is the redundancy, not the race.

**FIXED 2026-10-05**, taking the verdict by value. It is still the ledger's number and not a local
count, which the step-count mutation re-confirms: `ran.size()` diverges on exactly the runs where a
gate failed or did not answer.

### 3. The record a human reads could not say the run crashed; it pointed at the manifest
**Rules fired: give the object its behaviour; D-SIX weakly.** `provenance` accesses `Outcome` 8
times vs 0 of its own, `render` 10 vs 0 (both measured by `feature_envy`). The consequence is not
stylistic: **`exitCode` lived in the manifest only**, with `render` emitting *"see the manifest's
exitCode"* — while `Proposal#record`'s javadoc calls the record *"the file a human reads."* So the
single signal separating **a crash** from **a model that chose silence** — Rust defect 3, the whole
point of M5a and of its mutation B — was absent from the human-facing artifact. `saidNothing()`
carries it only when `text` is empty; a brain that spoke and *then* crashed rendered as a brain
that spoke. `- it wrote to stderr` is no substitute: stderr is non-empty for a clean run that
merely warned.

**FIXED 2026-10-05** — the record names the exit status, or says it was reaped before it could
exit. **Still open and homed to 28h with the rider:** three optionals (`costUsd`, `stoppedBy`,
`timedOut`) render their absence twice by two different literals, so editing one and not the other
makes the manifest and the record disagree about the same run.

### Below the fold
- **Rule 13 fires** on `PlanLedgerAdvancesOnlyPastItsOwnGateTest`'s two `Modifier.isPrivate`
  assertions: facts the class owns and the compiler enforces, unfalsifiable at the instant they run.
- **And its one live instrument was name-keyed and blind to the mutation it was built for:**
  `.map(Method::getName)` collapses overloads, so a smuggled `open(PlanId, int)` left it GREEN.
  **FIXED 2026-10-05** — keyed on the signature, with a control proving the old form was blind.
- `HiredRun` has a seam for the **gate** (`GateRunner`) and none for the **hire** (`HiredBrain.hire`
  is static), so every `issue` test forks a real child. One seam where the symmetry wants two. *28h.*
- `ServerChecks#onSessionEvicted` is a check that cannot fire in production, before or after M4, by
  its own comments' admission. *Later sprint.*
- `SeatScheduler#due` is static over seats the instance holds; two test call sites supply their own
  lists, so the parameter earns its keep. *No action.*
- `PolicyDial.Level.BLOCK` obeyed by nothing, `describe()` at zero references — pre-existing,
  correctly recorded by M3 as not its step's. *Later sprint.*

## Rule 12 — measured, and it does NOT fire

**One** breakage event across M0–M5b: M1's move of gate-ness from name-matching to declaration
reddened five sites from **one** cause (a fixture mock declares `isGate()` false; the checks fail
closed on an empty registry). **Zero consecutive pairs where fix N broke something different from
fix N−1**, because M2, M3, M4, M5a and M5b broke nothing that had been working. Rule 12 needs
different symptoms in sequence; there is no sequence.

Four families recur, ten instances of the first read directly out of the source:

| Cluster | Count | Note |
|---|---|---|
| **A — absent is not zero** | 10, source-verified | `GateOutcome.succeeded` · `Advance` a record not a boolean · `currentStep` an `OptionalInt` · `gateArguments` never null · `CallerIdentity.Origin` · `provenance` costUsd null not 0.0 · `render`'s "absent, which is not zero" · `SeatScheduler.due`'s three states · `saidNothing()` false on a failed child |
| **B — no instrument: the claim under test has no observer** | 6 | M3's own record calls it *"the third occurrence of that shape in this stage"* |
| **C — a classification's population changed, fixtures fell out** | 1 structure, 7 sites | M1's registry move |
| **D — a comment or citation claimed more than the code** | 6 | **all six caught by a human re-reading, zero mechanically** |

Cluster A **is the stage's declared subject** — finding it ten times is the stage working. B is
named by the stage itself. **Cluster D is the one with no gate anywhere, and the only family still
generating work.**

**Why the breakage count is this low, which is the finding worth keeping.** M2, M3 and M4 each ran
a census *before* writing, and each census caught the defect the fix would otherwise have caused —
the leaked in-flight ticket, the advisory-check move that would have gone silent in production
while its tests stayed green, the `find_field_writes` gate vacuous before the field exists. **Those
are breakages prevented, not breakages sequenced.**

**Declared limit:** the red-test events are taken from the execution records' own account; the
watch re-ran nothing, because a suite was live and it was read-only.

## Rule 5 — the consumer kinds DERIVED

Type references (`HiredRun` 19, all tests, **zero production**) · method invocations
(`advanceIfGatePassed` 16: 1 production, 15 test) · package edges both directions (1162 files each
way) · DI/reflective registration (`di_registrations` → **0 components, 0 beans, 0 injection
points** workspace-wide, so no reflective consumer kind exists to miss). **Not searched:** the Rust
`run_seat` engine, taken on `ARCHITECTURE-driver.md`'s word as reference-only.

**Load-bearing consequence:** `HiredRun` has **zero production callers** — nothing calls
`SeatScheduler.start()`. Changing `issue`'s signature breaks no production path, which is an
argument for doing Finding 1 before the wiring lands rather than after.

## Judged legitimate, explicitly NOT reported

`SeatSchedule.Field#matches`'s `value > 0` belt (Rule 13's own defence-below-your-own-abstraction
exemption, and its comment says so) · `GatedStep` named to avoid colliding with
`refactoring.PlanStep` · `RuntimeArtifactStore` reused instead of a second record format (D-SIX
honoured before a line was written) · the firing placed outside the monitor · the deleted
`GateOutcome#passed()` · the acceptance check's position before both `tool.execute` and the
in-flight ticket · the hardcoded `"run_tests"` in `onSessionEvicted` · and **incomplete delegation,
which was checked and does not fire** — `HiredRun` exposes no ledger method, `ServerChecks` exposes
no registry method, and `Proposal` hands `Outcome` over whole rather than half-forwarding it.
