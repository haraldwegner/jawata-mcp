# ARCHITECT — WATCH MODE, Sprint 28d Stage 8 checkpoint diff

**Scope:** the Stage 8 commits `522d8af`..`dbccd4c` on `sprint-28c-rescue`, judged
against `ARCHITECTURE-28d.md`. Two operations shipped — rank 3 (Replace Constructor
with Factory Method) and rank 2 (Replace Conditional with Polymorphism) — plus the
cross-file Extract Class proof (S8.10).

**Incomplete delegation, ranked first per standing rule 1: NONE.**
`find_quality_issue(kind=incomplete_delegation)` on
`ReplaceConditionalWithPolymorphismTool` returns **0 findings, scan COMPLETE (1 file
examined, every lookup answered)** — a real absence, not a failure to look. The
category is empty for this diff and the report moves on.

---

## Findings (ranked)

### F1 — the picture this watch-diff is supposed to judge against names a module that does not exist

`ARCHITECTURE-28d.md:90–93` declares stream 1 as:

```
  STREAM 1: OPERATIONS
  org.jawata.mcp.refactoring.ops
  ExtractClassOp, MoveFieldOp, ...
```

**Measured, both directions:**

- `search_symbols("RefactoringOperation*")` → **0 results**. There is no operation
  skeleton by that name anywhere in the workspace or its jars.
- `find_references(kind=implementations, AbstractApplyingRefactoringTool)` → **23
  production tools, every one in `org.jawata.mcp.tools`** — including
  `ExtractClassTool`, `RefactorToStateTool`, and both Stage 8 additions.

So the declared package was never built, and `ExtractClassOp` / `MoveFieldOp` never
existed under those names. The Stage 8 changes are consistent with the REAL
convention (23 of 23) and inconsistent with the DECLARED one, and the watch-mode
question — *toward or away from the picture?* — has no answer as written.

**Why this is a finding rather than a footnote.** A future executor reading the
architecture to place the next operation would create `org.jawata.mcp.refactoring.ops`
and split a 23-member family in two, on the authority of a document that describes
nothing. **This is the S7.8 shape recurring** — a falsified architecture clause — and
Stage 7 already paid for one.

**Design-level fix, and it is a document change, not a code change:** correct the
stream-1 box to name `org.jawata.mcp.tools` and `AbstractApplyingRefactoringTool`,
which is what every operation actually extends. If the `refactoring.ops` split is
genuinely wanted, it is a migration step with 23 members and belongs in the migration
path with a gate — not left standing as a description of the present.

### F2 — two refusals were DOCUMENTED and NEITHER is tested, in the same commit that introduced them

> **☑ DISCHARGED IN THIS CHECKPOINT — `2a58f39` (S8.13).** Both refusals now have a
> test, each asserting the source is byte-identical afterwards and that the message
> NAMES its cause (`step` for the assignment, `this` for the other). A **control**
> came with them: the ordinary switch in the same fixture project must still be
> accepted, because a tool refusing everything there would have passed both new
> tests. The fixture's two methods are otherwise perfectly good candidates — enum
> discriminator, arrow switch, two non-default arms plus a default — so each refusal
> is attributable to the shape it names rather than to some other precondition.
>
> Discharged rather than deferred because this is the COVERAGE GATE, not new scope:
> the refusals are behaviour this checkpoint added, and the gate says behaviour a
> change adds is covered before the change is called done.

`098cfba` added two refusals to rank 2, both of them real and both now advertised in
the class javadoc and in the tool description a client reads:

- an arm that **assigns** a method-scope variable (Java passes by value, so the write
  would land on a copy and be lost — a behaviour change that compiles);
- an arm using **`this`** for anything but reaching a context field (in the generated
  class `this` IS that class).

`ReplaceConditionalWithPolymorphismToolTest` has five tests. The only refusal among
them is `aCaretAwayFromAnySwitchIsRefused`, which is an ARGUMENT refusal — the caret
has no switch under it. **Neither shape refusal is exercised by anything.** No
fixture contains an arm that assigns a method-scope variable, and none contains a
bare `this`.

**This is the declared-shape family one level up.** Stage 7 spent four commits on
schemas that lied about their delegates; here the javadoc and the description make a
promise about behaviour that no instrument checks. A refusal nothing exercises is a
claim, and it is a claim a client is now being told to rely on.

It is also the cheapest finding in this report to discharge: two arms on the existing
fixture and two assertions, on the pattern
`aCaretAwayFromAnySwitchIsRefused` already establishes (refused, and the source
byte-identical afterwards).

### F3 — text assembly is now the majority path for pattern transforms, and nothing factors it

`ReplaceConditionalWithPolymorphismTool` builds the generated interface, the
implementations and the dispatch table with `StringBuilder`, hardcoded two-space
indent units (`String indent = "    "`), and wraps the result in
`InsertEdit`/`ReplaceEdit`.

