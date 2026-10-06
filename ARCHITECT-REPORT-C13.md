# ARCHITECT-REPORT-C13 — Sprint 28g Stage 13

**Mode:** watch. **Scope:** the C13 checkpoint diff — 13 commits in jawata-mcp plus
`c67b047` in jawata-studio. **Verdict on the run: MIXED — six design fixes and one
UNFIXED instance of the very defect two of them cured.**

**Gate:** `compile_workspace` 0 errors / 0 warnings over 4 projects. Scope is pure: the
diff touches `org.jawata.mcp.knowledge`, `org.jawata.mcp.tools`,
`org.jawata.mcp.tools.smell`, one studio component and two fixture files.

**D-SIX checked against the catalogue.** `org.jawata.mcp.knowledge` IS catalogued —
`describe action=area` answers 1,657 jobs, capped to 5 shown, so what I was handed is a
SAMPLE and I draw no population conclusion from it. One of the five is decisive and is
quoted under F1.

**Rule 14 history, which I was handed rather than inferred.** `ExperienceStore.java` was
repaired FOUR times in this effort (`6bfa3451`, `78c9af0c`, `2d12443f`, `1f82e916`), and
`appliedSampleCount`'s own lines twice. The place repeats; that is this rule's trigger
and it is why F1 below is about the run rather than the last patch.

---

## Findings (ranked — incomplete delegation first, rule 1)

### F1 — INCOMPLETE DELEGATION. D40c has the defect D25 and D28 were just cured of, and it is UNFIXED.

`RecoveringExperienceStore` is a delegating wrapper, and production serves through it.
Enumerated over the interface's 12 `default` methods:

| wrapper overrides | members |
|---|---|
| **9** | `attachSource`, `changeStamp`, `moveSymbolAnchor`, `provenanceWorkspaceId`, `putWithSource`, `setProvenance`, `sourceUnchanged`, `updateSymbolAnchor`, `upsertBySource` |
| **3 — the default answers instead** | `recordStoryFolder`, `storyFolder`, `storyFolderAmbiguous` |

**Those three are D40c's entire deliverable.** Through the wrapper:

- `recordStoryFolder` is a NO-OP, so the loader's recording of each root is discarded;
- `storyFolder` answers EMPTY always;
- `storyFolderAmbiguous` answers FALSE always.

So "the store records its folder instead of deriving it" does not hold in production.
`substrateBlock` would answer *"none: no folder was recorded"* on every machine, and the
accepted-story export would write nothing. **D40c did not merely fail to improve the old
behaviour — it replaced a wrong answer (the home-directory ancestor) with no answer**, and
both are wrong differently, which is the shape rule 12 is about.

**This is the third instance of one structure in one stage.** D25 (`742d0707`,
`1f82e916`), D28 (`639d681e`, `1f82e916`) and D40c — same mechanism every time: *a member
added to the interface with a `default`, and a wrapper that only forwards what it
declares.* Two were fixed and the third was not even looked for, although the fix for the
first two is exactly the enumeration that would have found it.

**THE CATALOGUE ALREADY HELD THE JOB THIS KEEPS RE-DERIVING.** `ExperienceStore#degradedNotice`'s
recorded job, quoted from the store: *"Lets a store say why its answers cannot be trusted
right now, so that retrieval asks before reporting an absence and says 'unavailable'
instead — because a store serving from a fallback still answers, and its empty answer is
shaped exactly like 'I looked and there is nothing'."* Every one of these three defects IS
that sentence: a store serving from a fallback answered, and its empty answer was shaped
exactly like a real one.

**DESIGN FIX, not another forward.** Adding three more overrides cures this instance and
leaves the structure that produced three. The member is the interface's own rule:
**`ExperienceStore` must not carry a `default` for anything whose honest answer depends on
which store you are holding.** A default is correct only where the answer is the same for
every implementation. All three of D40c's fail that test, as did D25's and D28's.

**Actuator:** a parity-gated `refactoring(action=plan)` is NOT the right instrument here —
there is no behaviour-preserving transform for "delete a default and implement it in three
places", and the compiler is the gate. The step is the one `1f82e916` already performed,
applied to the three members it missed: remove the defaults, let the compiler name
`H2ExperienceStore`, `RecoveringExperienceStore` and `UnreadableStore`, and implement each.
Estimated three overrides and one forward, with `TheWrapperForwardsBothNewReadsTest`
extended by a third case.

### F2 — RULE 12 ALARM, and it is measured rather than asserted.

The hollow-wiring gate failed THREE times on one member, and each fix broke something
DIFFERENT from the one before it:

| run | the gate's report | the cause found | the fix |
|---|---|---|---|
| 1 | called only by tests | no production caller at all | `742d0707` |
| 2 | called only by tests | interface default, wrapper never forwards | `1f82e916` |
| 3 | called only by tests | reached polymorphically; the gate asks for a concrete-type caller | baselined, `2371b474` |

