# ARCHITECTURE-driver.md — the hired-brain driver (v1, 2026-10-05)

Design-mode output for Sprint 28g Stage 9a, produced by the architect seat and folded on Harald's
word (*"I will follow your recommendation"*). It supersedes nothing: `jawata-studio/ARCHITECTURE-runner.md`
pictures a HIRED PROCESS subsystem that never ran, and `ARCHITECTURE-conductor.md` pictures seat-text
RENDERING. A loop owner living in `jawata-mcp` is in neither, which is why this file exists rather
than extending either of them.

## Scope

Who owns the answer to **"which step is open, and did it pass?"**, and how a brain is HIRED to work
on it. Not in scope: owning a chat, the ACP pipe, the turn boundary, or any client dialect — all
Sprint 28h's, and listed there under `2026-10-05 — What arrives here from the 28g S9a re-scope`.

## The target, in one sentence

jawata already owns a per-session enforcement lane at a single choke that rides the response every
client reads; the driver is **that lane given a PLAN as its subject and a REFUSAL as its verb**,
with the hired-process executor beside it behind one step contract — never a second lane.

## The picture

```
            THE HOST'S CHAT                          NOBODY IN THE CHAIR
     (rented brain: Claude/Cursor/...)             (the schedule fires a hire)
                  │                                          │
            MCP tools/call                                    │
                  ▼                                           │
  ┌──────────────────────────────────────────────┐            │
  │  JsonRpc / ToolRegistry                      │            │
  │    ├─ the shipped tools                      │            │
  │    └─ tap()   THE ONE CHOKE — every          │            │
  │       completed call converges here          │            │
  └───────────────────┬──────────────────────────┘            │
                      │ EventTap.onCall(...)                  │
   ┌──────────────────┴────────────────────────────┐          │
   │      org.jawata.mcp.learn  — THE LANE         │          │
   │                                                │         │
   │   CallerIdentity  ◄── declared, never defaulted│         │
   │        │                                        │        │
   │   ┌────▼───────────┐   ┌──────────────────────┐ │        │
   │   │  PlanLedger    │◄──│  Driver              │ │        │
   │   │  (from Session │   │  (from ServerChecks) │ │        │
   │   │   Ledger)      │   │  accept ⟺ a gate I   │ │        │
   │   │  step + its    │   │  RAN, with the STEP's│ │        │
   │   │  gate evidence │   │  own arguments       │ │        │
   │   │  `done` is     │   │  refuse → no next    │ │        │
   │   │  DERIVED       │   └──────────┬───────────┘ │        │
   │   └────────────────┘              │             │        │
   └───────────────────────────────────┼─────────────┘        │
                                       │                      │
                      ┌────────────────┴──────────┐           │
                      │   THE STEP CONTRACT       │◄──────────┘
                      │  one definition of a step,│
                      │  its gate, its acceptance │
                      └────┬─────────────────┬────┘
                           │                 │
                 RENTED executor        HIRED executor
            (the agent in the chat)   (HostProcesses + per-brain
             we withhold the next      adapter; OWNS the process,
             step; strongest act       so all four ceilings are
             is refusal)               real and a breach reaps it
```

## Dependency direction — who may know whom

```
CallerIdentity, PlanId, GateOutcome        (leaf values, no outbound deps)
        ▲
   PlanLedger
        ▲
     Driver ──► OperationRegistry        (ASKED: is this tool a gate?)
        ▲
   EventTap ──► ToolRegistry.tap          (the choke composes; it never decides)
        ▲
   the hook crate · the studio UI         (ask and render; never derive)

the hired executor ──► THE STEP CONTRACT ONLY
   It must not know the lane, and the lane must not know it — otherwise the
   rented path and the hired path become one subsystem wearing two names.
```

## Three things this picture FORBIDS, so a diff can be checked against it

1. **No second session-keyed store.** `PlanLedger` is `SessionLedger` re-keyed, not a sibling.
2. **No `reportDone` on any tool surface.** Acceptance takes a gate outcome; it never takes an
   executor's claim. A stored `done` is a field the executor writes, and verification degrades into
   a convention.
3. **No decision in the hook crate and none in the studio UI.** Both ASK and RENDER.

## The test surface this architecture produces

- **Environment-independent, one suite, no transport, no client** — and this is most of the work:
  the step algebra (open, accept, refuse, advance); `done` derived from a `GateOutcome` and
  **unreachable by any writer** (the assertion is a query per field: zero external writers);
  gate-ness read from the registry (mutation: rename a gate tool — a stale copy goes green, a
  declaration goes red); `CallerIdentity` absence as a distinct state, with `"local"` not
  constructible.
- **Owned by the boundary, measured per environment, never inferred** — exactly one fact: does this
  client send `Mcp-Session-Id`, and what reaches the tap? One row per client. Code-reading cannot
  answer it and neither can a unit test.
