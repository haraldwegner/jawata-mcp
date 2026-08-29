# ARCHITECT — WATCH MODE, Sprint 28d Stage 9 checkpoint diff

**Scope:** `bc535a2`, `939af3b`, `63691ff` on `sprint-28c-rescue`, judged against
`ARCHITECTURE-28d.md`. Stage 9 is D3 — the round trip has a fixed point we did not
author.

**Incomplete delegation, ranked first per standing rule 1: NONE, and the category is
empty by construction here.** Stage 9 added **zero production source** — 368 lines of
tests and one E2E block. `find_quality_issue(kind=incomplete_delegation)` on the one
production type the stage newly depends on (`InlineTool`) returns **0 findings, scan
COMPLETE**. A real absence, not a failure to look.

---

## The question this diff actually raises

A stage that ships no production code is either exactly right or a skipped build, and
nothing about the diff itself distinguishes them. So that is the finding to settle,
and it settles in the stage's favour — but only because of what the stage measured
first.

**D3 is a PROPERTY to verify, not a capability to build.** Its own measure says so:
*"the round-trip property test passes against human-written originals."* A stage whose
deliverable is a property is correctly all-test. Compare Stage 8, whose deliverable was
two operations and which shipped 909 lines of production code.

**But the stage could have been a build, and the measurement is what ruled it out.**
The trip needs both directions for one pattern. Counted off the front door's own
description: **8 TOWARD operations, 2 AWAY, and no pattern carries both.** The
alternative to the test-only shape was to build an inverse operation — a toward-
singleton, or a lambda-to-anonymous — which is a rank-2-scale build for the sake of a
property test, immediately after C8 cut four operations of exactly that cost. Not
building it is consistent with the cut, and the cut is the human's standing decision.

---

## Findings (ranked)

### F1 — the round trip is proven for ONE pairing, and the report should not read as proving the operation set

The property holds for `replace_constructor_with_factory` inverted by
`inline(kind=method)`. That is one pair out of ten operations. Nine operations have no
inverse and are therefore unmeasured by this property — including both of Stage 8's,
one of which is the same operation viewed from its other end.

**This is not a defect and no fix is proposed.** It is a scope fact that the stage
states honestly in its own commit message, and the reason to raise it here is that
"the round trip has a fixed point" reads, in a status summary, as a claim about the
refactoring engine. It is a claim about one pair. The plan and the report should keep
saying so, and C9's close should not round it up.

### F2 — the reversed direction loses a real check, and the loss is the interesting half

D3 says AWAY then TOWARD from a canonical implementation. The stage runs TOWARD then
AWAY from unpatterned code, because the literal direction has no usable corpus.

> **CORRECTED after the C9 audit, which REFUSED partly on this paragraph.** It said
> the fork holds "six self-returning static factories, in four classes, every one
> carrying Lombok". That was false, and **this report repeated it without re-running
> it** — which is the watch-diff's own failure, not just the stage's: an architect pass
> that accepts a number because it appears in four places has checked nothing. The
> measurement is now reproducible (`build/survey-self-returning-factories.py`) and
> reports **9 sites in 6 classes**, one dependency-free.
>
> The conclusion holds on a different reason: that one site, `monad/Validator.of()`,
> has a **private constructor**, and the trip is only defined where the old path stays
> open. Zero of the nine qualify.
>
> **CORRECTED AGAIN at round 3.** This block previously ended "constructor
> accessibility is the blocker, not Lombok", which is also false: **8 of the 9 are
> dependency-blocked and 5 are private-constructor-blocked**, so dependency blocks
> MORE. Neither filter alone reaches zero. The precise claim is that the one site the
> dependency filter leaves is removed by accessibility — both are load-bearing.
>
> **That this report needed the same correction twice is the finding about the report.**
> A watch-diff that repeats a number because it appears elsewhere has checked nothing,
> and a watch-diff that repeats a *correction* incompletely has done it twice.

