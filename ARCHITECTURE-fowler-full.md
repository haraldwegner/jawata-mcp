# ARCHITECTURE-fowler-full — where 51 new items land, without a bigger tool surface

**Scope:** Sprint 28d-rescue, signed off 2026-09-02. 45 Fowler refactorings, 5 smell
detectors, 1 commented-out-code check. The spec
(`jawata-enterprise/docs/sprints/jawata-mcp/sprint-28d-rescue-fowler.md`) says WHAT
ships and how it is measured; this document says HOW it is shaped. Where they speak
about the same thing, the spec governs scope and this document governs structure.
**The plan is written against this document.** A plan produced without it, or
contradicting it, is refused.

**Status:** v3 APPROVED 2026-09-02 by Harald. **v4 APPROVED 2026-09-06 by Harald** (*"sign off"*). The plan's S8b is written against v4. **v4.1 — one clause, on `needs[]` (§7b INVARIANT A, §12 step 4) — proposed 2026-09-06 by the plan's GATE 2 audit; APPROVED 2026-09-06 with the plan, on Harald's word (*"Skip it now. Only 8b left"*), the clause having been named in the ask as the one thing needing it.** v4 admitted a name as the only value the agent adds; the spec's own 30 detector-bearing rows include four whose door takes a position inside the addressed method, and `long_method → compose_method`, routed at HEAD, takes ranges — so under v4 as written INVARIANT A's test could never pass on a route that already ships.

**v3** — v1 added four tools; Harald ruled the surface must not grow (*"we do not want
to increase the number of tools. If we now increase we need to categorize, consolidate
and hide behind a lesser number of front doors at a later stage"*), so v2 consolidated
instead. v3 records his approval, takes both consolidation options, and carries the
name he gave this document (*"don't call it rescue but fowler-full"*).

**v4** — the spec was amended and signed on 2026-09-06 (D3a, the sentence added to D5, the probe added to D8, and the first entry under "Recorded decisions"). The amendment reverses the tier rule v3 inherited from `ARCHITECTURE-28d.md` line 174 — *"One route … → perform … several routes with nothing to choose between them … → advise"* — a rule 28d itself pre-declared at lines 164–166 as *"the clause that changes"*. Harald's rulings, verbatim from the spec: *"You have 3 alternatives. Pick the most appropriate one and perform"* · *"even if no smell is there, they should be included somehow"* · *"I do not want to be close to the code"* · *"There is nothing outside jawata. Only returns to a calling agent or studio consumes jawata-mcp"*. Measured the same day against the built artifact: 78 operations on nine doors, 18 named by a cure, 9 of D5's 30 detector-bearing work rows routed — because execution kept every smell to one fix rather than demote it to advice. v4 carries four things and decides no route: the tier is **RUN** when at least one cure is runnable and **CONSIDER** when nothing runs, and a smell's cures are **ranked, each with a discriminator** (§6 P4); **one address value type on both channels** — a finding names its cure with the address the door accepts, and a refusal names the next runnable step the same way (§7b, D3a); the **door population is derived** from the registry rather than written by hand (§6 P3), with the proof-of-life count anchored outside the derivation; and the gates v3 built on the old rule — §11 stage 0's *"perform OR advise"* and §12 step 2's *"`CureTierTest` unchanged and green"* — are replaced by gates that fail when the step did nothing (§12). Two product facts corrected on the way, both read from the tree: row 8 moved to `refactor_to_pattern` on 2026-09-03 (apply_cleanup 10 · refactor_to_pattern 11, sum 71 unchanged; §3's table is as signed and the plan carries the move), and the commented-out-code check §13 left open shipped as its own kind, `commented_out_code` (`FowlerDetectors` line 148). Which smell offers which cure, in what order, with what discriminator, is the plan's S8b, taken from the spec's "Finds it" column; v4 gives the shape, the rule and the invariant, and one worked example per seam.

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
   CLIENT — an agent, or the studio. Never Harald.
        │  copies ONE rendered call — a NextStep — off a finding, or off a refusal
        ▼
   ┌────────────────────────────────────────────────────────────────────────────┐
   │  NINE KindedTool DOORS · 78 operations · the set is a TYPE, not a list       │
   │  extract 11 · inline 5 · move 6 · apply_cleanup 10 · change_method_signature 11│
   │  hierarchy 7 · data 10 · refactor_to_pattern 11 · generate 7                 │
   │                                                                              │
   │   performs ─────► success {…, nextStep?}   ◄── row 34: what the sweep LEFT   │
   │   refuses  ─────► ErrorInfo {code, message, reason, nextStep?}               │
   │                                     ▲                                        │
   │                   D3a: the next runnable step, WITH an address (Command)     │
   └───────────────────────────────┬──────────────────────────────────────────────┘
                                   │ every kind is a delegate in tools/<family>/
                                   ▼
   ┌────────────────────────────────────────────────────────────────────────────┐
   │  AbstractApplyingRefactoringTool — the one pipeline, unchanged (Template     │
   │  Method) · prepareChange → compile gate → parity → purity → cache → undo     │
   └───────────────────────────────┬──────────────────────────────────────────────┘
                 ┌─────────────────┴──────────────────┐
                 ▼                                    ▼
   ChangeEngine.fromFileEdits            JdtRefactoringEngine
                 └─────────────────┬──────────────────┘
                                   ▼
                            RecipeEngine  ── the composed rows that ARE recipes (P2)
                                   ▲
                                   │ a step is any registered operation
                    ┌──────────────┴───────────────┐
                    │  OperationRegistry  (P1, P3)  │  name → publishing tools ·
                    │  + doors()  — the population  │  discriminator · structural
                    └──────────────┬───────────────┘  (Registry)
                                   ▲ validateAgainst: every step registered and
                                   │ unambiguous; every multi-cure kind discriminated
                    ┌──────────────┴───────────────────────────────────────────┐
                    │  CureCatalog  kind → RANKED [Cure(recipe, design, discriminator)]  (P4)
                    │  CureTier     RUN iff ≥1 runnable · CONSIDER otherwise ·          │
                    │               a declared step nothing backs is NAMED, never hidden │
                    │  CureLookup   renders every cure as a NextStep FROM the finding's  │
                    │               CodeAddress — the caller adds a name, nothing else   │
                    └──────────────┬───────────────────────────────────────────┘
                                   ▲ Finding {kind, CodeAddress, cures[]}   (Value Object)
                                   │ INVARIANT A: a routed kind's finding carries an address
                                   │ its cure's door accepts — or the join says so, per finding
                        the 41 detectors + 6 new — emit the FQN of the binding they resolved
