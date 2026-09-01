# Architect watch-diff — the v3.14.0 → v4.0.1 release run

Run at the patch-streak gate's own instruction, after it refused v4.0.1.

**The gate's question:** is this run one design flaw being moved around, or
independent fixes that coincided?

**Answer: ONE FLAW, moved around at least seven times — but not the flaw the
gate assumes.** Its text expects "each patch breaks something DIFFERENT from the
last". That is not what happened. Each patch fixed a different *instance of the
same defect class*, and the class is not in the product's features. It is in the
code that produces **verdicts**.

**Evidence basis, stated because it bounds the finding:** commit subjects for the
ten tags, not their diffs; full knowledge of v4.0.1's four fixes and the three
gate defects found on release day, because this agent made and fixed them.
Measured: **19 of the run's 124 commits touch a gate or guard.**

## Findings (ranked)

### F1 — A gate that cannot distinguish its own three states

Every gate answers one of three things: *I checked and it holds*, *I checked and
it is broken*, *I could not check*. The third keeps collapsing into one of the
first two, and each collapse shipped as its own release:

| Release | The collapse |
|---|---|
| v3.14.0/.1 | an unwired baseline entry carried no reason — "accepted deliberately" indistinguishable from "accepted by accident" |
| v3.14.2 | a guard added to make delivery *honest* — the same shape, named |
| v3.17.2 | the hollow-wiring gate **discarded the error explaining its own failure** — "broken" indistinguishable from "could not check" |
| v4.0.0 pre-release | the load guard would have gone **green on its own motivating incident** — "quiet machine" indistinguishable from "blind guard" |
| v4.0.0 release day | the streak gate exited 1 printing **nothing at all** — "the fetch failed" indistinguishable from "the gate refused" |
| v4.0.1 (3) | the staleness guard read the launcher — "genuinely stale" indistinguishable from "the launcher didn't change" |
| v4.0.1 (4) | the sweep deadline failed healthy runs — "regression" indistinguishable from "slow machine" |

Seven instances. The store cluster (v3.15.0, v3.16.0, v3.17.0, v3.17.1 —
*"reports what the store knows"*, *"reports its own drift"*, *"only deletes what
it can rebuild"*) is the **same abstraction one layer over**: an output
collapsing two distinguishable states into one value. The store's own recorded
lesson already names it — *"not declared" and "we failed to read it" became THE
SAME VALUE*.

**Why it recurs.** Every gate re-implements decide-then-report, and the
reporting half is an exit code or a boolean. A shell gate's vocabulary is
`exit 0` / `exit 2`; there is no third word, so the third state has nowhere to go
and is silently spent as one of the other two.

**DESIGN FIX — give the verdict its behaviour.** One `Verdict` type, shared by
every gate, with three constructors and no fourth: `holds(evidence)`,
`broken(reason)`, `couldNotCheck(why)`. A gate returns a `Verdict`; the runner
maps it to an exit code. The distinction stops being each author's discipline and
becomes unrepresentable-as-one. The shell gates take the same contract as a
required output line before any exit.

This is the standing bias applied literally: the data (which state, and why) and
the logic reporting it live apart, so the logic is rewritten per gate and drifts.
Move it into the object.

### F2 — Gates ship unexercised, and the first firing is production

The streak gate was added *in the release it first ran on*, and failed. The load
guard would have passed its own incident. Sprint 27's headline gate has never run
for want of a corpus. A gate that has never produced its non-trivial verdict is a
claim, not an instrument — a detector that never fires and a corpus with nothing
to find are byte-identical.

**DESIGN FIX:** a gate ships with a control that makes it fire — the red-then-
green pair. The staleness repair did this and it earned its keep immediately: the
first attempt made a 2021 third-party jar the reference, so every correct tree
would have been refused. Worse than the bug, caught in one run, never left the
working tree.

### F3 — The reference a gate measures against is chosen, not derived

Three of the recent defects are one mistake: an arbitrary reference standing in
for the real quantity. The load guard used a load-average threshold at half the
core count (the incident ran below it). The staleness guard used the launcher as
proxy for "the dist" (the module that changes least). The sweep used 120 s as
proxy for "too long" (a hang backstop priced as a latency budget).

**DESIGN FIX:** a gate states the quantity it means and derives its reference
from that quantity. "Is another heavy job running" is a process lookup, not a
load number. "Is the dist older than the source" is the oldest artifact *we
build*, not one file. "Has it hung" is far above the worst legitimate run.

## Reviewed diffs — design fix or bandage

| Change | Verdict |
|---|---|
| (1) classpath cache persists | **DESIGN FIX.** The key was already a content hash; persisting is the correct consequence. The existence check is the design work — it names the half the hash does not cover instead of assuming it |
| (2) cache cap 64 → 512 | **BANDAGE, acceptable as one.** Still a chosen number. With disk behind it a miss costs a file read, so the cliff is gone rather than moved — but the smallest design alternative is an eviction policy, not a bigger constant |
| (3) staleness reference | **DESIGN FIX** — replaces a proxy with a derivation, and carries its control |
| (4) sweep deadline 120 s → 600 s | **BANDAGE**, and the right one: a backstop belongs far from the work. But 600 is chosen too, and the design answer is that a hang is detected by *no progress*, not by elapsed time |

## Dispatches

- **F1** → `refactoring(action=plan)`, kind `extract`, target: a `Verdict` type in
  `org.jawata.core`, then migrate the Java-side gates one at a time,
  parity-gated. The shell gates take the contract, not the type.
- **F2** → the test-writer seat: every existing gate owes a control that fires
  it. Start with the three that have never fired in anger.
- **F3** → no new mechanism; it is a review question F1's type makes askable,
  because `holds(evidence)` forces the author to name what was measured.

## Trend

The run is **not** degenerate patching. Nine of ten releases shipped real
capability or real repair, and the suite grew 2109 → 2246 across it with zero
failures at the close. What the streak measures correctly is **cadence**; what it
cannot see is that the cadence is driven by one unfixed defect class, which is
worse news than the streak itself.

## Recommendation — advisory; the ranking is the human's

**Ship v4.0.1.** The four fixes are correct, proven, and two repair live defects
in what users are running now. Withholding them to protest a pattern punishes the
wrong thing.

**Then take F1 before the next feature.** On this evidence there will be an
eighth instance, and it will cost another release. The streak gate is right that
the action is not another fix — but the action it should provoke is F1, not a
pause.

## Below the fold

- The samples-poms miss (v4.0.0) is F3 in a different costume: version sites
  derived by grepping the *current* version, which finds only files that already
  agree.
- `mcp#66` is F1's reporting half — a title naming the wrong cause survives
  because nothing forced the gate to state what it measured.

## Skipped by record

None — no previously-declined proposal is re-argued here.