What the reversal costs: the literal form would test that our TOWARD direction
**reproduces a human's chosen factory shape**. The reversed form cannot. And the two
shapes are known to differ — a human writes an instance factory on a separate type;
this operation writes a static method on the type itself.

**That gap is the more valuable of the two checks**, because it is the one that would
catch our operations drifting away from how people actually write the pattern. It is
declared in the commit, the test javadoc and the plan, which is the right handling —
but it should be carried as an open item with a home rather than left as a sentence
in three places, or it will be re-discovered rather than remembered.

### F3 — the E2E promise changed KIND, and the change is right but undeclared as a precedent

Every earlier stage's E2E promise asserts that an operation **stages** — nothing
written, a `changeId` returned — because those promises are about reachability through
dispatch. S9.2 **applies**, because the property under test is about the writes: it
needs the undo path, the compile gate and the file mutations in play, and a staged
change exercises none of them.

Correct for this stage. Worth naming because it is the first applying promise in the
script, and the next author will copy whichever neighbour they happen to read. One
sentence in the E2E block saying *why* this one applies would prevent a staged promise
being "fixed" into an applying one, or the reverse.

---

## Dispatches

| Finding | Actuator |
|---|---|
| F1 | none — a scope fact to keep stating, not a change |
| F2 | the carried-findings dossier, with a home. Not a refactoring; no plan kind applies |
| F3 | one comment in `build/end-to-end-test.sh`, already largely present in the commit message |

## Trend (baseline diff)

| Gate | Result | Against |
|---|---|---|
| e2e | **104 passed / 0 failed** | 99 before Stage 9's five checks |
| production source added | **0 lines** | Stage 8 added 909 |
| `incomplete_delegation` on the newly depended-on type | **0, scan complete** | — |

The full suite, abort budget and dead-code gate were running when this was written and
are NOT claimed here.

## Reviewed diffs — design fix or bandage

**DESIGN FIX**, on three points:

1. ~~**The stage measured before it designed, and the measurement changed the
   design.** The 8/2 count and the six-Lombok-factories survey were both taken before a
   line was written, and both are recorded with their method.~~
   **WITHDRAWN at C9 — this was false in its second half, and the auditor caught it.**
   The 8-TOWARD/2-AWAY count does carry its method (counted off the front door's
   description, operations enumerated). The factory survey carried **no method at
   all**: four artifacts said "the fork was surveyed" and none said how, so nothing in
   the repository let a reader re-run it — which is precisely how a wrong number
   reached the dossier unchallenged, through this report. It is a file now.
2. **The instrument is shown to discriminate.** The first attempt was REFUSED by JDT
   ("Selected entity is not a constructor invocation or definition"), and that red is
   kept in the commit message as the evidence the test can fail. A property test that
   has never failed is a property test nobody has checked.
3. **The author found the vacuity hole in his own test and closed it.** The first green
   compared the slice against its pristine bytes — which two no-op operations satisfy
   perfectly while reporting success. A per-iteration proof of life now requires the
   midpoint to differ. This is the exact shape the C8 auditor named one stage earlier;
   finding it unprompted is the behaviour the audit was supposed to teach.

**Contract observation (rule 5(f), silence forbidden).** Stage 9 changes **no
contract**: no signature, schema, response payload or serialized format moved. The
producers and consumers are therefore unchanged and no other side needs to change.
The one new production dependency is a test's use of `InlineTool`, which is an existing
registered front door used through its published interface.

## Below the fold

- The property runs on six originals where C9 asks for three. Free, and it caught
  nothing extra — all six behaved identically, which is itself mild evidence that the
  shape rather than the class is what the operation keys on.
- `protectConstructor=false` is load-bearing in both the unit test and the E2E, for the
  same reason in both places. It is commented in both, which is the right redundancy —
  a reader of either will meet it.

## Skipped by record

None.