```

Each seam, the pattern it uses, and the smell it prevents — the pattern entries are read off the catalogue (`catalogue:java-design-patterns/<slug>/README.md`, held by the store as `design:<slug>`; intent from each README's "Intent" section, consequences from its "Benefits and Trade-offs" section):

| Seam | Pattern | Intent (the entry's words) | The consequence that matters here | The smell it prevents |
|---|---|---|---|---|
| `OperationRegistry` + `doors()` | **Registry** — `design:registry` | "centralizes the creation and management of a global set of objects, providing a single point of access" | Benefit: "Facilitates decoupling between components." Trade-off: "a single point of failure if the registry is not designed to be fault-tolerant" — here the registry is filled by `ToolRegistry.register`, which sees every tool exactly once | *a policy expressed as a set of NAMES fails open when one of those things is renamed, retired or added* — four such lists existed at C8 (10 · 8 · 6 · 8 members against nine doors), complementary blind spots |
| `CodeAddress` | **Value Object** — `design:value-object` | "immutable objects that represent a descriptive aspect of the domain with no conceptual identity" | Benefit: "Easier to reason about and maintain." Trade-off: "Creating a new object for every change" — one per finding, per refusal; negligible | a half-address reaching a door: 37 of 40 emission sites pass the literal `-1` as column, and the other three pass a variable; a finding's line is 1-based, a door's is zero-based |
| `NextStep` | **Command** — `design:command` | "encapsulates a request as an object, allowing for parameterization of clients with queues, requests, and operations" | Benefit: "Decouples the object that invokes the operation from the one that knows how to perform it." Trade-off: "Increases the number of classes for each individual command" — one record, not one class per step | a pointer as prose the caller must transcribe: five composed rows name a kind in a refusal, none names the place to run it |
| the ranked `Cure` list | no catalogue pattern — **Specification** (`design:specification`, "Encapsulate business rules and criteria that an object must satisfy") was considered and is NOT adopted | — | its trade-off, "a proliferation of small classes … performance overhead due to the dynamic checking of specifications", is the expression language 28d refused (*"a guard is detector presence at the site … not an expression language"*, `ARCHITECTURE-28d.md` line 168). The discriminator stays a sentence the agent judges, anchored to a fact the finding already carries | the reversed rule's own smell: a second fix demoting the first to advice |
| `AbstractApplyingRefactoringTool` | **Template Method**, unchanged (22 subtypes, `ARCHITECTURE-28d.md` "Module placement") | — | — | a second pipeline |

Counts, with their instruments: nine doors = the production implementors of `KindedTool` (`find_references kind=implementations`, 11 minus two test fakes); 78 = the per-door counts the plan's C9 exit asserts exactly (71 across the eight v3 names, plus `generate` 7), of which `EveryShippedKindIsRoutedOrExplainedTest` measures 50 over six doors today; 18 named by a cure = the distinct recipe literals in `CureCatalog.BY_KIND`, recounted in this pass; 37 of 40 = every `new Finding(` call in `tools/smell/` whose fourth argument is the literal `-1`, by a parenthesis-balanced scan of the working tree that lists the three that are not (`AbstractAstDetector:117`, `ForbiddenEdgeDetector:77`, `OcpDetector:163`, each passing a variable).

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

## 6. The seams the sprint opens — two at S0 (v3), three at S8b (v4)

### P1 — `OperationRegistry`: what may be a step in a cure — OPENED

**Was.** `CureCatalog` refused to load if a cure named a step outside `RefactorToPatternTool.publishedKinds()`.

**Is.** `OperationRegistry` in `refactoring/`, fed by `OperationSurface.publish(tool)` from `ToolRegistry.register`, which sees every tool exactly once. It maps an operation to the SET of tools publishing it (three doors publish `method`, three `class`, two `variable` — measured at C8), refuses an ambiguous bare step, and classifies each qualified operation as mechanical or structural at registration. `CureCatalog.validateAgainst(registry)` runs once from `JawataApplication` after registration and still throws — boot fails loudly on a step nothing backs. `CureTier.derive()` reads the union of the pattern kinds and the registry, so a unit test that never wired the application does not mistake an empty registry for a missing route.

**What it still lacks, measured at the C8 watch.** It solved the CLASSIFICATION axis — *"the classification arrives WITH the registration … nothing to forget"* — and not the POPULATION axis: it knows every publishing tool and exposes no accessor for the door set, so the guards that need the population re-derive it by hand. That is P3.

### P2 — a recipe is an ordered list of registered operations — OPENED, narrower than planned

`Recipe(name, List<RecipeStep>)` and `RecipeStep(operation, arguments)` exist in `refactoring/`; a step is resolved through a `StepBuilder` the tools layer supplies, so `refactoring/` still depends on nothing in `tools/`. `RecipeEngine` is unchanged. Of the eight composed rows, four are recipes (2, 8, 10, 58 — the recipe engine's five construction sites minus `ComposeMethodTool`) and three ship as one prepared change (4, 36, 37); row 34 is a `CleanupRule`, not a tool. The C2 audit recorded this; v4 does not re-open it — the spec's standing rule (*"a chain that cannot hold together becomes written code"*) already covers a row built as one change.

### P3 — the door population is read off the registry, never written — v4

**Today.** Three hand-written enumerations of the door set remain in the working tree, and each is right by somebody remembering: `EveryShippedKindIsRoutedOrExplainedTest` lists six doors and asserts `examined == 50`; `DeclaredShapeHonestyTest.frontDoors()` lists ten; `TheCureTableRefusesAnAmbiguousStepTest` mirrors nine, and its own comment says the list *"is STILL hand-written … deriving it needs the registration seam M6c"*. The C8 watch counted four such lists (10 · 8 · 6 · 8); M10 replaced `OperationSurface`'s eight-name allowlist with `tool instanceof KindedTool`, and `KindedTool.structuralKinds()` is now a derived default (both uncommitted in the tree, suite green per the C8 run). The routing guard's blind spot is exactly where the allowlist could see, and neither could see the other — one missing object, two vantage points.

**Target.** ONE production source of the door population, and every guard reads it: `JawataApplication.registerTools()` constructs the nine doors inline (lines 1040–1112); `extract kind=method` lifts those constructions into `refactoringDoors(Supplier<IJdtService>, RefactoringChangeCache)`, which the application registers from and the three tests iterate. The filter is the type — `instanceof KindedTool` for the operation doors, `instanceof FrontDoor` for the description doors, which admits `refactoring` and nothing else (it implements `FrontDoor` and not `KindedTool`, deliberately). Java cannot enumerate a type's implementors without reflection, so the population is the application's own list applied to a type-shaped predicate — the plan's M6c, stated there and widened here to all three lists.

**The count is anchored outside the derivation, or it is a tautology.** The C8 watch's P3 warning: deriving a list turns a proof-of-life count over it into a count of itself. So the guard's proof of life is not `examined == doors().size()`. It is `examined == 78` **and** per door equal to the count this document assigns (§3's table with row 8's move, plus `generate` 7) — the same numbers C9 asserts exactly. A door leaving `refactoringDoors` changes production and the test in one edit, which is the property; a kind arriving on any door changes 78, and this document must be amended to move it.

**One worked example.** Under v4 the routing guard examines all 78. "Routed" means named by a cure at ANY rank, not only first. The kinds that shipped before this sprint on the newly examined doors — `refactor_to_pattern`'s ten, `generate`'s seven, `data kind=encapsulate_field` — are exempt BY NAME in `PRE_EXISTING`, as the test's own rule requires (*"an exemption list has to be about what was NOT worked on"*); no row of the 45 is ever exempt. Seven `SHIPPED_BUT_UNROUTED` reasons argue from the reversed rule (*"which the tier model turns to ADVISE the moment a second is added"* and its variants: `guard_clauses`, `decompose_conditional`, `function_to_command`, `introduce_parameter_object`, `separate_query_from_modifier`, `replace_type_code_with_subclasses`, `replace_superclass_with_delegate`); each is either routed or its reason rewritten in S8b. Which — is the plan's.

### P4 — a smell offers a RANKED list of cures, each with a discriminator; RUN means one runs — v4

**Today.** `CureCatalog.Cure(recipe, operation)` — two components, pinned by `CureTierTest.aRouteIsOneStepUntilACureNeedsTwo` with a delivery condition. `curesFor(kind)` already returns the list "best-first", so the ORDER exists and means nothing. `CureTier.derive` has five rules; rule 5 is *"Several runnable routes — ADVISE: nothing mechanical chooses between them"*, and rule 4 demotes a single route to ADVISE when `PARTIAL_ROUTES` carries a measured fraction for it (one entry, `apply_cleanup kind=loop_to_pipeline`, "29 candidates in 18 files … changes 2"; one reader). Four kinds declare several runnable cures today and all four render ADVISE: `ocp`, `divergent_change`, `shotgun_surgery` (three each), `lazy_class` (two). `CureCatalog`'s own comments record the rule's cost seven times over — routes deliberately not taken because a second fix would cost a smell its instruction.

**Target — the rule Harald ruled.** `Cure(recipe, operation, discriminator)`: the recipe as today; the catalogue design as today; and the sentence that tells this cure from its neighbours. `CureTier.Tier` becomes `RUN` and `CONSIDER` (`rename_symbol` on the two constants; the rendered words follow, and their readers are two files). `derive` keeps rules 1–3 unchanged — nothing declared → CONSIDER; nothing runnable → CONSIDER; a declared step the registry does not hold → CONSIDER **naming the step**, never narrowed — and replaces 4 and 5 with one: **at least one runnable cure, every step registered → RUN, carrying the ranked list**. `Derivation(kind, tier, List<Cure> runnable, reason)`. The caller picks; the caller is an agent.

**`PARTIAL_ROUTES` folds into the discriminator and the side table goes.** A measured fraction is exactly what tells a cure from the alternative of reading the loop yourself: the `loops` cure's discriminator carries "accepts a loop over a list declared empty directly above, no break/continue/return, one job per body — measured over java-design-patterns, 29 candidates in 18 files, changes 2". The tier no longer demotes on it; the reader judges. The transcription is pinned by a golden (the `CureCatalogTest` precedent, "byte-identical to the constant it replaced").

**INVARIANT 3, unconstructible like the other two.** A kind with two or more runnable cures where any lacks a discriminator does not load. It joins INVARIANT 1 (the pair is the entry identity) in the builder, and the builder's loop is lifted by `extract kind=method` into `validate(Map<String, List<Cure>>)` so a planted violating table is a TEST, not a C11a-style proof recorded in prose. A single cure may carry a discriminator (the partial case) and need not.

**What a discriminator is, and is not.** A sentence for the agent, anchored to a fact the finding already carries — because a detector emits on ONE shape from ONE site (the store's lesson, selected in this pass), so the fact that separates two cures is usually visible at that site. It is not a guard the product evaluates: 28d ruled *"presence only, never counts"* and refused an expression language, and v4 keeps that. When a guard machinery lands, 28d's own note says which clause changes.

**One worked example, already in the table.** `lazy_class` → `inline kind=class` — *when the class stands beside its only user and extends nothing*; `inline kind=subclass` — *when it stands under a parent and has no subtypes of its own*. Both facts are in the hierarchy the detector already walks. Today the pair renders ADVISE; under v4 it renders RUN with both, and it is the natural probe for D5's added measure on the built artifact: *"one smell carrying two shipped fixes offers both as runnable"*.

### P5 — one address value type, on the finding and on the refusal — v4

The contract is §7b. The seam: `CodeAddress` and `NextStep` in `models/` (the layer both `domain.Finding` and `models.ErrorInfo` can reach; `domain` already imports `models`), `CodeAddress.of(Finding)` as the ONE place that knows a finding's line is 1-based and a door's is zero-based, `CureLookup` rendering every ranked cure as a `NextStep` from the finding's address, `ErrorInfo` carrying an optional `NextStep` beside `reason`, and `OperationRegistry` rendering an invocation with the door's own discriminator (`hierarchy direction=up`, not `kind=` — the KEY stays `hierarchy kind=up`, which `CureCatalog`'s Stage-7 note already separates from the INSTRUCTION).

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

## 7b. The address contract — one value type on both channels

**The clause, as signed (D3).** *"Each one is callable straight from the finding that names it — it accepts both a symbol's name and a place in a file, and every other value it needs is one the finding already carries … A fix that makes the caller work out a position by hand is a hand-edit with extra steps and does not count as delivered."* And D3a, 2026-09-06: a refusal *"names it and where to run it, the way a finding names a cure … an address the caller runs unchanged except a name."*

**The measured state, from the working tree.** `Finding` is `(kind, filePath, line, column, severity, message, symbol)`, and its javadoc says *"Coordinates are 1-based (JDT convention); use `-1` when N/A"*. Of the 40 `new Finding(` sites in `tools/smell/`, **37 pass the literal `-1` as column** and the other three pass a variable (a parenthesis-balanced scan, this pass, listing the three); the doors refuse `column < 0` (*"line and column are required and must be zero-based non-negative integers"*, `EncapsulateFieldTool:120` and every composed row alike). No routed kind's site builds a package-qualified symbol — `feature_envy` emits the method's simple name, `lazy_class` the class's, `mutable_data` `Type#method` with the simple type name, `global_data` the field's name, the modernization adapters a type name derived from the file — while `FqnResolver` accepts only `com.foo.Bar`, `com.foo.Bar#member` and the overload form. And the finding's line is 1-based where `IJdtService.getElementAtPosition` is *"Zero-based"* (`DecomposeConditionalTool:204` adds one). So today no routed finding can be fed to its cure as emitted; the two detectors the amendment names are the two the auditor happened to read.

**INVARIANT A — address completeness.** For every kind with at least one runnable cure, every finding of that kind carries an address the cure's door accepts without the caller adding anything but a name — **v4.1: anything but what the cure's `needs[]` declares (§12 step 4)**: a `symbol` in `FqnResolver`'s form for a door whose target is a named symbol (`FqnTarget.materializePosition` then does the rest, as it already does for every symbol-targeted tool), or `(filePath, line, column)` with `column ≥ 0` for a range-targeted operation — which no finding can drive anyway, and which the table lists as unrouted for that reason. **v4.1:** a range or position INSIDE the element the finding addresses is a `needs[]` entry on that cure, not a reason to leave the row unrouted — D5's 30 include rows 8, 58 and 64, whose doors take the `if`, the temp's declaration and the boundary line, and `compose_method`'s `sections[]` are already routed at HEAD. The test checks completeness against the door's form LESS the cure's `needs[]`, and the rendered step prints `needs[]`. The whole-element range case (row 62, row 13's `extract kind=method` from nothing) stays as v4 says.

**Where it is enforced, and why there.**

- *Not on the `Finding` type.* Column `-1` is legitimate for a file-level finding (`commented_out_code`, `javadoc_lack`), and a constructor that refused it would constrain the 20 kinds that route nowhere for the sake of the 22 that do.
- *Not at the registration seam.* `DetectorCatalog.register` sees a detector, never a finding.
- **At the join, in production** — `AbstractAstDetector.withCures`, the one place a finding meets its cure. `CodeAddress.of(finding)` is built there; each ranked cure is rendered as a `NextStep` from it; an address the door's form cannot accept renders CONSIDER *naming the detector* rather than a runnable-looking call. That is fail-visible: the defect appears in the product's own output, on the finding, and cannot ship silent.
- **In a test derived from the table** — `EveryRoutedFindingIsAcceptedByItsCureTest` (§9): for every declaring kind with a runnable cure, run the detector over the fixture project, require ≥ 1 finding, require every finding's address complete for the door's form, and CALL the door with the rendered arguments — a refusal is allowed only for a reason that is not address-shaped. The population is `CureCatalog.declaredKinds()`, so a kind routed in S8b is guarded the day it is routed. This is the store's lesson applied: the invariant is checked at the emission site, per detector, because that is where the shape is decided.

**INVARIANT B — the invocation runs as rendered.** Every invocation the product renders — in a finding's cure, in a refusal's `nextStep`, in `ocpHint()` — uses the door's own discriminator. `OperationRegistry.qualify` hard-codes `kind=`; `hierarchy` dispatches on `direction` and refuses *"direction is required"*; `CureCatalog`'s Stage-7 note records that every qualified address for that door *"renders an instruction that does not run"*. Latent today (no hierarchy cure is routed), live the day one is. Enforced at registration — `OperationSurface.publish` has the tool in hand and passes `FrontDoor.discriminator()` — and by `EveryRenderedInvocationIsAcceptedByItsDoorTest` over every door and kind (§9). This is the C8 finding — three smells instructing a reader to run bare `data`, which the door refuses — generalised into a guard rather than fixed three times.

**The refusal pointer — the same value, the other channel (D3a).** A refusal today is `ErrorInfo{code, message, hint, reason}`; `reason` names which precondition declined (16 tools carry a `Refusal` code class; `getReason` has 89 references, all in tests, which is what it was added for). Five composed rows name a smaller step or the sibling by kind inside `message` (2, 4, 8, 10, 58); row 36 names the value it needs; row 37 names nothing; row 38 names Collapse Hierarchy by Fowler name; row 34 never refuses — `RemoveDeadCodeRule.edit` returns null and the door answers `hasChanges:false`. The shape exists as prose. v4 makes it a value: **`NextStep(operation, invocation, arguments, why, needs[])`** — a reified call the agent runs unchanged, `arguments` being a `CodeAddress` plus the row's own parameters, `needs` the values only the agent can supply (a name). It rides on `ErrorInfo.nextStep`, nullable and absent from the wire when unset, exactly as `reason` was added; a success that left a smaller step behind carries the same value on its `data` — the shipped precedent is `ToolResponse.error(code, message, hint, data)` with four production callers, one of them `WorkspaceHealth`'s per-project `remedy`. No second refusal channel, no second address vocabulary: a finding's `cures[]` and a refusal's `nextStep` are the same record, rendered by the same registry, from the same value type. *"D3a's stage should extend that shape, not add a second mechanism"* — the round-7 auditor's instruction, met by extension.

**What the contract does not claim.** A `NextStep` is right about the SHAPE — the step exists, the address is the door's form, the invocation parses. Whether it is the right step on code nobody wrote for it is what D8's probe proves and nothing else does; the fixture rows prove the shape only (§9, last two rows).

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

"Pure" runs with no workspace and no store; "fixture" needs the `simple-maven` project the existing detector and tool tests already load. Both run anywhere. The last two rows do not.

| What | Where | Runs |
|---|---|---|
| Per-operation behaviour, refusals, the gate battery | one test class per operation in `tests/tools/refactoring/` | fixture |
| **Tool honesty, all three axes** — enum equals routed set, parameters published, every kind named in the description | `DeclaredShapeHonestyTest`, over the population `refactoringDoors()` yields, filtered by type; the hand-written `frontDoors()` is gone | fixture |
| **Tier under the ruling** — RUN iff at least one runnable cure; CONSIDER otherwise; a missing step NAMED; per kind, never by counting tiers | `CureTierTest` against an explicit registry — the seam is the parameter, so this suite is now environment-independent; the no-arg `derive()` reads the global and is exercised by production and the render tests | pure |
| **Table invariants 1–3** — entry identity, every step registered and unambiguous, every multi-cure kind discriminated | `validate(Map)` and `validateAgainst(registry)` on planted tables: each guard shown throwing on a violating table and passing on the shipped one (`TheCureTableRefusesAnAmbiguousStepTest`'s shape, its door mirror now derived) | pure |
| **The partial measurement survives the fold** | a golden on `loops`'s discriminator text and RUN tier — `PartialRouteAdvisesTest` inverted, its control kept (`unused` still RUN with one cure) | pure |
| **Rendering from the address** — a bare symbol renders CONSIDER naming the detector; an FQN renders RUN with `symbol=`; the pinned wordings of the other branches unchanged | `CureLookupTest`, `CureTierTest.theHintCarriesTheDerivedTier` on hand-built findings | pure |
| **INVARIANT A, per routed kind** — every detector whose kind has a runnable cure fires on the fixture (proof of life ≥ 1 finding, else the fixture lacks that kind's target and the test names it), every finding's `CodeAddress` is complete for the cure's door form, and the door called with the rendered arguments refuses, if at all, for a reason that is not address-shaped (`filePath`, `line/column`, `symbol`, `kind`, `direction`) | `EveryRoutedFindingIsAcceptedByItsCureTest`, derived from `CureCatalog.declaredKinds()` filtered to runnable — no list of kinds | fixture |
| **INVARIANT B, every door, every kind** — the registry's rendered invocation is accepted by the door's discriminator parser | `EveryRenderedInvocationIsAcceptedByItsDoorTest` over `refactoringDoors()`; fails at HEAD on `hierarchy`'s seven | fixture |
| **Routing, 78 operations** — named by a cure at any rank, exempt by name, or explained; proof of life `examined == 78` and per-door equal to §3's assignment | `EveryShippedKindIsRoutedOrExplainedTest`, doors derived | pure |
| **The refusal pointer, per composed row** — the tested refusal is one at which a runnable smaller step or the sibling applies, whenever the row has such a refusal; `error.nextStep.operation` is that step and `error.nextStep.arguments` is the refused call's own address, or the row records in the test why it has none; row 34's `hasChanges:false` and applied responses carry `nextStep` naming `organize_imports` with the file | the row's own test class, one refusal per row as D3a's measure states | fixture |
| Recipe composition, the rows that are recipes | one test per recipe row, parity asserted at every step | fixture |
| A retired name still answers | one test per `RENAMED_TOOLS` entry | pure |
| **The boundary** — the product through its own front door, against the built artifact: tool count 42; each door's exact kind count; D5's added measure, *one smell carrying two shipped fixes offers both as runnable* (`lazy_class` today); a finding's rendered call copied verbatim and accepted | the end-to-end assertion before the release ask (S9) | one machine, the built distribution |
| **What only D8 can verify** — *one refusal on real work followed by the step it named being run*, unprompted. A fixture-authored refusal cannot show the pointer is right on a shape the author did not imagine (the store's lesson, selected in this pass); every fixture row above proves the shape, none proves the judgement | the dogfood window | reality only |

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
| 0 | P1 + P2, and the four unreachable fixes routed | `CureCatalog` loads with a standalone step; a feature-envy finding offers `move kind=method` — met at C0 under v3's rule, which accepted either tier and so could not fail. **v4 replaces that gate (§12, S8b-5): the finding offers it as RUN, with the finding's own FQN in the rendered call, and the rendered call is accepted by the door.** |
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

Each step is one commit; the parity gate for every step is `compile_workspace` at 0 errors, the full suite captured to a file, and the step's own gate below. A step applied by a jawata refactoring reverts through its `undoChangeId`; an authored edit reverts by commit. **Every gate names what fails at HEAD if the step did nothing, and the mutation that turns it red again.** The plan's S8b is written from these steps, in this order; S8b-6 precedes any route that targets `hierarchy`.

**The gates v4 voids, and what replaces them.** v3 §12 step 2 gated on *"`CureTierTest` unchanged and green"* — false now: the suite CHANGES at S8b-3, and "unchanged" would prove the step was skipped. v3 §11 stage 0 gated on *"a feature-envy finding offers `move_method` at whatever tier the rule derives — perform OR advise"* — a gate that accepted either answer could never fail; it is replaced by S8b-5's: the finding offers `move kind=method` as RUN with its own FQN in the rendered call.

0. **Commit the working tree.** M10 (`OperationSurface`: `instanceof KindedTool`) and the derived `KindedTool.structuralKinds()` default, with the three door-mirror additions. Gate: `OperationRegistryTest` green; the suite as the C8 run reported it (2800 / 2797 / 0). Mutation: none — this is the baseline.

1. **Rename the tiers to the ruling's words.** `rename_symbol CureTier.Tier#PERFORM → RUN`, `rename_symbol CureTier.Tier#ADVISE → CONSIDER`; the rendered literals in `CureLookup.hint()` follow. Gate: `grep -rn 'TIER: PERFORM\|TIER: ADVISE'` over `src` and `tests` returns nothing (readers measured at HEAD: two files); `EveryDeclaringKindRendersItsCureTest` green on `TIER:`. Mutation: the compiler — revert one rename and the other file does not compile. Reversible: two undo handles.

2. **Widen `Cure` and lift the builder's guard into a seam.** `Cure(recipe, operation, discriminator)` — a record's canonical constructor changes, so this is an authored edit followed by `compile_workspace(clean=true)`; then `extract kind=method` on INVARIANT 1's loop in `byKind()` → `validate(Map<String, List<Cure>>)`, and INVARIANT 3 added to it. Gate: `CureTierTest.aRouteIsOneStepUntilACureNeedsTwo` goes RED — its delivery condition fired — and is replaced by: three components; `validate` throws on a planted two-cure kind with one discriminator missing; passes on the shipped table. Mutation: delete INVARIANT 3's throw → the planted-table test passes for the wrong reason and says so by going green — so the test asserts the throw's message names the kind and the undiscriminated cure.

3. **RUN from any runnable cure; fold `PARTIAL_ROUTES` into the discriminator.** `Derivation(kind, tier, List<Cure> runnable, reason)` (authored, clean compile); rules 4 and 5 replaced by the one rule in §6 P4; `PARTIAL_ROUTES` and `partialReason` deleted after the one entry's text is transcribed into `loops`'s cure. Gate — discriminating, four assertions that are ADVISE at HEAD: `lazy_class` RUN with two cures; `ocp`, `divergent_change`, `shotgun_surgery` RUN with three; `loops` RUN with "29 candidates" and "changes 2" in its discriminator; controls: `coupling` and `composition_over_inheritance` CONSIDER (design-only), `switch_statements` with its step removed from the registry CONSIDER naming `replace_conditional_with_polymorphism`. Mutation: restore rule 5 → `lazy_class` red.

4. **`CodeAddress` and `NextStep`; the finding renders its cures from its address.** New `models/CodeAddress` (symbol in `FqnResolver`'s form or null; filePath; zero-based line and column; `complete(form)`; `arguments()`), new `models/NextStep` (operation, the rendered invocation, arguments, why, `needs[]` — the values the agent must add. **v4 admitted a name only; v4.1 admits, declared per cure in the table and printed on the rendered step:** a new name; an existing member of the addressed element selected by name (`functions[]`, `parameters[]`, `parameter`); a literal as written; or a position inside the addressed method — the `if`, a temp's declaration, a boundary line, `compose_method`'s sections. INVARIANT A subtracts the declared `needs[]` from its completeness check; a cure whose address is complete for the rest renders RUN with `needs[]` beside it, never CONSIDER. The plan records the D3 deviation this carries for the position cases); `CodeAddress.of(Finding)` subtracts one from the line, and nothing else does; `Finding` gains `cures` (`List<NextStep>`, empty by default) written by `AbstractAstDetector.withCures` and rendered by `Findings.toResponse` as a `cures` array beside `message`; the kind-level sentence stays cached per kind as today, the per-finding steps are string formatting. An incomplete address renders **CONSIDER naming the detector** — *"1 runnable cure, but this finding carries no address `data kind=encapsulate_field` accepts: symbol 'items' is not package-qualified, column absent"* — fail-visible, never fail-open. Gate: a hand-built finding with a bare symbol renders CONSIDER naming its kind; the same with `com.foo.Bar#items` renders RUN with `symbol=com.foo.Bar#items`; every wording `CureLookupTest` pins for the non-address branches unchanged. Mutation: `complete()` returns true unconditionally → the bare-symbol case renders RUN → red.

5. **Detectors emit addresses.** Every kind with a runnable cure emits the FQN of the binding it already resolved — ONE renderer, `CodeAddress.of(IBinding)` / `of(IJavaElement)`; the four hand-rolled `Type#member` renderers in the tree (`AnalyzeMethodTool:168`, `AnalyzeJavadocsTool:603–625`, `AnalyzeNullnessTool:461`, `ResolveOrRelocate:170–241`) are its future callers and not this sprint's work. `MutableDataDetector` and `GlobalDataDetector` first, then the rest of the routed set; `ModernizationSmells` derives a type name from the file and gets the package from the compilation unit. Gate: `EveryRoutedFindingIsAcceptedByItsCureTest` (§9) — at HEAD it fails for every routed kind, because 37 of 40 sites pass the literal `-1` as column and no routed site builds a package-qualified name; done when it passes with proof of life ≥ 1 finding per routed kind. Mutation: revert `GlobalDataDetector`'s symbol to the bare `name` → red naming `global_data`. This gate replaces v3 §11 stage 0's.

6. **The invocation runs as rendered.** `change_method_signature OperationRegistry#register(String, Collection, boolean, boolean, Set)` gains `String discriminator` (call sites default `"kind"`); `OperationSurface.publish` passes `((FrontDoor) tool).discriminator()`; `invocationOf` renders `<door> <discriminator>=<kind>` while the registered KEY stays `<door> kind=<kind>`. Gate: `EveryRenderedInvocationIsAcceptedByItsDoorTest` — for every door in `refactoringDoors()` and every published kind, the door called with only the rendered discriminator argument refuses, if at all, with something other than *"kind is required"*, *"direction is required"* or *"Unknown"*; at HEAD `hierarchy`'s seven fail. Mutation: hard-code `kind=` again → red on seven. Must precede any hierarchy route.

7. **`NextStep` on the refusal channel, and on row 34's response.** `ErrorInfo` gains a nullable `nextStep`, omitted from the wire when absent — the `reason` precedent; `ErrorInfo.invalidParameter(param, reason, reasonCode, NextStep)`; `CleanupRule` gains a default `leavesTo()` returning null, `RemoveDeadCodeRule` returns `organize_imports`, and `ApplyCleanupTool` puts a `nextStep` with the file(s) on both its `hasChanges:false` and its applied response for that kind. Each refusing composed row attaches a `NextStep` at the refusal where a runnable smaller step or the sibling applies — rows 2, 4, 8, 10, 58 already name the kind in prose; 36 names the value it needs; 37 says in writing if none applies; 38's Fowler-name pointer becomes `hierarchy direction=collapse_hierarchy` with the refused class's address. Which reason code carries which step, per row, is the plan's table from D3a's measured list; v4 fixes the shape and the address. Gate: per row, the test asserts `error.nextStep.operation` and that `error.nextStep.arguments` equals the refused call's own `CodeAddress` (or asserts absence with the written reason) — D3a's measure verbatim; row 34's test asserts `nextStep` on both responses. Mutation: drop the arguments from one pointer → that row red.

8. **Derive the three door lists.** `extract kind=method` on `JawataApplication.registerTools()` lines 1040–1112 → `refactoringDoors(Supplier<IJdtService>, RefactoringChangeCache)`; `EveryShippedKindIsRoutedOrExplainedTest`, `DeclaredShapeHonestyTest` and `TheCureTableRefusesAnAmbiguousStepTest` read it, filtered by type. The routing guard now examines 78 under "routed at any rank". Gate: `examined == 78` and per door equal to §3's assignment plus `generate` 7 — anchored outside the derivation; and the silent list is EMPTY. At HEAD, widened, it is not empty (measured before routing, `refactor_to_pattern` alone contributes three: `refactor_to_visitor`, `replace_pattern_with_idiom`, `replace_constructor_with_factory`; `generate` seven; `data` seven), and the seven reversed-rule reasons in `SHIPPED_BUT_UNROUTED` are each rewritten or their kind routed — the routing itself being S8b's work from the "Finds it" column. Mutation: remove one door from `refactoringDoors` → 78 red and the application publishes one door fewer, in the same edit.

9. **The boundary, at S9.** Through the built artifact's front door: tool count 42; each door's exact kind count; D5's added measure — `lazy_class` (or whichever smell S8b leaves with two runnable cures) offers both as runnable; one `find_quality_issue` finding's `cures[0]` copied verbatim into the door it names and accepted. Then D8: one refusal on real work followed by the step it named being run — reality only, recorded with the probe.

## 13. Open, and deliberately not decided here

- **The routes.** Which smell offers which cures, in what order, with what discriminator; which reason code of each composed row carries which `NextStep`; whether each of the seven reversed-rule reasons in `SHIPPED_BUT_UNROUTED` becomes a route or a rewritten reason. All of it is S8b's, from the spec's "Finds it" column and D3a's measured list. v4 gives the shape and one worked example per seam.
- **`generate`'s seven kinds under the 78-operation guard.** Codegen has no smell; they are exempt by name or explained by one reason. The steering that sends an agent to `generate` instead of typing boilerplate is homed in 28f (spec, "Deferred").
- **A discriminator that the product evaluates.** v4 keeps discriminators as sentences anchored to a fact the finding carries. If a guard machinery ever lands, 28d's note at lines 164–166 already says the tier clause is what changes; nothing here pre-empts it.
- **The four hand-rolled `Type#member` renderers** outside `smell/` migrating to `CodeAddress`. A later sweep; naming them is enough for now.
- **The `unused` detector's address shape.** Its findings are adapted from `FindUnusedCodeTool` outside `smell/` and were not read in this pass; the derived INVARIANT A test will say.
- **`Finding.line` on the wire.** It stays 1-based (editors and the studio read it); `CodeAddress.of(Finding)` is the one conversion. Changing the wire base is a contract change with callers this repository cannot enumerate, and nothing here needs it.
