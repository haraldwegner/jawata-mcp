# ARCHITECT REPORT — C3 (Sprint 28g Stage 3, the eight engine defects)

**Produced by the architect seat in WATCH MODE**, read-only, over `c7febfe9..HEAD` (10 commits,
21 files, 2016 insertions). Range corrected before measurement: `b37f12da~1` *is* `230ed0d9`, so
the range first handed to the seat excluded the commit carrying D7a and D7d. Both are reviewed.

**VERDICT for the run: MIXED** — five fixes put the behaviour where the object that owns it lives
(D7b, D7e, D7h, and the placements of D7d and D7g's new mode), but D7f's cap is opt-in at the call
site and 14 of 20 paths did not opt in while ~85 tools now advertise its flag, and D7g's
`overloadAlternative` fires on the success of the very run it says should not have happened.

---

## Per-change verdict

| defect | design fix or bandage | the smallest design-level alternative, if a bandage |
|---|---|---|
| **D7b** `ChangeSignatureTool` 623-690 | **DESIGN FIX** | — one predicate (`offsetIsInsideAnArgument`) serves all three constructor branches, and the javadoc states why it is not a `getType()` check: `this(..)`/`super(..)` have no type node, so they carried the defect unexercised. |
| **D7c** `ChangeSignatureTool` 747-751, 935-950 | **BANDAGE**, honestly so — no gate can catch a literal that compiles and is wrong | Sentinel-ness is a property of the default VALUE, not of this tool. Extract a `DefaultValue` type owning `isSentinel()` and the "no site wanted this" judgement, shared with D7g, so `change_method_signature` *detects* the case and offers an overload instead of only naming the sites afterwards. See Rule 12. |
| **D7e** `MoveClassTool` 179-184, 236-323 | **DESIGN FIX** | — wraps JDT's `MoveInnerToTopRefactoring` rather than hand-rolling the lift; the engine decides the enclosing instance; the first attempt to *configure* the engine was caught by a test and the reasoning recorded. |
| **D7f** `DiffView`, `DiffRenderer.summarize`, 4 wiring classes | **MIXED** | Design-sound where applied — the view owns its presentation, 24,000 is the existing house figure, `diffSummary` is unconditional, and the cap is proven reachable through a real production response. Incomplete as delegation: see **F1**. |
| **D7g** `AddRecordComponentTool` | **MIXED** — `viaSecondaryConstructor` is a DESIGN FIX (it changes the shape, not the sites, and the signature clash is refused rather than generated); `overloadAlternative` is a **BANDAGE** | REFUSE instead. See **F5**. |
| **D7h** `KnowledgeLane.excludesCatalogue`, `ExperienceRetrieval.noteCatalogueExclusion` | **DESIGN FIX — the best of the eight** | — it *asks* `KnowledgeLane.of(...)` rather than restating its answer, so a ruling that moves the catalogue moves this with it; it is a property of the FILTER and not of the result, so it cannot be wrong; and it is written onto recall *and* nominate from one method, including the absence branch — the branch the defect made unreadable. |
| **D7d** `RunTestsTool` 1567-1591 | **DESIGN FIX in placement, BANDAGE in content** | It joins `appendResult`'s own `evidenceNote` idiom and cites the in-tree precedent (`ReDerivedJobDetector#report`). The note names three possibilities without saying *which*, because the selection is not threaded in — and that follow-up is recorded in the code rather than guessed. Correct as far as it goes; the real fix is the signature change it names. |
| **D7a** diagnostic only | **not fixed; honest** | `engineThrew` names the phase, type, message and top four frames. Judged below. |

---

## Rule 12 — is this one structure or eight defects?

**Measured by blaming each defect's pre-fix lines at the parent commit, not asserted:**

| defect | pre-fix line blamed | commit | what that commit was |
|---|---|---|---|
| D7b | `ChangeSignatureTool` 633/641/649 `covers(node)` | `312586ba` | 2026-09-05 "Stage 4 opens: change_method_signature becomes a routing door" |
| D7c | `ChangeSignatureTool` 705 `newArgs.add(newParam.defaultValue)` | `312586ba` | **the same commit** |
| D7e | `MoveClassTool` 173-177 refusal | `661b849a` | 2026-06-28 `goja-mcp v1.0.0` — the 345-file initial import |
| D7f | `AbstractApplyingRefactoringTool` 211 `put("diff", diff)` | `661b849a` | **the same initial import** |
| D7g | `AddRecordComponentTool` 324 call-site loop | `9fa0cba3` | 2026-09-09 "mcp#63: a record gains a component, and every construction of it follows" |
| D7h | `ExperienceRetrieval` 197-204 absence branch | `3788389f` | 2026-07-03 Sprint 21 Stage 2 terminal retrieval |
| D7d | `RunTestsTool` 1553-1566 `evidenceNote` block | `f4891746` | 2026-07-13 sprint23(C2) async test sessions |
| D7a | `ExtractMethodTool` 235/254 engine phases | `fc5f96f2` | 2026-07-14 v2.12.1 "refactorings verify their own result" |

**Reading.** Seven distinct authoring events over four months. **One true shared structure:
D7b + D7c, both `312586ba`** — the node-identification bug and the verbatim-default splice are two
faults of one hand-written call-site rewriter created in a single sitting, work JDT's own
ChangeSignature engine would otherwise own. D7e and D7f share `661b849a`, but that is the
whole-repo initial import, so shared blame there is the baseline and is **not** evidence of shared
structure. The prior architect run's D7b-vs-D7d ruling stands and this measurement agrees: D7d
blames `f4891746`, a feature commit in a different subsystem four months away.

**And one thing blame cannot see, which matters more than the table.** D7c and D7g are **the same
design question answered twice, four days apart, inconsistently.** Both are "a verbatim text
`defaultValue` forced onto every existing call site." D7g learned that a sentinel default means no
site wanted the value, and built both a detector (`isSentinel`) and an escape
(`viaSecondaryConstructor`). Measured: `ChangeSignatureTool` contains **zero** occurrences of
`isSentinel|Sentinel|viaSecondary|overloadAlternative`. So `change_method_signature` will still
splice `null` into N call sites with no detection and no escape, while `add_record_component` now
declines to. **By the house rule that the second occurrence of one defect shape is the design
alarm, this is the second.**

---

## Findings

### F1 — D7f's cap is opt-in at the call site; 14 of 20 paths did not opt in, while ~85 tools advertise the flag *(incomplete delegation)*

**Finding.** MEASURED, and **re-measured independently at the checkpoint** (see the verification
note below). `DiffView.putInto` is reached from **9 production sites in 4 classes** —
`AbstractApplyingRefactoringTool` 214/254/302, `AbstractRefactoringTool` 319/356/377,
`InspectRefactoringTool` 93, `CopyClassTool` 213/239. **14 production response paths still write a
raw uncapped unified diff**, in 8 classes: `ComposeMethodTool:199`, `DecomposeConditionalTool:310`,
`OverrideMethodsTool:243,265`, `GenerateConstructorTool:280,302`,
`GenerateGettersSettersTool:316,338`, `GenerateTestSkeletonTool:194,234`,
`GenerateToStringTool:227,250`, `GenerateEqualsHashCodeTool:192,215`. (`RunTestsTool:276,904` are a
different `diff` — a git-diff-kind string — and are excluded.)

**All 8 of those classes call `withAutoApply`**, which `AbstractTool:228-239` now uses to publish
`fullDiff` — so all 8 advertise the escape, ignore it, and still send the payload the cap exists to
prevent. This codebase has a *named* precedent for exactly this failure, in `ExtractMethodTool`
beside D7a's own change: *"the parameter was published, accepted, echoed back, and inert."*
`DeclaredShapeHonestyTest` checks front-door-vs-delegate schema **parity**, not
schema-vs-**behaviour**, so nothing catches it.

**Verdict.** BANDAGE in placement — the fix is right where applied; the delegation is unfinished.
*How a response presents a diff* is owned by the response, not by each of twenty tools that must
remember to ask.

**Smallest design-level alternative.** One seam, not twenty opt-ins: give `AbstractTool` a single
`putDiff(data, diff, arguments)`, or move the cap into the one place a response's data map is
finalized, so a path cannot bypass it; then migrate the 14 sites — mechanical, no behaviour change.
Add the schema-vs-behaviour invariant beside `DeclaredShapeHonestyTest`: no production class writes
`put("diff", …)` for a unified diff, and no tool publishes `fullDiff` without honouring it.

**Actuator.** `extract` kind=method on `AbstractTool`, plus `move` to fold the 14 sites onto it;
`/cover` for the invariant test.

**Home.** The 14 migrations: **this stage** — they are the same defect, and D7f's own commit
message claims the cap is declared *"once on `withAutoApply`"*. The invariant test: a later stage.

### F2 — `PurityCheck` reaches no stage or apply path, so the detector that describes D7b verbatim could never have seen it *(incomplete delegation)*

**Finding.** CARRIED FORWARD AND RE-MEASURED, with a correction to the number: **17 references
total — 5 in production, all in `PlanRefactoringTool` (22, 23, 49, 278, 280); the other 12 are in
`PurityCheckTest`.** `GatedApply.perform` has exactly 4 call sites (`RecipeEngine:137`,
`AbstractApplyingRefactoringTool:237`, `AbstractRefactoringTool:341`, `ApplyRefactoringTool:86`) and
none consults `PurityCheck`. So the one detector whose `CHANGED_OUTBOUND_CALLS` rule describes
D7b's symptom exactly is wired only into the plan tool.

**Verdict.** This is the design fix that was available and not taken. The carried finding's
substance holds; its count was 5 *production* references rather than 5 total.

**Smallest design-level alternative.** Call `PurityCheck` from inside `GatedApply.perform` — one
place, four call sites inherit it, no new seam, no per-tool opt-in. Explicitly **not** from each
tool.

**Actuator.** architect (design: report-vs-undo on a purity finding), then `move` kind=method onto
`GatedApply`. **Home.** Later stage — recorded, not asked here.

### F3 — every engine warning is discarded, for every refactoring; D7e worked around it rather than fixing it *(incomplete delegation)*

**Finding.** `AbstractRefactoringTool#runPreCheckedRefactoring` (declared 267) reads
`checkFinalConditions` at **273** and tests only `hasFatalError() || hasError()` at **274**. Every
INFO/WARNING entry the engine produced is dropped, product-wide. The cost is visible *inside this
checkpoint*: D7e's `promoteMemberType` (`MoveClassTool:294-300`) runs the conditions itself — its
javadoc says so, *"because `isCreatingInstanceFieldMandatory()` is only answerable once the source
rewrite exists"* — and then **hand-authors** the sentence *"A member the promoted type reads may
also have been WIDENED"*, which is information `MoveInnerToTopRefactoring` already reports as a
status entry that never arrives.

**Verdict.** BANDAGE — a hand-written note duplicating what the engine says, in a product where no
engine warning reaches any caller.

**Smallest design-level alternative.** `RefactoringStatus` already owns its entries. Carry the
non-error entries into the response under one key (`engineWarnings`) from
`runPreCheckedRefactoring` — one method, every refactoring gains it, and D7e's hand-authored
widening sentence can then be **deleted** rather than maintained.

**Actuator.** architect (key name, and whether warnings gate anything), then
`change_method_signature` / `extract` on `AbstractRefactoringTool#runPreCheckedRefactoring`.
**Home.** Later stage — recorded.

### F4 — the staged path still returns before the gate: AGREED, and the tiny solution is not the one proposed

**Finding.** Re-measured; the line numbers moved under D7f's insertions.
`AbstractApplyingRefactoringTool`: staged branch **207**, return **218**, gate **237**.
`AbstractRefactoringTool`: staged branch **312**, return **322**, gate **341**. The shape is
duplicated, as the prior run said.

**Verdict.** The finding is agreed, **and so is the decision not to take its F1** — calling
`GatedApply` from the staged path buys an apply-check-revert on every staged call, and on APPLY the
D7b defect was never silent. But Harald's *"SO make the tiny solution"* is not satisfied by
declining the expensive one. **The tiny solution is different and is available: the staged response
already has everything it needs to say it is unverified, and says nothing.** The applied branch
writes `compileVerified` (`AbstractApplyingRefactoringTool:302`); the staged branch writes no
verification claim at all — which reads as verified by omission. Add one key at 218 and 322:
`compileVerified: false` plus a one-line reason ("a staged change is not compiled until it is
applied"). Two lines, no behaviour change, no cost. It is also exactly the shape of five of the
eight defects in this very stage: *an answer that cannot be told apart from a different answer.*

**Home.** **This stage** — it is two lines and it is the stage's own theme.

### F5 — D7g returns a change for the caller to apply, after doing the thing the caller did not want *(Rule 8)*

**Finding.** `withOverloadAlternative` (`AddRecordComponentTool:566-592`) is gated on
`constructions > 0 && isSentinel(defaultValue) && !viaOverload` — **the condition under which it
fires is precisely the condition under which the 78-site rewrite should not have happened.** The
tool now *knows* the ask carried its own answer, rewrites 78 sites in 24 files anyway, reports
success, and hands the caller an arguments map to re-invoke. The field evidence is that all three
such runs were undone.

**Verdict.** BANDAGE.

**Smallest design-level alternative.** REFUSE — and the tool already owns the precedent one screen
above: `NO_DEFAULT_FOR_EXISTING_CALLS` refuses rather than guess a value, on the reasoning *"a
value that compiles is the worst kind of guess."* Same shape: when `defaultValue` is a sentinel and
`viaSecondaryConstructor` was not spelled, refuse with a code naming **both** modes and let the
caller spell the one they want. If a refusal is judged too strong, the next-smallest is to make
`viaSecondaryConstructor` **default true** for a sentinel default — the shape the evidence says is
wanted — and let the caller opt out.

**Home.** **This stage** — same defect, not new work. The refuse-vs-default-true call is the one
design decision this report leaves open.

### F6 — one of D7f's assertions is a count guarding a value against itself *(Rule 13)*

**Finding.** `ADiffIsSummarisedAndCappedTest.assertSummarised` line **276**:
`assertEquals(DiffRenderer.summarize(diff).size(), summary.size())`. `summary` is
`data.get("diffSummary")`, which `DiffView.putInto` built by calling `DiffRenderer.summarize(text)`;
the expected side calls `DiffRenderer.summarize(diff)` where `diff = data.get("diff")`. Its two
callers (`theNonApplyingBaseIsWired:206`, `theApplyingBaseIsWired:221`) stage small diffs, so
capped == uncapped and **both sides are byte-identical inputs to one deterministic production
function.** It cannot fail, and `assertNotNull(raw)` at 269 already proves everything it proves.
Its neighbour at **281** — `diff.contains(row.get("filePath"))` — is the real cross-check and does
the work the method's javadoc claims.

**The contrast in the same file, stated in fairness:** `theCapIsReachableInProduction:252-257`
compares `summarize(uncapped).size()` against `summarize(capped).size()` — **different inputs, a
strict inequality that can fail.** That is the strongest assertion here, and it is the one proving
the cap is reachable through a real production response.

**Smallest fix.** Compare against an independent number: the count of `--- a/` header pairs the
test can count itself, or the fixture's known file count. **Actuator.** `/cover` on that one
method. **Home.** This stage (one line).

### F7 — D7g's new refusal guard is unexercised and undocumented *(Rule 13)*

**Finding.** `SECONDARY_CONSTRUCTOR_EXISTS` is declared at `AddRecordComponentTool:96` and thrown
at **349**. MEASURED: it appears in **zero** test file (`AnOverloadLeavesEveryCallSiteAloneTest:160`
asserts only `NO_DEFAULT_FOR_EXISTING_CALLS`), and it is absent from the tool's own documented
refusal list — the `<li>` block at 77 and the description text at 130, both of which enumerate the
other codes.

**Verdict.** The guard is right; its evidence and its publication are missing. A refusal a caller
cannot discover is a refusal they meet by surprise. **Actuator.** `/cover` — one test on a record
with an N-arg constructor asking for `viaSecondaryConstructor`; plus the code added to the two doc
lists. **Home.** This stage.

---

## D7a — the two questions raised, answered

**Is `engineThrew` dropped to package-private a contract change? No.** It is **born**
package-private in `230ed0d9` — not reduced from `private`, not widened from nothing published — it
is a `static` helper on a tool class, and its only out-of-class reference is the test in the same
package. An exported OSGi package does not make a package-private static method API. It is the
minimum visibility that lets the format be pinned, and the comment states that split rather than
glossing it.

**Is the formatting-vs-firing split honest or a bandage? Honest.**
`AnEngineThrowNamesItsPhaseTest.theOtherPhaseIsNamedToo` (72-85) is a real anti-constant control: a
hardcoded `"checkAllConditions"` would satisfy the first test and fails this one, so the phase —
the whole point — cannot be decorative. The limit is declared in both the production comment and
the test javadoc, and the wiring is held by the compiler (two catch blocks, no other caller).
`TheShapeD7aBlamedExtractsCleanlyTest` pinning a **negative** result so the refuted hypothesis
cannot be re-adopted is the right instinct and should be kept. The residual gap — the branch is
never proven to fire — is correctly homed to the debugger seat with a resident stack.

---

## Rule 5 — the contract changes

Two contracts changed. Both have **clients** (an MCP client parsing the response `data` map), not
callers.

**D7f — `diff` capped under its own key; `diffSummary`, `truncated`, `diffLength`, `diffNote`
added; `fullDiff` added as an input on ~85 tools.**

- *What was searched.* (a) `jawata-mcp` production source for `put("diff"` map-key writes — 17
  hits, enumerated in F1. (b) `jawata-studio` `.rs/.ts/.tsx/.js/.jsx` for `"diff"`, `diffSummary`,
  `diffNote`, `fullDiff`, `truncated`, `undoChangeId`, excluding `node_modules` and `target`.
  (c) `jawata-enterprise` `.java/.ts/.rs/.py` for `"diff"`, `diffSummary`, `undoChangeId`,
  `changeId`. (d) `jawata-mcp` `*.json` and `*.md` for `diffSummary`/`fullDiff`.
- *Which KIND of consumer.* Any MCP client that **parses the response data map** — the agent
  harness itself, and any renderer or script reading `data.diff`. Not Java callers.
- *Result.* **jawata-studio has no structural consumer of this payload.** Its only hits are prose
  in instruction text it writes out (`src-tauri/src/manager_service.rs:4758`, the "it returns a
  diff + `undoChangeId`" boilerplate), one test-fixture JSON (`manager_service.rs:11107`), and the
  hook's own unrelated truncation locals (`pipeline.rs:1936`, `local.rs:459`). It relays
  responses; it does not parse them. **jawata-enterprise: zero hits.** **No JSON schema declares
  the response shape.** So nothing in these three workspaces breaks.
- *The in-workspace limit, stated plainly.* **It cannot be known whether consumers outside this
  workspace exist.** Any third-party client, user script or agent prompt that reads `data.diff` and
  expects the whole text now silently receives a 24,000-character prefix. The mitigations are
  present and, on examination, sufficient for feature detection: `diffSummary` is written
  **unconditionally**, so a client can detect the new server by its presence, and `truncated:true`
  + `diffNote` name the cut when it happens. `truncated` being written only-when-true
  (`DiffView:76`) is therefore sound rather than a gap — the unconditional key carries the
  detection. The one strictly non-breaking alternative would be putting the capped text under a
  **new** key (`diffPreview`) and leaving `diff` whole-or-absent; that is **not** recommended,
  because the whole point is that the oversized default answer never leaves the server.
- *The real exposure is F1, not the shape.* While 14 paths stay uncapped, the published contract
  says one thing and 8 tools do another.

**D7g — `viaSecondaryConstructor` (new input), `overloadAlternative` (new response key),
`SECONDARY_CONSTRUCTOR_EXISTS` (new refusal code).**

- *What was searched.* `jawata-mcp` `*.java` for both refusal-code literals; the studio and
  enterprise sweeps above also covered `changeId`-shaped response reading.
- *Which KIND of consumer.* A client **branching on a refusal code** — the one documented reason
  these codes are stable strings.
- *Result.* `NO_DEFAULT_FOR_EXISTING_CALLS` is consumed by two tests only;
  `SECONDARY_CONSTRUCTOR_EXISTS` by nothing (F7). All three additions are purely additive — no
  existing key changes meaning. **In-workspace risk: none. Outside this workspace: unknowable**,
  but additive keys and a new refusal code on a new opt-in path cannot break a client that does not
  pass the flag.

---

## Below the fold

- `DiffView` is referenced fully-qualified at all 6 call sites (`AbstractApplyingRefactoringTool:204`,
  `AbstractRefactoringTool:308`, `InspectRefactoringTool:93`, `CopyClassTool:213,239`,
  `AbstractTool:~235`) rather than imported — consistent with the existing `GatedApply` style in
  the same files, so defensible; `organize_imports` would shorten it.
- `CopyClassTool` builds the view twice (213, 239) instead of once above the branch, unlike both
  bases.
- `withPromotionReport` (`MoveClassTool:311`) and `withOverloadAlternative`
  (`AddRecordComponentTool:566`) both cast `response.getData()` to a mutable `Map` and mutate in
  place, relying on `runPreCheckedRefactoring` returning a `LinkedHashMap`. D7e's javadoc names the
  cause precisely — *"the shared tail assembles its data map privately and offers no extras seam"* —
  and **that absence is the finding**: two unchecked casts against an undeclared invariant. An
  `extras` parameter on the shared tail would retire both.
- `DiffRenderer.summarize` counts a line starting `+++`/`---` inside a hunk body as added/removed
  (the `+`/`-` branches run once the header check fails), so a diff *of a diff* miscounts by two per
  header. Named as residual ambiguity in the javadoc; no action wanted.
- D7f's pair of commits (`d32c90f4`, `4e2b4dc4`) is correct mutation discipline: the javadoc admits
  the prefix-check mutant survived two tests, and `anUnpairedMinusLineDoesNotOpenAFile` is the third
  that kills it. No finding.
- D7d's `discoveryNote` fires on `total == 0 && evidenceFinalized && !cancelled && !timedOut` —
  correctly disjoint from the existing `evidenceNote` branch (`!evidenceFinalized`), so the two
  notes can never contradict.

---

## Dispatch summary — what fires where

**This stage:** F1's 14 migrations · F4's two lines · F5's refuse-vs-default-true decision · F6 and
F7 (`/cover`).

**Later stages, recorded not fixed:** F2 (`PurityCheck` into `GatedApply`) · F3 (`engineWarnings`) ·
the Rule-12 `DefaultValue` object that would give `change_method_signature` what
`add_record_component` just learned · D7a's root cause (debugger seat, resident stack) · D7d's
selection threading · F1's schema-vs-behaviour invariant.

---

## Verification note — what the checkpoint re-measured rather than accepted

The architect seat is a subagent and its findings are checked, not taken. Re-derived independently
at C3, before any of F1's work was scheduled:

| claim | verified how | result |
|---|---|---|
| `DiffView.putInto` has 9 production sites in 4 classes | `find_references` on the symbol — 12 total, 9 production and 3 in `ADiffIsSummarisedAndCappedTest` | **TRUE**, at exactly the lines named |
| 14 production paths write a raw uncapped unified diff, in 8 classes | `find_string_literals` regex `^diff$`, scan COMPLETE over 1171 files, filtered to unified-diff response writers | **TRUE** — ComposeMethod 1, DecomposeConditional 1, and 2 each in OverrideMethods, GenerateConstructor, GenerateGettersSetters, GenerateTestSkeleton, GenerateToString, GenerateEqualsHashCode = 14 |
| the range correction | `git merge-base --is-ancestor 230ed0d9 b37f12da` | **TRUE** — `b37f12da~1` IS `230ed0d9`, so the first range excluded D7a and D7d; both agents were corrected mid-run |

*Read-only run. No build, no test, no `mvn`, no file written by the seat — the concurrent suite in
`/home/harald/CursorProjects/jawata-mcp` was not touched. Its evidence came from
`git log`/`blame`/`diff`/`show`, file reads, and the jawata MCP `find_references` tool; four shell
text reads over `.java` were declared `jawata-fallback:` with their reasons and are logged.*

## D4 - what was asked of the experience store and the pattern catalogue, and what was selected

**Recorded after the fact (2026-10-11), not before the design.** This checkpoint closed without the
consultation Stage 6 requires, which was found by counting the reports on disk (2 of 9 carried one).
The consultation below was run afterwards, over the checkpoint's subject as this report describes it.
It informs the record and not the build, and the lateness is the finding.

**Asked:** *eight engine-tool defects are repaired in a refactoring engine (an internal error with no
phase named, a response far too large, a change-signature rewriting the wrong call, a forked test runner
killed, a lane filter that silently drops a whole corpus); which repairs belong in the object that owns the
behaviour, and which cap or flag is left opt-in at the call site.* Eight nominated, **one selected**:
`6fecd8cb` (when a claim the product itself emits turns out false, the test that asserts it is the
mechanism that kept it, so the producer and the test move together). It applies to the D7g finding in this report: D7g's
`overloadAlternative` states something false on the success of the very run it says should not have
happened, so the repair must move the claim and any assertion over it together. It did not change what
was built. The other seven were not applicable (three are about review loops and refactoring order, not
about where a behaviour lives).

No catalogue pattern was selected in any case below.