**I checked whether this diverges from its siblings before calling it one, and it does
not.** `find_pattern_usages(kind=instantiation, org.eclipse.text.edits.InsertEdit)`
→ 6 production sites in 5 tools: `FormTemplateMethodTool`,
`RefactorToCommandDispatcherTool`, `RefactorToStateTool`, `RefactorToVisitorTool`
(×2), and now this one. Rank 2 follows the family convention for operations with no
JDT engine behind them. **The finding is about the convention, not about the change.**

Five hand-rolled Java emitters now exist, and:

- none derives indentation from the project's formatter settings — while the
  `generate` family already takes an `indentChar` parameter precisely because that
  mattered enough to parameterise once;
- an arrow arm whose body is a block survives as a nested `{ }` inside the generated
  method (valid, harmless, and nobody chose it);
- each emitter re-solves the same problems — indent, reindent-on-move, where to
  insert relative to the type's closing brace.

**Per the standing shape rule, the second instance is the design alarm. This is the
fifth.** The smallest design-level alternative is one emitter the five share —
generated-member insertion against the enclosing `TypeDeclaration`, formatter-aware
— rather than a sixth copy when Stage 9 or the cut-line operations arrive.

**Not urgent, and explicitly not a blocker for C8.** It becomes urgent at the next
engineless operation, which is exactly when it is cheapest to have done already.

---

## Dispatches

| Finding | Actuator |
|---|---|
| F1 | a documentation correction to `ARCHITECTURE-28d.md` — one box. Not a refactoring; no plan kind applies. |
| F2 | **test-writer seat** (`/cover`) — two fixture arms + two refusal assertions on `ReplaceConditionalWithPolymorphismToolTest`. |
| F3 | `refactoring(action=plan)` **when it is taken**, kind `extract` (method) against the five emitters' shared shape. NOT proposed for this checkpoint. |

## Trend (baseline diff)

Nothing regressed. Gates read at `dbccd4c`, before S8.13 was written:

| Gate | Result | Against |
|---|---|---|
| `compile_workspace` | **0 errors / 136 warnings** | the C0 baseline exactly, unmoved across all of Stage 8 |
| full suite | **2209 total · 2207 succeeded · 0 failed · 2 aborted**, wall 481 s | C0 was 2104/2102/0/2 |
| abort budget | **OK** — 2 aborts, 3 patterns allowed | every skip accounted for |
| `unwired-gate.sh` | **PASS at 81**, unchanged | 81 in baseline |
| e2e | **99 passed / 0 failed** | 96 before rank 2's three staging checks |

The S8.13 tests landed after that run, so a second full pass with them in is the
checkpoint's own gate and is running. **This report does not claim that pass** — it
records the numbers above, which are the ones actually read.

## Reviewed diffs — design fix or bandage

**DESIGN FIX**, on all four points I checked:

1. **Rank 3 delegates rather than reimplements.** `IntroduceFactoryRefactoring` does
   the work; the tool is 275 lines of plumbing. That is the right relationship to an
   engine that exists.
2. **Rank 2 does not pretend to be the State tool generalised.** The stage measured
   `refactor_to_state`'s four preconditions, concluded lifting them produces a laxer
   State tool rather than this operation, and then **asserted the non-overlap** —
   `refactor_to_state` refuses rank 2's fork-slice caret and leaves the source
   byte-identical. An argument became a test, which is what makes the boundary
   survive someone widening the State tool later.
3. **The refusals sit on a channel that can act in time (rule 6).** Both fire inside
   `prepareChange`, before any edit is constructed and long before anything is
   written. Contrast the shape rule 6 refuses — a control that can only append after
   the artifact exists.
4. **The mutating tools return what they DID, not homework (rule 8).** Both new
   operations go through `AbstractApplyingRefactoringTool` and return
   `filesModified` + `diff` + `undoChangeId`, or stage under a `changeId`. Neither
   hands the caller a list of edits to apply.

**One contract observation, stated because rule 5(f) forbids silence about the other
side.** Both changes ADD a `kind` to `refactor_to_pattern`'s published enum and its
schema. The producers are the two new delegates; the consumers are MCP clients
reading the tool schema — **which live outside this workspace and cannot be
enumerated from here**. The change is purely additive: no existing kind, parameter or
response field changed shape, so no consumer can break on it. No consumer-side change
is needed. `DeclaredShapeHonestyTest` now registers both new kinds in its delegate
map, so the published schema is checked against each delegate's parameters on the
same axis where `extract` and `generate` both previously failed.

## Below the fold

- `ReplaceConditionalWithPolymorphismTool` is 634 lines against rank 3's 275. The
  difference is the absent engine, not bloat — but it is the largest single tool in
  the family and the F3 emitter would take roughly a third of it out.
- The dist freshness guard reads `jawata.jar`'s mtime. Maven does not rewrite that
  12 KB launcher when only bundles change, so an incremental build leaves a genuinely
  fresh dist looking stale and the suite refuses. Conservative direction — it refuses
  rather than testing stale code — but it costs a full clean rebuild each time, and
  it cost one in this checkpoint.

## Skipped by record

None. No previously declined proposal is re-argued here.
