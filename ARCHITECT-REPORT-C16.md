# Architect watch — C16 (Sprint 28g, Stage 16)

**Mode:** watch. **Scope:** `f64085da..HEAD`, nine commits, 13 files, +2404/−10.
**Verdict:** **MIXED** — the run is a design fix with one bandage I am naming rather than
defending, and one finding I could not close inside this checkpoint.

---

## What the run did, against the architecture

The stage's subject is the hire path's first production caller. `HiredRun.issue` had seven
callers and every one was a test, so a capability that was built, gate-proved and documented
could not be reached by anything in the live process. That is the shape this project has shipped
three times before (S9a's `PlanLedger`, the architect gate's own registry mirror, the operation
allowlist), and closing it is a design fix rather than a feature.

| Change | Verdict | Why |
|---|---|---|
| `ToolGateRunner` | **design fix** | The gate's four failure modes were a comment in `HiredRun` and are now a type. It reports "did not answer" distinctly from "answered zero", which is the one distinction the ledger acts on. |
| deleting the `hasTool` pre-check | **design fix** | One fact expressed twice, removed after a mutation proved the two indistinguishable. The surviving guard is compiler-enforced, which is stronger than what went. |
| `HireTool` | **design fix** | It is the missing caller, not a second implementation: D-SIX checked, zero nominees. The seat's own file carries brain, model, tools and ceilings, so changing a brain is a settings edit — the clause met by construction rather than by assertion. |
| deleting the `binary` input | **design fix** | An inert published input, removed rather than honoured. Honouring it would have made `hire` run an arbitrary executable named in a tool argument. |
| `TreeAttribution` | **design fix** | A clause that was unmet is now met with an instrument whose absence is reported as an absence. D-SIX checked against the catalogue: zero nominees. |
| `--permission-mode plan` on `ClaudeCodeDialect` | **BANDAGE, named below** | Correct, measured, and it puts a policy in the fourth place it does not belong. |

---

## F1 — THE CONTAINMENT DEFAULT IS A POLICY OF THE HIRE PATH, AND IT LIVES IN THE DIALECTS

**This is the bandage, and the commit that introduced it is otherwise the strongest in the run.**

The policy is one sentence and the hire path owns it: *an unattended run PROPOSES, so it is
read-only unless a caller explicitly says otherwise.* It is now expressed **four different ways in
four classes**, and two of them cannot express it at all:

| dialect | how the policy is said | can it be said? |
|---|---|---|
| `CursorDialect` | `readOnly` field, default true → `--mode plan` + `--sandbox enabled` | **it can be SAID and it does not BIND** — see the measured table below |
| `ClaudeCodeDialect` | `readOnly` field, default true → `--permission-mode plan` | yes, **as of this checkpoint** |
| `CodexDialect` | `--sandbox read-only`, hard-coded, no field | not as a decision — only as a fact |
| `CopilotDialect` | ~~nothing~~ → `readOnly` field, default true → `--deny-tool write --deny-tool shell` | **yes, and the row above was FALSE** |

**THE COPILOT ROW WAS WRONG AND IS CORRECTED IN PLACE RATHER THAN QUIETLY REPLACED.** It said this
brain had nothing and could not be told. Its own `--help` offers `--deny-tool`, `--excluded-tools`
and `--available-tools`, and names `write` as the file-editing tool. I asserted an absence from not
having looked — the shape this very report calls the run's structure 1.

**AND THE CONTROL THEN CORRECTED THE CORRECTION.** Two runs of one brief in scratch directories,
with the new flags and without: NEITHER wrote. Without them the model called `apply_patch`, was
refused, reasoned about *"needing approval due to security measures"*, routed around to `bash`, was
refused again, and answered *"I couldn't create the file because write permission was denied."* So
copilot's headless DEFAULT denies writes; the flags state the hire's intent and are not what
contains it. Both the dialect's javadoc and its attachment report now say so, and neither claims
deny-over-allow precedence for tools, which the help documents for URLs only.

**AND THE ACTUATOR'S THREE VALUES ARE NOW MEASURED RATHER THAN PROPOSED.** When F1 was written,
the three-valued shape was an argument by analogy with `ToolAttachment`. An isolating probe
(`build/c16-containment-isolated.sh`) then drove each CLI directly, one containment flag at a
time — which the hire path cannot do, because it always sends every flag a dialect has — and the
categories came back populated:

