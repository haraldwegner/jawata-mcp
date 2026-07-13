# Sprint 24 — evidence dossier

> Companion to the signed spec
> (`jawata-enterprise/docs/sprints/jawata-mcp/sprint-24-dynamic-analysis.md`)
> and the GATE-2 plan. Every number expected-vs-actual. Three phases, one
> release each (v2.11.x / v2.12.x / v2.13.x), each with a dogfood-in-anger
> round.

## C0 — Baselines + spec/process sync (2026-07-13)

### Baselines (on the released v2.10.0, HEAD = a7ec390)

| Probe | Expected | Actual |
|---|---|---|
| Suite (sharded) | 1230/1230, 0 skipped | **1230/1230, 0 skipped, wall 164 s** ✓ |
| toolCount (live resident) | 43 | 43 ✓ |
| Resident version | 2.10.0 | 2.10.0 ✓ |
| Self-workspace sources | 500+ | 500 ✓ |
| Self compile_workspace | 0 / 0 | 0 errors / 0 warnings ✓ |

### Spec + process sync (the two GATE-2 decisions)

- SPEC: R1 amended (three phased releases supersede "fallback, not a
  pre-split") + both GATE-2 decisions added to Recorded decisions —
  committed to jawata-enterprise.
- PROCESS: the dogfood-in-anger + re-release rule folded into the /sprint
  skill's execution discipline (`~/.claude/skills/sprint/SKILL.md`) — the
  plan auditor now checks such a stage exists for every release a plan
  contains.

## C1 — D1: name addressing everywhere (2026-07-13)

### The recorded entry-point audit (the F1 gate)

Every registered tool/kind whose target is a Java element was classified before
any wiring — the full table lives in the plan file. Summary: **14 IN**
(whole-named-symbol targets), **8 EXCLUDED** (range/expression/statement
targets — a range has no name), **1 already symbol-based**
(`apply_null_annotations`), the rest not symbol-targeted. The wiring list and
FqnEverywhereTest enumerate against THAT table, never an ad-hoc list.

### What shipped

- **`FqnTarget`** (shared helper): resolves `symbol=`/`typeName=` into the
  element's name-range position and writes filePath/line/column into the
  arguments, so each tool's PROVEN position path runs unchanged. Idempotent;
  an explicit position always wins; a binary element gets an honest refusal
  (no source position exists) rather than an invented one.
- Wired into: `get_call_hierarchy(outgoing)`; `analyze` kinds
  method/control_flow/data_flow/change_impact/symbol (deliberately NOT
  kind=type — it answers for binaries, which have no source position); and
  every whole-symbol refactoring via `AbstractApplyingRefactoringTool` plus
  the front doors — rename_symbol, change_method_signature, inline(method),
  move(class), move_in_hierarchy, move_method, encapsulate_field,
  extract(interface/superclass), generate(all kinds), and the
  type/method-targeted refactor_to_pattern kinds.
- **Schemas relaxed with the wiring**: filePath/line/column dropped from
  `required` on the newly name-addressable tools (a strict client would
  otherwise reject a name-only call) — either form is now valid, validated at
  run time.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| FqnEverywhereTest | one probe per audited-in tool, by name alone | **10/10** ✓ |
| Touched-tool regressions | green | **130/130** across 11 filters ✓ |
| Pattern-family regressions | green | **20/20** across 8 filters ✓ |
| Full suite (sharded) | 1240/1240 | **1240/1240** after one contention-flake rerun (FeatureEnvyDetectorTest: empty search result under 4-shard CPU load; 3/3 green focused — the known Sprint-23 flake class) ✓ |

### SURPRISE — a pre-existing product bug, found by the new battery

`analyze(kind=type)` **crashed with an INTERNAL_ERROR on every BINARY type** —
`java.util.ArrayList`, any dependency class — in the RELEASED v2.10.0
(confirmed against the untouched live resident, so it predates this sprint).
`IType.getCompilationUnit()` is null for a class file, and
`createMethodInfo`/`createFieldInfo` called `getLineNumber(cu, …)` unguarded →
NPE on `cu.getSource()`. The type-info and diagnostics paths guarded it; the
member paths did not.

This matters precisely for the memory-first thesis: a library type is exactly
what an agent addresses **by name**, and asking about it returned a crash.
Fixed by omitting the line for members with no compilation unit — a binary has
members and a hierarchy, just no source lines. Now `analyze(kind=type,
typeName=java.util.ArrayList)` answers with its members. Recorded in the
experience store (b6b42d6c).