Three different causes behind one symptom is the signature this rule names. **The run is
the target, and F1 is what the run was moving around.** The baseline entry at step 3 is
correct ON ITS OWN TERMS and I am not disputing it — but it closed the symptom while the
structure was still live in a third member, which is why F1 outranks it.

**What I will NOT say**, because the record forbids it: that the third fix was wrong
because "this one is different". That sentence is true every time. It happens also to be
true here — the evidence is the 143-entry baseline already carrying `JdtServiceImpl#dispose`
and `SearchService#getProject`, the same polymorphic shape — and the author verified the
update rather than trusting it (109 → 110, zero removed). That is why F2 is an alarm about
the RUN and not a finding against that commit.

### F3 — THE HOLLOW GATE CANNOT SEE A POLYMORPHIC CALL, and 110 findings is a lot of blindness.

Established at F2 step 3: the gate asks whether a method has a non-test caller ON ITS OWN
DECLARATION. For an interface implementation reached through the interface, the answer is
always no, and the only cure available is the baseline. The sweep reports **110 findings of
kind `called_only_by_tests`** and the baseline carries **110 accepted members** — so this
gate's entire standing output is a list it cannot distinguish from genuine dead code.

That matters because of what it just did RIGHT: on runs 1 and 2 it found two real
production defects nothing else had. An instrument that valuable should not have an
accept-list growing by one every time an interface gains a member.

**DESIGN FIX:** the predicate is the wrong one. "Does any production code call this method
on a concrete reference" should be "is this method reachable from production at all",
which for an override means asking whether any production caller invokes it through a
supertype the class implements. JDT answers that already — `get_call_hierarchy(incoming)`
on the override listed `ExperienceRetrieval:1091` while the gate did not — so the two
instruments disagree today and one of them is the product's own API.

**Actuator:** the test-writer seat is wrong for this; it is a detector change in
`TestOnlyCallerDetector`, and its control is the 110 baseline entries — the right ones
must stay flagged and the polymorphic ones must stop being.

---

## Dispatches

| finding | actuator |
|---|---|
| F1 | remove the three defaults; implement in the three stores; extend the wrapper test. Compiler-gated, no plan kind applies |
| F2 | no separate actuator — F1 IS the run's cure |
| F3 | `TestOnlyCallerDetector`, with the existing 110-entry baseline as the control |

## Reviewed diffs — design fix or bandage

| commit | verdict |
|---|---|
| `6bfa3451` D25 | **DESIGN FIX** with a latent defect — the count is right and the `default` was the error F1 is about |
| `78c9af0c`/`4388174f` D28 | **DESIGN FIX.** Reading the tree first and finding half the deliverable already shipped in 28c is the behaviour this seat wants; and the mutation that would not fail was acted on rather than filed |
| `2d12443f` D40c | **BANDAGE, unknowingly** — see F1. The derivation it removes was genuinely wrong; what replaced it does not reach production |
| `ade9c665` D40b | **DESIGN FIX.** The boundary is real and the test's own correction (reading the index rather than the body) found the actual subject |
| `e6e31204` D24 | **DESIGN FIX.** One fact, two doors, now saying the same thing |
| `fe194950`/`d4630003` D29 | **DESIGN FIX, and the best of the run.** The cause was identified as WHICH types counted rather than the threshold, which is the difference between a fix and a tuning; and the mutation that stayed green corrected the fixture instead of the claim |
| `639d681e`/`742d0707` wirings | **DESIGN FIX** for what they addressed, **incomplete** as F2 records |
| `1f82e916` | **DESIGN FIX** — removing the defaults is the structural answer. It is also F1's evidence that the enumeration was not run |
| `2371b474` baseline | **CORRECT**, and verified rather than trusted |
| `ca651122` probe | **DESIGN FIX.** Two proof-of-life refusals, and the capped-page answer reported as "not in the top 60" rather than "absent" |
| studio `c67b047` D27 | **DESIGN FIX.** No hardcoded namespace list, undefined rather than zeroes, and the transport verified rather than assumed |

## Deliberate duplicates

None. No change in this diff orders a second implementation of an existing job.

## Below the fold

- `ExperienceStore` is now 40+ members on one interface; the store, its curation surface
  and its hygiene audit are three roles sharing one type. Not this checkpoint's work.
- `ExperienceTool.substrate()` reads `StoreCorpus.of(store).all()` to count entries and
  then discards the rows — a full table scan for a number.
- `headingShapedSummaries` walks `all()` in memory; fine at 10k rows, not a query.
- The M7 instrument is authored by the agent it judges, which its own first section says.
  Replacing it is a task for someone else and is already raised as F-28g-13b.

## Skipped by record

- The `data` door's missing change-returning seam (C5 F1) — homed to the seam lane,
  unchanged, not re-argued.