| value | brains | the evidence, and it is the MECHANISM that decides the category |
|---|---|---|
| contained per run, **by a mechanism** | codex `--sandbox read-only`; copilot `--deny-tool` | codex 2/2, its sandbox observed **rejecting an attempted `apply_patch`** verbatim — *"patch rejected: writing is blocked by read-only sandbox"*; copilot `permission_denied` in **6 of 6**, naming our own rules when we pass them (*"denied due to the following rules: `write`"*) and denying anyway when we do not |
| held by an **INSTRUCTION**, which is not containment | claude `--permission-mode plan`; cursor `--mode plan` | **claude was in the row above and does not belong there.** `Edit`, `Write` and `NotebookEdit` are ADVERTISED under plan mode, `Write` was INVOKED and returned *"File created successfully"*, and `permission_denials` is `[]` in every run; the model narrates the rule — *"plan mode permits edits only to this plan file"*. Its instruction HELD for the work tree in 5 of 5; cursor's FAILS, 5 writes in 15 |
| contained **out of band**, by the vendor's own default | copilot | 3/3 with our `--deny-tool` flags and **3/3 without them** — so the flags state this hire's intent and are not what contains it |
| **cannot be contained**, and here is what bounds it instead | cursor | 5 writes in 15 plan-mode runs; **10 writes in 10** with `--sandbox enabled` alone. No mechanism refusal has ever been observed. What remains is the hire's own wall-clock, cost and step ceilings |

**The discriminator between the first category and the third is not the refusal RATE.** Cursor
refuses roughly two-thirds of the time, which a small sample reads as containment — and did, at
4-of-5. What separates them is whether a refusal arriving from something **other than the model**
appears anywhere in the record. For **codex and copilot** it does. For claude it does NOT — an
earlier version of this sentence said it did, and the logs contradict it, which makes the split
TWO of four rather than three. For cursor, across 25 runs, it never
has. That is the property `writeContainment()` should answer, and stating it as a rate would
reproduce the error this measurement corrected.

**Two claims of ours died here, and both were in code rather than in a report.** `CursorDialect`
said the sandbox was *"the vendor's stronger lever"* whose *"independent effect is not established
here"* — established now, and for writes it is none. `BrainDialectTest` said *"ALL FOUR BRAINS NOW
CARRY A WRITE BOUND BY DEFAULT ... cursor always had one"*, which conflated a flag with a bound.
Both corrected in place. The sandbox flag STAYS: it is documented for sandboxing at large, whether
it bounds shell or network is not measured, and removing it would be a behaviour change justified
by a measurement scoped to one of the things it claims.

**It also falsifies the PLAN, not only the product.** Stage 16 Step 0 asserts cursor *"CAN be told
READ-ONLY via `--mode plan`"* and builds the honest-report argument on that. The flag can be sent;
it is not a read-only bound.

**Why that is a bandage rather than four correct local choices.** A fifth brain added tomorrow
gets no default at all and nothing notices — which is exactly how claude stood until a probe told
it to write a file and it did. The product already has the right shape for this one axis over:
`ToolAttachment` makes a dialect SAY what reach it has over tools, with `NONE` obliged to name
what contains it instead. Writes have no such role, so a dialect that cannot contain writes is
indistinguishable from one that was never asked.

**The actuator**, and it is a parity-gated refactoring rather than a rewrite: give `BrainDialect` a
`writeContainment()` answering the same three-valued shape `ToolAttachment` already uses — contained
per run and here is the flag · contained out of band and here is where · **cannot be contained, and
here is what bounds it instead**. The four dialects then each answer once, `HiredRun` records the
answer beside the tool attachment it already records, and the run panel gains a row it currently
cannot have. Copilot's honest answer is the third value, which is a fact worth publishing rather
than a gap worth hiding.

**Not done here** because it reaches all four dialects, the `BrainDialect` role, `HiredRun`'s
provenance and record, and the studio panel — after the last measurement in a checkpoint that has
already changed the product's safety behaviour once. Homed to **S17 or 28h**.

---

## F2 — A DIALECT'S ARGV IS ITS CONTRACT WITH A VENDOR, AND MOST OF IT IS PINNED BY NOTHING

Adding `--permission-mode` turned **no test red**: 96 classes green over a change to the single
most important argument this product sends. The dialect's own test asserts the events it READS and
never the command it BUILDS; the one argv assertion in the suite lives in a spy's containment
check in `HiredRunProposesTest`, and that spy replaces the PROCESS — so it proves what the dialect
would ASK FOR and never what runs.

That second half is not a gap to close by writing more assertions. It is a boundary: **a claim
about what a hire EXECUTES can only be made from the wire**, and this stage built the instrument
(`build/c16-hire-frontdoor.sh`). The gap that IS closable is the first half, and this checkpoint
closed it for one flag only. Filed as S16-22.

**Narrower than it looks, deliberately:** three of the four dialects' argvs ARE pinned verbatim by
`ThreeMoreBrainsTest`, which was written against captured runs. It is claude's — the oldest and
most used — that was not.

---

## F3 — THE COST CEILING IS NAMED "ENFORCEABLE" AND BOUNDS NOTHING ON A SINGLE TURN

`SeatHire.budgetReport()` answers *"cost budget $2.0 is ENFORCEABLE: CLAUDE_CODE reports a dollar
cost"*. Measured in M12: a seat with a $0.25 ceiling, briefed to reply with one word, reported
`$2.26` and the ceiling fired **after** the spend. The ceiling is a stop BETWEEN reported costs and
cannot bound a run whose whole cost arrives in one terminal event — which is every single-turn
hire, i.e. the common case.