- **Only reality can verify** — a hired run advancing a real plan with no human turn between steps,
  against each brain. That is Stage 16, already written.
- **What no test on this path reaches, stated so green does not imply it:** that the executor did
  *the step's* work as against something else that passes the same gate. That is the observer's
  territory (28h).

## Migration — ordered, parity-gated, reversible

M0 measure the transport and the fork behaviour (no code) · M1 gate-ness becomes a declaration
instead of two identical `Set.of(...)` copies · M2 `PlanLedger` with `done` unwritable · M3
acceptance refuses instead of warning, running the gate with the STEP's arguments and asking the
ledger for the step's NAMED tool · M4 `CallerIdentity` replaces the `sessionId == null ? "local"`
ternary · M5 the hire lands in Java beside `ForkedTestRunner`, with the per-brain output adapter and
the scheduler that triggers it. Order forced: M0 before M5, M1 before M2, M2 before M3. Each step's
gate, mutation and revert handle are in the plan's Stage 9a.

### AS BUILT, and three of this section's own premises were measured FALSE — 2026-10-05

This subsection exists because C9a's exit puts the architect's watch against THIS FILE, and a watch
that diffs a built step against a target written before the measurement judges the wrong state.

**M0 — CLOSED.** Five parallel read-only censuses and a fold. It refuted three premises above and in
the plan, each by measurement: the live identity defect is **FRAGMENTATION, not the `"local"`
collapse** (both transports mint, and the two overloads that hardcode the literal have 41 references
of which every one is a test — so M4's subject is a fresh id per request, not an absent one);
**there were THREE gate-tool literals, not two**, so the driver would have been the fourth; and
`HostProcesses#forOs` is **not** the home for a `setsid` wrapper (zero production callers, and its
own javadoc excludes launching — the home is `SystemHostProcesses#start`). It also measured that
every one of the lane's five existing checks degrades to a CONSTANT under a one-row ledger, and that
the cause is independent of which identity direction is live: `SessionLedger.CallRecord` carries
`(tool, ok, filesModified, ts)` and **no arguments at all**. That last fact is why M2 must carry the
step's declared gate arguments, or M3's load-bearing gate has no instrument.

**M1 — CLOSED, and it confirms this file's "the lane given a PLAN as its subject" direction by
following a seam the lane already had.** Gate-ness is now declared by each tool
(`Tool#isGate()`, beside `isMechanical()` and `isStructural()`), forwarded by the single production
write `OperationSurface.publish`, and answered by `OperationRegistry#isGate(String)` with
`#gates()` for the message that names them. The three byte-identical `Set.of(...)` literals in
`EventTap`, `ServerChecks` and `ToolExperienceRecorder` are deleted, along with the prose
re-enumeration in the seat-discipline steer.

**The one design decision worth recording, because this file did not predict it and the codebase
settled it.** The lane's three classes take the registry as an **injected collaborator with a
defaulting constructor**, not by reaching for the process singleton. The singleton reach was chosen
first, on the precedent of `MechanicalChangeJournal.isMechanicalTool(name)` — a static classification
call sitting one line above the gate read in `EventTap`. `ArchitectGate`, in this same package, had
already made this identical repair for structural-ness and its constructor javadoc argues the other
way in so many words: *"a dependency taken from a global is one a test cannot supply … hiding that
behind a singleton would leave the gate's own tests passing over an empty registry, which is a
differently-shaped version of the same blindness."* So the checks **fail CLOSED** on an unpopulated
registry — a seat outcome reads as ungated — and that is deliberate: the cost of a lane test not
wiring the gates is a red test rather than a silent pass. Four lane tests were repaired accordingly,
through one shared fixture.

**One name stays hardcoded ON PURPOSE**, and the picture above does not forbid it:
`onSessionEvicted`'s inline `"run_tests"` asks *did this session do DETECTOR-shaped work*, not *is
this a gate*. Replacing it with the declaration would widen that population to the compile and
diagnostic gates — a sensitivity change, not a declaration move — and M0 measured that the check
cannot fire at all until M4 makes the ledger accumulate. Homed to M4.

**Two smells recorded with their cures named rather than taken:** `OperationRegistry#register` is now
seven parameters with three adjacent booleans (cure: `introduce_parameter_object` over the three
flags plus `structuralKinds`); and `OperationRegistry.Snapshot` went from four components to five,
an arity change on a record its own javadoc flags as crossing the OSGi boundary, taken because the
gate set had to join snapshot/restore/clear **together** or borrow-and-return would be the
*accidentally correct* state a C9 audit found for `discriminatorOf`.

**CONCURRENCY, derived from what each step EDITS rather than from the package it sits in — the
plan's "M1–M5 serialise on `org.jawata.mcp.learn`" is false.** M2 ∥ M4 ∥ M5a: M5a is wholly disjoint
(`org.jawata.core.host`, `org.jawata.mcp.execution`), M2 creates new types and only READS
`ServerChecks`, M4 edits the two ledgers, the recorder, both transports and the protocol handler.
**M3 cannot run beside M4** — both edit the tool registry's one source file. And the WRITING
parallelises where the GATING does not: the suite script refuses to run while a competing Maven
build is live, so lanes are authored concurrently and gated in turn.

### AS BUILT — M2 to M5b, and TWO CORRECTIONS TO THIS DOCUMENT — 2026-10-05

Added at C9a because the audit found this section covered M0 and M1 only, while C9a's exit puts the
architect's watch against THIS FILE. A watch with no reconciled baseline for five of seven steps
cannot diff anything.

**M2 — the ledger.** `PlanLedger` with `advanceIfGatePassed` as the sole mutator of a plan's
PROGRESS, deriving the next index from gate EVIDENCE rather than accepting an index. `GateOutcome`
carries a fourth component, `succeeded`, because `errorCount == 0` is also what a REFUSED gate
reports — absence read as a pass. Steps carry their declared gate arguments and the ledger checks
the call satisfied them (subset semantics: every declared field present and deep-equal, extras
allowed, an absent field distinct from an explicit null).

**M3 — acceptance refuses, and its POSITION is load-bearing twice.** `ServerChecks#onCall` split by
TIMING into `acceptance` (pre-act, returns a `ToolResponse`) and `onCompleted` (post-act). The
refusal returns AS the response, because `ToolResponse#appendSteering` early-returns on any failure
— a defect this repository already names in the registry's own comment. It lands before
`tool.execute` (the step) and before the in-flight ticket, because `inFlight.started` sits outside
the `try` whose `finally` releases it, and that `finally`'s own comment calls a leaked ticket a
FALSE hang. The advisory checks stayed post-act deliberately: `unauditedAskCheck` reads the ledger
for more than one experience call, and pre-act the current call is not in the ledger at all.

**M4 — identity is declared, never defaulted.** `CallerIdentity(id, Origin)` with
`CLIENT_SUPPLIED` / `RESIDENT_MINTED` / `IN_PROCESS`. The live defect was FRAGMENTATION, not the
`"local"` collapse: both transports minted a fresh identity PER REQUEST. Each now mints once per
transport, and `Origin` is what lets the shared-history caveat be about a minted bucket rather than
about every answer.

**M5a — the hire and the proposal record.** `HiredBrain.hire` drives one coding-agent CLI child to
its own exit, a ceiling breach or the wall clock, keeping failure, silence and a reap
distinguishable. `HiredRun.issue` opens a plan, hires, runs each step's gate through the ledger,
journals a `hired_run` row and writes a PROPOSAL as a `RuntimeArtifactStore` artifact of kind
`proposal` — reusing the store rather than writing a second on-disk format (D-SIX). Three of the
Rust reference's four defects are pinned as tests with mutations.

**M5b — the schedule.** `SeatSchedule` (five fields; `*`, a number, `*/n`; a loud `parse`) and
`SeatScheduler` (`due`, a once-per-minute `tick`, the firing thread — new on BOTH sides, since the
Rust cron has no production caller anywhere). The fourth Rust defect is not carried: `due_seats`
converted a malformed cron into *no schedule* via `.ok()`, and `due` keeps three states apart
instead — absent, valid, and malformed-and-reported-by-name. Both new types import only `java.*`,
so the scheduler knows neither the lane nor the executor; the hire attaches through a `Firing` seam.

**CORRECTION 1 — this document's dependency GRAPH and its PROSE contradict each other, and the
graph is right.** The prose at §"Dependency direction" says *"the hired executor ──► THE STEP
CONTRACT ONLY. It must not know the lane."* The graph four lines above it lists *"CallerIdentity,
PlanId, GateOutcome (leaf values, no outbound deps)"* at the base of the stack — which is what a
shared contract is made of. The architect's ruling at C9a: **`PlanLedger#advanceIfGatePassed` IS
the step contract** (its signature `(PlanId, GateOutcome) → Advance` names zero of the lane's
deciding types), and the rule as worded is UNFALSIFIABLE — a package reading finds 12 violations, a
knowledge reading finds 1. Until the three contract value types live in a package of their own,
`forbidden_edge` cannot check the one rule this document says a diff should be checkable against.
The move is costed (90 references across 5 files, no production consumer but `HiredRun`) and homed
to the wiring decision, because that decision changes `issue`'s signature anyway.

**CORRECTION 2 — two of the picture's boxes have no referent.** `search_symbols(query="Driver")`
returns three classpath binaries and **zero workspace source**: the box drawn as `Driver (from
ServerChecks)` is not a type. Neither is `THE STEP CONTRACT`. Both are load-bearing for
§"Three things this picture FORBIDS, so a diff can be checked against it", so that section is
currently checkable by a reader and not by a tool.

### DECLARED DEVIATION — the rented path enforces "a gate ANSWERED", not "a gate PASSED"

C9a's exit reads *"a step claimed done over a FAILING gate is REFUSED through the refusal channel …
and acceptance checks the step's named tool."* **On the HIRED path that is met and
mutation-proved**: `PlanLedger.advanceIfGatePassed` refuses a gate that did not answer and a gate
reporting errors, and it compares the step's declared tool against the outcome's.

**On the RENTED path it is NOT met, and the reason is structural rather than an omission.**
`seatOutcomeCheck` asks whether a gate ran; at C9a it was strengthened to require the gate call to
have ANSWERED (`isGate(tool) && ok()`), which closes the case where a gate blew up and its failure
was recorded on the row and never read. Two halves remain open and are declared here rather than
implied:

1. **A gate that ANSWERED and reported errors still satisfies the check.** `SessionLedger.CallRecord`
   carries `ok` but no error count, so the predicate cannot see it. Closing it is a
   record-component addition across that record's writers — the shape M2 already performed once for
   `arguments`.
2. **"acceptance checks the step's named tool" has no referent on the rented path, because a rented
   session has no plan.** `PlanLedger.open` is called by `HiredRun` and by nothing else; nothing
   opens a plan for a chat session. The clause presumes a precondition the product does not create,
   and deciding HOW a rented session acquires a plan is a design decision this document homes to
   28h along with `/main` as a loop inside a rented chat.

**THIS CLASSIFICATION WAS CHALLENGED AND HELD — C9a audit round 2, 2026-10-05.** The plan's
amendment of the same day, folding his `/main` ruling, asserted the opposite: that the gap *"is not
structural"* and that a `/main` seat is *"exactly the missing `open` caller"*, so the deviation
dissolved. **Measured and false:** `PlanLedger#open` has EIGHT references, exactly ONE production
(`HiredRun:120`), and M6 added zero Java — a markdown stance cannot call a Java method. The plan's
claim is struck; this section's wording stands unchanged, and **M7 — the name the plan gives this
work — is homed HERE, to 28h, reconciled with the entry already on that list, "`/main` as a loop
inside a rented chat".** They are the same work.

What his ruling genuinely changed is narrower and worth keeping: before it there was no visible
path at all, and after it the path has a name and a shape — a session needs a way to DECLARE its
steps, and `/main` is what such a plan would belong to.

So the step-level verdict exists on the hired path and not on the rented one. The lapse condition
above is not tripped — the rented path has **zero** step definition, not a second one — but that is
a technicality worth stating plainly: the two executors are said to SHARE the step contract, and
today only the hired one has it.

## What must NOT be touched

`jawata-studio/ARCHITECTURE-runner.md`'s own rule still holds and this design keeps it: *"an adapter
must never gain direct write access to the workdir — that collapses propose/verify/apply into
'trust the agent'."* The hired path's propose-mode invariant is that rule stated for a process we
own; the rented path's refusal-to-advance is it stated for one we do not.

## DELIBERATE DUPLICATE, with its lapse condition

The Rust `run_seat` engine stays beside the Java driver **as reference only** — his word, 2026-10-05,
and no code depends on it. Where the two executors genuinely differ is containment: the hired path
OWNS a process and can therefore bound wall time, cost, actions and iterations and reap on breach,
while the rented path's strongest act is to withhold the next step. Collapsing them would either
give the rented path a process it has not got or take the hired path's only real bound away.

**The condition that keeps this from becoming two lifecycles:** the two executors share the STEP
CONTRACT and differ only in who started the process. One definition of a step, one of its gate, one
of its acceptance. **If a second step definition appears on either side, the exception has lapsed.**

## Pattern selected

**Server Session** — per-client state kept on the server, found by an identifier, surviving requests
that carry no memory of the client. Taken from the catalogue by the architect seat at
`catalogue:java-design-patterns/server-session/README.md` (reference implementation
`com.iluwatar.sessionserver.App`).

*Consequences, as the pattern's own README states them:* client logic simplified, sensitive state
server-side, multi-step flows supported — against server memory growing with sessions, timeout and
persistence logic becoming ours, and poor concurrency scaling.

*Are we willing to pay them?* Two are already paid in shipped code: `SessionLedger` bounds memory at
64 sessions × 200 calls with LRU eviction, and owns expiry as a first-class event
(`EvictionListener`, which `ServerChecks.onSessionEvicted` already reads as *the session ended*). The
third is the live one and is this design's whole contribution: **the pattern's weak point is the
IDENTIFIER**, and we refuse the transport's identifier and make identity a value the caller names.
