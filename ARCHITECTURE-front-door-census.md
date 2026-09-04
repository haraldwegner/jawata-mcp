# Front-door census — measured, not asserted

**Produced 2026-09-04 by migration prerequisite M0**, against the workspace at
`d8320848`. Every row was read with `inspect(kind=type_members, typeName=…)` through
JAWATA; nothing here is inferred, recalled or copied from another document.

**This file is the single source for per-door shape.** `ARCHITECTURE-front-door-kinds.md`
and the Sprint 28d plan state no counts of their own — each step's per-door work is sized
from here, and a step whose list disagrees with this file is refused.

**Why it exists.** The delegate-shape census was hand-written three times in three
successive revisions of the architecture and was wrong all three times, in a different way
each time. The fourth version is a measurement.

## The population

The eight in `ToolRegistry.REFACTORING_FRONT_DOORS`, plus `refactoring` (a
discriminator-dispatching tool the constant deliberately excludes) and
`change_method_signature` (not a door today; Stage 4 makes it one).

## Measured

| Door | Class | How it holds delegates | Kind list | Structural declaration |
|---|---|---|---|---|
| `extract` | `ExtractTool` | **map** — `delegates : Map<String, AbstractTool>` (:28) | private `kinds()` (:101) — **no constant** | `structuralKinds()` (:307) |
| `move` | `MoveTool` | **map** — `delegates : Map<String, AbstractRefactoringTool>` (:34) | private `kinds()` (:61) — **no constant** | `structuralKinds()` (:226) |
| `apply_cleanup` | `ApplyCleanupTool` | **map, values are NOT `Tool`s** — `RULES : LinkedHashMap<String, CleanupRule>` (:67) | `KINDS` (:115), **derived** from `RULES.keySet()` | **neither** override |
| `inline` | `InlineTool` | **5 typed fields** (:32–36) | `KINDS` (:29) | `structuralKinds()` (:60) |
| `refactor_to_pattern` | `RefactorToPatternTool` | **11 typed fields** (:62–72) | `KINDS` (:26) **and** `public static publishedKinds()` (:50) | `isStructural()` (:298) |
| `generate` | `codegen.GenerateTool` | **7 typed fields** (:36–42) | `KINDS` (:32) | **neither** override |
| `hierarchy` | `HierarchyTool` | **2 typed fields** (:24–25) | `DIRECTIONS` (:22) | `isStructural()` (:97) |
| `data` | `DataTool` | **NONE — the class declares zero fields** | none | **neither** override |
| `refactoring` | `RefactoringTool` | **4 typed fields** (:28–31) | `ACTIONS` (:22) | **neither** override |
| `change_method_signature` | `ChangeMethodSignatureTool` | **none** — its one collaborator is `engine : RefactoringEngine` (:80), not a delegate | none | `isStructural()` (:892) |

## What the numbers are

Of the eight in the constant: **three hold a map** (`extract`, `move`, `apply_cleanup`),
**four hold typed fields** (`inline`, `refactor_to_pattern`, `generate`, `hierarchy`), and
**one holds nothing** (`data`). Adding `refactoring` makes five typed-field doors.

So **five of the eight** have no delegate map — not two, and not none. Both earlier figures
in the architecture's prose were wrong, in opposite directions.

## Facts that change the migration, each verified above

1. **`extract` and `move` publish their kinds from a private `kinds()` METHOD, not a
   constant.** Any step written as "replace the `KINDS` constant" does not describe them.
2. **`apply_cleanup` already derives its kind list** — `KINDS` is built from `RULES.keySet()`.
   It is the door nearest the target, and the conversion there is small.
3. **`data` has zero fields.** Any per-door gate demanding a delegate for every door is
   unsatisfiable for it until Stage 5 gives it kinds.
4. **`change_method_signature` holds no delegates and publishes no kind list.** It is not a
   front door; Stage 4 creates one.
5. **Structural declaration is not uniform, and the role's fourth method must account for
   it.** Over all TEN rows above: **three** override `structuralKinds()` (per kind) —
   `extract`, `move`, `inline`; **three** override `isStructural()` (per tool) —
   `refactor_to_pattern`, `hierarchy`, **`change_method_signature`**; and **four override
   neither** — `apply_cleanup`, `generate`, `data`, `refactoring`. 3 + 3 + 4 = 10.

   *An earlier version of this line read "three / two / four", which sums to nine over a
   population of ten: it silently dropped `change_method_signature`, whose own row in the
   table above records `isStructural()` at :892. The table was right and the summary of it
   was wrong — which is the very defect this file exists to stop, committed in the file that
   stops it. Both other documents then hardened the omission into an explicit "ten".*
6. **`refactor_to_pattern` carries a second reader**, `public static publishedKinds()`, which
   migration step M1 must retire or rename in the same step: a static method cannot coexist
   with an inherited instance method of the same erasure.

## How to re-derive this

```
inspect(kind=type_members, typeName="org.jawata.mcp.tools.ExtractTool")
inspect(kind=type_members, typeName="org.jawata.mcp.tools.MoveTool")
inspect(kind=type_members, typeName="org.jawata.mcp.tools.ApplyCleanupTool")
inspect(kind=type_members, typeName="org.jawata.mcp.tools.InlineTool")
inspect(kind=type_members, typeName="org.jawata.mcp.tools.RefactorToPatternTool")
inspect(kind=type_members, typeName="org.jawata.mcp.tools.codegen.GenerateTool")
inspect(kind=type_members, typeName="org.jawata.mcp.tools.HierarchyTool")
inspect(kind=type_members, typeName="org.jawata.mcp.tools.DataTool")
inspect(kind=type_members, typeName="org.jawata.mcp.tools.RefactoringTool")
inspect(kind=type_members, typeName="org.jawata.mcp.tools.ChangeMethodSignatureTool")
```

Re-run before any step that sizes per-door work, and update this file in the same commit as
the code that changes a row.
