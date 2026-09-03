# ARCHITECTURE-fowler-full — where 51 new items land, without a bigger tool surface

**Scope:** Sprint 28d-rescue, signed off 2026-09-02. 45 Fowler refactorings, 5 smell
detectors, 1 commented-out-code check. The spec
(`jawata-enterprise/docs/sprints/jawata-mcp/sprint-28d-rescue-fowler.md`) says WHAT
ships and how it is measured; this document says HOW it is shaped. Where they speak
about the same thing, the spec governs scope and this document governs structure.
**The plan is written against this document.** A plan produced without it, or
contradicting it, is refused.

**Status:** APPROVED 2026-09-02 by Harald. The plan is written against this document.

**v3** — v1 added four tools; Harald ruled the surface must not grow (*"we do not want
to increase the number of tools. If we now increase we need to categorize, consolidate
and hide behind a lesser number of front doors at a later stage"*), so v2 consolidated
instead. v3 records his approval, takes both consolidation options, and carries the
name he gave this document (*"don't call it rescue but fowler-full"*).

---

## 1. The binding constraint

**The published tool count does not rise, and it falls. 46 before, 42 after.**

The 45 refactorings become options on tools that already ship. This is not a new idea
here: `ToolRegistry` carries a rename map whose own comment records that "the tool
surface collapsed many narrow tools into parametric front doors across Sprints 11–19",
and a call to a retired name still answers with a pointer to its replacement rather
than an error. The machinery for consolidating is built, tested and in production.

---

## 2. The picture

```
   CLIENT (an agent, or the studio)
        │  one call per fix, taken from the finding that named it
        ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │  EIGHT EXISTING TOOLS, each ≤ 11 kinds. NO NEW REGISTRATIONS.     │
   │                                                                   │
   │  extract 11 · inline 5 · move 6 · apply_cleanup 11                │
   │  change_method_signature 11 · move_in_hierarchy 7                 │
   │  encapsulate_field 10 · refactor_to_pattern 10                    │
   └───────────────────────────┬───────────────────────────────────────┘
                               │ every kind is a delegate class in tools/<family>/
                               ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │  AbstractApplyingRefactoringTool  — the one pipeline, unchanged   │
   │  prepareChange → compile gate → parity → purity → cache → undo    │
   └───────────────────────────┬───────────────────────────────────────┘
             ┌─────────────────┴──────────────────┐
             ▼                                    ▼
   ChangeEngine.fromFileEdits            JdtRefactoringEngine
   (we build the edit map —              (we expose an Eclipse engine —
    12 exemplars in production)           the cheap minority, 7 targets)
             └─────────────────┬──────────────────┘
                               ▼
                        RecipeEngine  ── the 8 composed rows, as ordered
                                          runs of the above. No new engine.
                               ▲
                               │ a step is any registered operation
                    ┌──────────┴───────────┐
                    │  OperationRegistry   │  NEW SEAM (P1) — internal, not a tool
                    └──────────┬───────────┘
                    CureCatalog · CureTier
                               ▲
                    the 41 detectors + 6 new
```

---

## 3. Where the 45 land

Grouped by the SHAPE of the change, because shape decides the code that implements it:
a rewrite inside one method, a signature change with its call sites, a hierarchy
change, a change to how state is represented.

| Tool | Kinds today | After | The rows it takes |
|---|---|---|---|
| `extract` | 6 | 11 | 5, 48, 49, 58, 64 |
| `inline` | 2 | 5 | 17, 36, 38 |
| `move` | 2 | 6 | 23, 24, 25, 26 |
| `apply_cleanup` | 2 | 11 | 7, 8, 34, 44, 50, 52, 60, 62, 63 |
| `change_method_signature` | 1 | 11 | 21, 27, 28, 35, 41, 46, 47, 53, 55, 61 |
| `move_in_hierarchy` | 2 | 7 | 4, 29, 56, 57, 59 |
| `encapsulate_field` | 1 | 10 | 2, 9, 10, 16, 22, 37, 45, 54, 65 |
| `refactor_to_pattern` | 10 | 10 | — |

5 + 3 + 4 + 9 + 10 + 5 + 9 = **45**, every work row placed exactly once, verified by
script. **No tool exceeds eleven kinds**, and that cap is not cosmetic — see §4.

The 6 new detectors are detector kinds, not tools: they register into the detector
catalogue behind `find_quality_issue`, which already publishes 41.

---

## 4. Why eleven, and why a fat tool is a real cost

A tool that dispatches to delegates the application does not register standalone owns
the **only documentation its clients can reach**. Proven here at 28d C8:
`RefactorToPatternTool` published ten kinds and described eight, and two shipped
operations were documented nowhere — while two existing guards both passed, because
the gap sat on a third axis neither looked at. `DeclaredShapeHonestyTest` now guards
all three per tool: the kind enum equals the routed set, every delegate parameter is
published, and **every published kind's name appears in the description**.

So the cost of a kind is one description entry that a human must be able to read in
one screen. Eleven is where the existing tools already sit (`refactor_to_pattern` at
ten is the current worst) and it keeps the guard satisfiable by prose a person wrote
rather than a generated list. **A family that would exceed eleven splits by shape, it
does not spill into a neighbour.**

---

## 5. Two tools are renamed — DECIDED 2026-09-02

Absorbing the rows makes two tool names narrower than their contents:

- `encapsulate_field kind=split_variable` — the tool would cover how state is
  represented, not only field encapsulation.
- `move_in_hierarchy kind=collapse_hierarchy` — the tool would cover hierarchy change,
  not only moving members up and down.

`change_method_signature` and `apply_cleanup` survive honestly: every row assigned to
them is a signature-and-call-site change, or a safe mechanical rewrite.

**Decided: rename both.** `encapsulate_field` → `data`, `move_in_hierarchy` →
`hierarchy`, with both old names added to `RENAMED_TOOLS` so a call to either still
answers with a pointer to its replacement. The count does not change. This is the
mechanism Sprints 11–19 already used fourteen times, and a name that misdescribes its
contents is the same defect class as a kind nobody documented.

## 5b. Four tools are folded away — DECIDED 2026-09-02

Four of today's tools duplicate something a front door already does. Folding them takes
the surface **below** 46, which is the consolidation Harald's ruling asks for. None of
it is needed to absorb the 45, so it is scheduled where it cannot block them (§11,
stage 1).

| Fold | Into | Why it is a duplicate |
|---|---|---|
| `optimize_imports_workspace` | `organize_imports` with a scope | two tools, one job, different scope |
| `move_method` | `move kind=method` | the move family already exists |
| `convert_anonymous_to_lambda` | `refactor_to_pattern kind=replace_pattern_with_idiom` | that kind's default idiom IS anonymous-to-lambda |
| `replace_duplicates` | `extract` | it is Replace Inline Code with Function Call under another name |

**46 → 42.** Each fold is a contract change: the old name answers through the rename
map, and a caller outside this workspace cannot be enumerated, so the map is the only
guarantee. Each fold ships with a test that the retired name still answers.

### 5c. Where the folds land in §3's arithmetic — AMENDED 2026-09-03

§3's table was written before §5b and counts only ROW absorption, so a reader adding
four folded tools to it gets a different answer than the table gives. Three of the four
folds add no kind at all:

- `convert_anonymous_to_lambda` lands on `replace_pattern_with_idiom`, a kind
  `refactor_to_pattern` already publishes. It stays at ten.
- `optimize_imports_workspace` lands on a `scope` PARAMETER of `organize_imports`, not
  on a kind. `organize_imports` publishes no kind enum and is not in §3's table.
- `replace_duplicates` lands on ROW 49's kind, because §5b's own reason for folding it
  is that it IS row 49 under another name. It is not a seventh kind beside row 49 — and
  it cannot be, since `extract` reaches the eleven cap exactly with its five rows.

The fourth is the one that moves a number, and it moves it in §3's table rather than
past the cap:

- `move_method` lands on `move kind=method`, and **row 24 (Move Function) is that same
  kind's static half.** Fowler names ONE refactoring, Move Function, and a caller asking
  to move a method should not have to know first whether it is static; the kind
  dispatches on that itself. So `move` publishes class, package, method, and rows 23, 25,
  26 — **six**, exactly as §3 says, with row 24 inside `method` rather than beside it.

Consequence for the build order: `move kind=method` ships in stage 1 covering the
INSTANCE case, which is the tool being folded, and stage 6 extends the same kind to the
static case through `MoveStaticMembersProcessor`. One kind, two stages, and the second
stage adds no kind to the surface.

---

## 6. The two seams the sprint must open first

### P1 — `OperationRegistry`: what may be a step in a cure

**Today.** `CureCatalog` refuses to **LOAD** if a declared cure names a step outside
`RefactorToPatternTool.publishedKinds()` — it throws from a static initializer, so a
lane that appends a cure before this seam exists takes the server down rather than
failing a test. `CureTier` derives perform-versus-advise from the same list.
`publishedKinds` has exactly **three references** in the workspace: `CureCatalog`,
`CureTier`, and one test.

**Target.** One `OperationRegistry` in `refactoring/` that every tool registers its
kind names into at construction, read by `CureCatalog` and `CureTier` instead of one
tool's static list. **It is internal — it is not a tool and does not appear on the
surface.** The load-time refusal stays; it just asks the right question.

**Why first.** Four operations that already ship cannot be offered by the smells whose
prose names them: feature envy (713 findings on this repository) → `move_method`; an
over-large class → `extract(class)`; a temporary field → `extract(class)`; a field the
outside can still write → `encapsulate_field`. P1 closes all four at zero new
operations, and all 30 routed rows depend on it.

### P2 — a recipe is an ordered list of registered operations

**Today.** `RecipeEngine` runs a dependent sequence with apply-reparse, a per-step undo
and atomic rollback. It has exactly **one caller**: `ComposeMethodTool`.

**Target.** `Recipe` becomes an ordered list of `(kind, parameters)` resolved through
`OperationRegistry`; `ComposeMethodTool` becomes one recipe rather than the only shape.
The 8 composed rows then ship as recipes.

**This is a generalisation of one caller, not a new engine.** Sizing it as an engine is
the single largest way this sprint's plan could be wrong.

---

## 7. Where new code lands

```
org.jawata.mcp/src/org/jawata/mcp/
  tools/
    api/           delegates for change_method_signature's 10 new kinds
    statements/    delegates for apply_cleanup's 9 new kinds
    inheritance/   delegates for move_in_hierarchy's 5 new kinds
    data/          delegates for encapsulate_field's 9 new kinds
    (extract/inline/move delegates stay where they are)
    smell/         + 6 new detectors
  refactoring/
    OperationRegistry.java          ← P1
    Recipe.java / RecipeStep.java   ← P2 (RecipeEngine itself unchanged)
```

**The package is the collision boundary:** a lane works inside `tools/<family>/` and
touches its own tool, and nothing else.

**The template to copy, named because the obvious choice is wrong.** Twelve tools in
production build their own change through `ChangeEngine.fromFileEdits`, which takes a
multi-file edit map — so cross-file rows are already served: `RefactorToStateTool`,
`RefactorToCommandDispatcherTool`, `RefactorToVisitorTool`, `FormTemplateMethodTool`,
`InlineSingletonTool`, `ReplaceConditionalWithPolymorphismTool`, `ReplaceDuplicatesTool`,
`ApplyCleanupTool`, `ApplyNullAnnotationsTool`, `OrganizeImportsTool`, plus the non-JDT
modes of `ChangeMethodSignatureTool` and `ExtractSuperclassTool`. **`ExtractClassTool`
is NOT the template** — it wraps an Eclipse engine, and copying it for a row we must
write ourselves starts the work in the wrong shape, silently.

**The six Eclipse engines to expose.** Two columns, because they are different
questions: the row an engine IS, and the rows that WAIT on it because they use it as a
part. Rows 21 and 55 are exposures in their own right and wait on nothing.

| Engine | The row it implements | Rows that wait on it |
|---|---|---|
| `IntroduceParameterObjectProcessor` | 21 | — |
| `IntroduceParameterRefactoring` | 55 | 27 |
| `MoveStaticMembersProcessor` | — | 23, 24 |
| `JavaDeleteProcessor` | — | 2, 34, 37 |
| `UseSuperTypeProcessor` | — | 4 |
| `PromoteTempToFieldRefactoring` | — | 48 |

**Eight refactorings wait**: 2, 4, 23, 24, 27, 34, 37, 48. That is the same eight §10
counts, and it agrees with 37 of 45 having no predecessor.

**`ConvertToRecordRefactoring` is NOT needed, and this document said it was.** The
spec's smell row 22 records that the finder already ships as
`find_modernization(class_to_record)` and only has to be registered as a reportable
smell. The spec governs scope. Consequence: **no detector waits on anything**, and all
six can start on day one.

Six more ship unexposed and are not needed here: `ConvertToRecordRefactoring`,
`IntroduceIndirectionRefactoring`, `InlineConstantRefactoring`,
`ReplaceInvocationsRefactoring`, `MakeStaticRefactoring`, `ChangeTypeRefactoring`.

---

## 8. Dependency direction, and what must not be touched

- Tools depend on their delegates; delegates depend on `refactoring/`; nothing in
  `refactoring/` depends on `tools/`. `OperationRegistry` lives in `refactoring/` so
  `smell/` can read it without depending on `tools/` — today `CureCatalog` reaches into
  `RefactorToPatternTool`, and P1 removes exactly that edge.
- **Do not touch:** the behaviour of the 17 shipped refactorings; the published names of
  shipped tools except through `RENAMED_TOOLS` under §5 or §5b; `ChangeEngine`,
  `ParityGate`, `PurityCheck`, `CompileVerify` internals; the store schema;
  `RecipeEngine`'s stale-buffer handling, which exists because of a measured
  one-in-three failure.

---

## 9. The test surface this architecture creates

| What | Where | Runs |
|---|---|---|
| Per-operation behaviour, refusals, the gate battery | one test class per operation in `tests/tools/refactoring/` (68 such files today) | anywhere |
| **Tool honesty, all three axes** | `DeclaredShapeHonestyTest`, per tool: enum equals routed set, parameters published, **every kind named in the description** | anywhere. §4 is why this is the load-bearing one |
| Cure routing and tier | `CureTierTest` + the prose/cure agreement check, over `OperationRegistry` | anywhere |
| Recipe composition | one test per composed row, parity asserted at every step | anywhere |
| Callable from the finding | per operation: called with a symbol name, with a file position, and with the parameters the finding carries | anywhere |
| A retired name still answers | one test per entry added to `RENAMED_TOOLS` | anywhere |
| The product through its own front door, against the built artifact | the end-to-end assertion before the release ask | one machine, the built distribution |
| Whether it is any good on code nobody wrote for us | the dogfood window | reality only |

---

## 10. How many lanes, and what they collide on

**Ruled: three or four lanes write at once, one queue for testing, the full test run at
each merge.**

**The binding constraint is the machine.** `build/run-suite.sh` splits across four
processes and every shard reads one shared build output,
`build/dist/target/dist/jawata.jar`. Two overlapping runs collided on 2026-08-07 and
one reported "4 shard(s) produced no summary". The output directory is per-process now,
but the runner **refuses a build older than the tree**, so a lane cannot verify against
a build another lane just replaced, and one run saturates the CPU.

**Consolidating instead of adding removes the worst shared file from every lane's
diff:** `JawataApplication` is touched **zero times** for the 45 rows, and only if you
take §5 or §5b.

| File | Why several lanes touch it | Rule |
|---|---|---|
| the eight tools' kind lists and descriptions | every kind in that family | **one owner per tool** |
| `CureCatalog` | every routed cure | batched at the merge |
| `DeclaredShapeHonestyTest` | unchanged unless a tool is renamed | one owner |
| `FowlerDetectors` | the six new detectors | one owner |

There is no golden tool list or count to break: `getToolCount` has eight references,
two in the application and six inside a test that builds its own registry.

**The work barely constrains itself.** 37 of the 45 refactorings have no predecessor;
8 do — rows 2, 4, 23, 24, 27, 34, 37 and 48 — and all wait on the six engine exposures
above. **No smell waits on anything.**

```
  W0  P1 + P2                        1 lane      everything routed or composed waits here
   │
   ├─ W1  the 6 engine exposures     ≤ 6 lanes
   │        └─ W3  the 8 composed rows  ≤ 8 lanes  (need W1 + P2)
   ├─ W2  the independent rows       3–4 lanes in practice
   └─ W4  rows 56 and 57             last
   ∥  the 6 detectors and the 2 defect fixes — a different package, from day one
```

---

## 11. Order of work

Demand measured on this repository (jawata-mcp, 392 main-source files, tests excluded,
2026-09-02). **`encapsulation` timed out rather than returning zero** — the sweep
exceeded the client timeout, and a later reader must not rank it last on a false zero.

| Detector | Findings | Detector | Findings |
|---|---|---|---|
| feature_envy | 713 | switch_statements | 26 |
| long_method | 276 | god_class | 21 |
| message_chains | 257 | cqs | 20 |
| long_parameter_list | 140 | lazy_class | 15 |
| data_clumps | 132 | speculative_generality | 6 |
| temporary_field | 128 | middle_man | 3 |
| primitive_obsession | 115 | refused_bequest · composition_over_inheritance | 0 · 0 |
| duplicated code | 198 groups / 628 instances | encapsulation | **not measured — timed out** |

| Stage | Ships | Gate |
|---|---|---|
| 0 | P1 + P2, and the four unreachable fixes routed | `CureCatalog` loads with a standalone step; a feature-envy finding offers `move_method` **at whatever tier the rule derives — perform OR advise** |
| 1 | the 6 engine exposures; the two renames (§5); the four folds (§5b) | each affected tool passes all three honesty axes; each retired name answers; the published tool count is 42 |
| 2 | the 8 composed rows as recipes | each recipe parity-gated at every step, on code we did not author |
| 3 | the independent in-method rows, demand-first | the per-operation battery |
| 4 | the cross-file rows, demand-first: 16 · 28/53 · 54 · 22 · 61 · 17/38 · then the rest | the per-operation battery; order re-checked against the calibration corpus first |
| 5 | rows 56 and 57 | the per-operation battery |
| 6 | the 6 detectors | non-zero on a deliberate fixture before zero on clean code counts |
| 7 | v4.1, dogfooded over real calendar days | the release contract |

**Cost model, an estimate and labelled as one.** Expose an Eclipse engine ½ day ·
composed ½ day after P2 · in-method 1 day · cross-file 2 days · rows 56/57 4 days · a
detector 1 day · P1+P2 about 3 days. 28d's measured baseline: two hand-built operations
in about three days.

---

## 12. Migration path — ordered, parity-gated, reversible

1. **Introduce `OperationRegistry`**, seeded from `RefactorToPatternTool.publishedKinds()`.
   No behaviour change; the three existing references keep passing.
2. **Move the read.** `CureCatalog` and `CureTier` read the registry. The `smell/ →
   tools/` edge is gone. Gate: `CureTierTest` unchanged and green.
3. **Register every tool's kinds** into the registry at construction. Gate: the registry
   equals the union of the published kind lists, asserted.
4. **Route the four unreachable fixes.** Gate: each of the four findings offers its
   shipped fix by name, at the tier the rule derives.
5. **Generalise `Recipe`**; re-express `ComposeMethodTool` as one recipe. Gate:
   `ComposeMethodToolTest` unchanged and green — the control proving behaviour held.
6. **Rename and fold** (§5, §5b): rename the two tools and fold the four, adding all
   six old names to `RENAMED_TOOLS`. Gate: every old name answers with its pointer, all
   three honesty axes pass, and the tool count reads 42.
7. **Then the operations**, per §11, each an independent step.

---

## 13. Open, and deliberately not decided here

- **The commented-out-code check's home.** It reports rather than refactors, so it
  belongs among the detectors behind `find_quality_issue`. Whether it is a `bugs`
  sub-check or its own kind is a one-line decision for whoever builds it.