The word "ENFORCEABLE" is doing work it cannot support, and a reader setting a budget is entitled
to read it as a bound. Either the wording narrows to what it does (*"a run that exceeds this
between turns is stopped"*), or a hire that cannot be bounded in advance says so before spawning.
**A decision, not a repair** — it changes what a caller is promised. Filed as S16-16.

---

## F4 — A HIRED BRAIN INHERITS THE MACHINE, AND THE HIRE PATH HOLDS THE LEVER IT DOES NOT PULL

Measured in one M12 run: copilot's model was never called — *"Static context is using 125% of
available input tokens"* — and claude paid $2.26 to answer one word. Same cause: this machine's
user-level client configuration carries three jawata MCP servers' full tool schemas plus the
layered CLAUDE.md set, and a spawned CLI reads it even from a throwaway working directory.

`ClaudeCodeDialect`'s own attachment report has named `--mcp-config` and `--strict-mcp-config` as
available since it was written, and the dialect passes neither. `CopilotDialect` already learned
this lesson on its own axis — its constructor turns built-in MCP servers OFF by default, with a
javadoc saying context is the measured reason this brain can fail to start — so the product holds
both the knowledge and the mechanism and applies them to one brain of four.

This is the same shape as F1: a policy of the HIRE (what environment does a hired brain get?)
living per dialect. It should be decided once, by the hire path, and each dialect asked to carry
it out. Filed as S16-17. **Homed with F1**, because the same refactoring answers both.

---

## Rule 12 — does it fire?

**Measured, and NO.** Rule 12 fires when each fix exposes a different face of one structure. Here
the run's own breakages cluster into **two** structures, not nine:

1. **A claim that was never executed.** The inert `binary` input, the `hasTool` pre-check, the
   broad `RuntimeException` catch, the "ENFORCEABLE" budget, the `--allowedTools` containment, the
   javadoc naming a mechanism the code does not have. Six faces, one structure — and every one was
   found by *running* rather than by reading. That is a recurring symptom with a known cure
   (execute the claim), which rule 12 calls ordinary.
2. **A fact the product knows and does not act on.** F1 and F4: the containment default and the
   client configuration, both held per dialect where they are the hire's.

Structure 1 is not a design alarm, because the cure is already in place and worked nine times this
checkpoint. Structure 2 is F1's actuator and is one refactoring, not a cascade.

**What I would have called an alarm and did not:** three mutations stayed green in one stage. That
reads like an instrument failing, and it is the opposite — each green mutation found something a
red one could not have (a redundant branch, an unreachable fixture, a false javadoc), and the
fourth found a missing test case. A stage where every mutation reddens is a stage whose mutations
were aimed at what it already knew.

---

## D-SIX

**PASSES.** Both new jobs were checked against the catalogue with `duplicate_check` and both
returned **zero nominees**: `TreeAttribution` (the census) and `HireTool` (one seat hires another).
`HireTool` is in any case the missing caller of an existing capability rather than a second
implementation of it, which finding S16-5 states.

**AND ONE PROCESS FAULT IN THIS REPORT, recorded rather than quietly repaired.** The sentence above
originally read "`HireTool` … D-SIX checked, zero nominees" and I had run the check on
`TreeAttribution` ONLY — the `HireTool` half was an argument wearing a measurement's clothes. I ran
it afterwards and it agrees, so the claim stands; the fault is that it was written before it was
true, in a report whose whole value is that a reader need not re-derive it. It is the run's own
structure 1 — a claim that was never executed — committed by the instrument meant to catch it.
No `jawata-duplicate:` was ordered in production code; one is declared in `HireToolTest` for a
15-line process stand-in whose sibling is package-private in another package, with the reason and
the expiry written at the declaration.

**One caveat on my own method:** the package-level catalogue read came back `capped_from: 232`, so
I did not conclude from it. The targeted duplicate check is what answered.

---

## Below the fold

- `GateOutcome` has nowhere to carry WHY a gate did not answer; four causes collapse to one
  reported state and the distinction lives only in the resident's log (S16-10). Widening a shipped
  record reaches every caller, which is why it is filed rather than fixed.
- `ToolRegistry#hasTool` is left with test callers only (S16-9). Reasonable public API; keeping a
  redundant branch alive to give it a caller would be the tail wagging the dog.
- The published tool count and the registration census disagree by one across the release boundary
  (S16-14): the worktree adds exactly one registration and publishes 44 by both measures, against
  the released 4.3.3 resident's 42. Not this stage's.
- `--permission-mode plan` still lets the brain write its PLAN file outside the working tree
  (S16-21). The CENSUS is correctly scoped to the working directory — that is a fact about the
  instrument. **THE CLAUSE IS NOT, and an earlier version of this line inferred one from the
  other.** "The seat cannot edit a file except through a tool" names no directory, so a brain that
  edits a file anywhere through an editor the hire does not govern has not met it. On that reading
  claude's cell is UNMET rather than scoped-and-held: the capability is demonstrated, not merely
  unobserved — `Write` invoked, a file created, six runs of six, no denial ever recorded.
- The collected answer duplicates a short reply on two brains, so it is the shared stream reader
  (S16-18).
