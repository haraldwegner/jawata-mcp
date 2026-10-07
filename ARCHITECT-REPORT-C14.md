# Architect watch — C14 (Sprint 28g, Stage 14: the cataloguer)

**Mode:** watch. **Subject:** the C14 fold — the repairs made after round 1 of the checkpoint's
adversarial audit refused with five blockers. **Verdict: MIXED.**

Run because §7 requires the architect watch at every checkpoint and the audit's own non-blocking
finding 7 established there was no report on disk for this one (C3, C5, C8, C9a and C13 have one).

---

## What was reviewed

| Change | Files |
|---|---|
| A form gate on the closing verb, and the predicate behind it | `DescribedUnits.hasHashShape`, `ExperienceTool.describeDone` |
| The corrected fixture and the refusal it should always have met | `ClosingAUnitSaysWhatItLeavesTest` |
| The missing composition, built rather than carried | `ARecrawlAfterAnEditLeavesOneJobPerMemberTest` (new) |
| D40a's claim withdrawn, its test rewritten to the property that moves | `jawata-hook/src/pipeline.rs` (studio) |
| The overload/duplicate classification corrected by reading | the collapse listing, now `docs/sprints/jawata-mcp/28g-d30-collapse-proposal.txt` in jawata-enterprise |

## D-SIX — passes, and it was measured rather than asserted

`hasHashShape` is not a second implementation of a job the system already does. Every hash-named
member in the workspace either PRODUCES a hash (`DescribedUnits.hash`,
`ExperienceMaintenance.sourceHash`, `ProjectKeys.shortHash`, `ProjectImporter.pomTreeHash`) or reads
stored ones (`DescribedUnits.describedHashes`). Nothing validated a hash's SHAPE before this.

The duplicate check nominated `DescribedUnitsTest#an_edited_unit_re_queues` — a test asserting a
unit re-queues when its text changes. It neither states nor checks the shape of a content key, so
it is unrelated rather than a prior implementation, and that reason is recorded here because the
seat's own rule is that a reason given is not a formality.

## Rule 1 — nothing to rank, and saying so rather than inventing one

No incomplete delegation is introduced. The shape rule sits on the class that produces the shape and
the calling tool ASKS it; that is the direction this seat argues for, not against. An absence is an
answer.

## Findings

### F1 — `hasHashShape` checks a PROXY in place of the PROPERTY. **BANDAGE.**

The clause asks that *a description whose text was never offered* be refused. The predicate asks
whether a string could have been produced by our hash function. Those are different questions, and
the gap between them is the whole set of well-formed hashes of text nobody was offered.

This is the shape the C5 architect run named across four fixes in one commit — *"a proxy is checked
in place of the property"* — where the one that shipped correct was the one naming the property.

**It is declared as a bandage in the code rather than left to read as the clause**, which is the
mitigation available inside a repair round. The design fix, named rather than left open:

- **remember the offers** — a set per unit. Correct, and it puts stored state on a read path, which
  is the cost; or
- **make the offered key SELF-VERIFYING** — a value the server can confirm it issued without storing
  anything. This answers the real property, costs nothing per offer, and is the better shape.

The second is a design change and belongs to whoever next opens this area, not to a repair round.

### F2 — two producers of one hash, with the shape rule beside only one. **FINDING, homed.**

`DescribedUnits.hash`'s own javadoc states it is *"the same function
`ExperienceMaintenance.sourceHash` uses … because both answer the identical question"*. If that
holds, the shape is a property of both, and `hasHashShape` now states it beside one of them.

Nothing breaks today: one caller, one statement. An algorithm change on the other producer would
leave this validating the old shape for its hashes, silently. Homed to whichever stage next touches
either producer; merging them has its own blast radius and no defect behind it yet.

### F3 — rule 12 does NOT fire, and that was measured. **Not a design alarm.**

Three of the five blockers were the agent's own, from the same session, and they are ONE structure
rather than each fix exposing the next face:

| blocker | the cheaper instrument that stood in |
|---|---|
| 1 | a fixture value nobody checked against its producer |
| 3 | a hand-written literal standing for a payload the product sends |
| 4 | a count standing for a reading |

Rule 12 asks whether fixes keep breaking what the previous fix touched. These were independent and
found in one pass by one auditor. A recurring symptom, which rule 12 calls ordinary.

## Verdict per fold, because one grade would blur them

| fold | verdict |
|---|---|
| Blocker 1 — the form gate | **BANDAGE** (F1), declared as such in the code |
| Blocker 3 — D40a's claim withdrawn | **DESIGN FIX** — a false claim deleted, and the test made to read the property that actually changes |
| Blocker 4 — the classification corrected | **DESIGN FIX** — a reading replaced a count at the point the classification is decided |
| Blocker 5 — the composition built | **DESIGN FIX** — and its anchors come from the product's own answer rather than a second derivation, so test and code cannot drift about what a member is |

## Dispatch, not ticket

- **F1** → the next stage opening the describing path: a self-verifying offered key.
- **F2** → whichever stage next touches either hash producer.
- **F3** → no actuator; recorded as a symptom.
