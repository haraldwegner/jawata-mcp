# ARCHITECT REPORT — Sprint 28g, C5 (watch mode)

**Range** `ee333c1f..HEAD` — 9 commits (`42ebe83a`, `8691ffde`, `ffc86a01`, `234f3348`,
`68b44077`, `03044548`, `e6d197a6`, `12ef11cb`, `b1524246`), 26 files, +3341/-26.
**Subject** D9's four tool changes and D10's new operation.

**VERDICT: MIXED.** D9 is a design fix four times over - each change adds an operation whose
precondition is declared, refuses by code, and names what it costs; two measured no-ops were
DELETED rather than shipped. D10 follows a signed spec clause faithfully; its
placement tension was already on the board and is not re-opened here. What D10 newly exposed is a
live structural hole (F1) that no gate in this product can see, plus one inconsistency the board
does not record (F2).

**D-SIX status: CHECKED.** `org.jawata.mcp.tools` IS catalogued - jobs resolve, including for
files this checkpoint added. So the duplicate findings below are against the catalogue, not
against silence.

---

## F1 - INCOMPLETE DELEGATION: a tool's ability to report what it did is decided by its `extends` clause

Ranked first by the seat's own sequencing rule. This is the finding of the checkpoint.

**Measured, not read.** `AbstractRefactoringTool` declares three protected entry points -
`runRefactoring(descriptor, ...)`, `runRefactoring(refactoring, ...)`,
`runPreCheckedRefactoring(...)` - and **not one of them takes extras**. All three funnel into
`respondForChange`, which is **private**. A subclass on this base therefore has no reachable way
to add a field to its own success response: the base assembles the response on the subclass's
behalf and declines to forward the one fact only the subclass knows - what it actually did.

The sibling base carries it: `Preparation.of(change, summary, extras)`.

**The population, source-only** (both `find_references kind=implementations` results are
inflated by a second loaded copy of the same project - 25 and 42 rows resolve to a
`.cache/jawata-studio/workspaces/...` path, not a source file; the raw totals 51 and 85 must
not be quoted):

| base | source implementors | can report extras |
|---|---|---|
| `AbstractRefactoringTool` | **26** | **no** |
| `AbstractApplyingRefactoringTool` | **42** (+1 test) | yes |

**D10 hit this and the cure was per-instance.** It built an extras map on the first base and
discarded it; the repair was to move the tool to the other base. That fixes D10 and leaves the
hole. The next of the 26 pays again, and nothing fails when it does - a discarded map compiles.

**The live instance is in this diff.** `RemoveUnreferencedMemberTool` (D9 change 3) sits on the
26-side. Its members are `executeWithService`, `refuseIfUnsafe`, `overriddenDeclarer`,
`declarationOf`, `sites` - no extras-producing member, on a base with no extras channel. So it
cannot name the member it deleted in its success response. *Stated as derived from the base's
signature set, which was read; not from observing its response.*

**Actuator.** Not a helper (rule 2). Either `respondForChange` accepts the subclass's extras -
which means the two `runRefactoring` overloads and `runPreCheckedRefactoring` each gain the
parameter the applying base already has - or the two bases merge. A parity-gated signature
change on the three entry points; 26 call sites, every one in-workspace. **Homed to the seam
lane, not to C5**: it rewrites a base class two closed checkpoints depend on.

**Why no gate sees it:** a tool that wants extras and cannot have them produces a response that
is *correct and shorter*. There is nothing to assert against.

---

## F2 - ALREADY CARRIED, with one half that is not: D10's placement contradicts D9 change 3's

**MOST OF THIS IS NOT A NEW FINDING, and saying so is the point.** The plan's own S5 board row
already carries it, with the same actuator I had reached for: *"D10's spec text says 'one new
tool', which moves the published count 42 -> 43 against 28d-rescue's 46 -> 42 consolidation; its
natural home is `inline kind=...` beside `middle_man`, which deletes a forwarder for the same
reason. Following the spec's literal wording unless he rules otherwise."* The spec says one new
tool, the spec is signed, and the board records the tension and the resolution. **An architect
watch does not re-open a signed ruling**, so the count staying at 43 is faithful rather than
wrong, and I am not escalating it a second time.

What the board does NOT record is the half below, which is new.

For context the direction is 46 -> 42: every refactoring operation becomes a **kind on a front
door**, and `RefactoringDoors.standalone(...)` is the exception list. C9 round 2 named the latent
hole in its own words - *"a future standalone refactoring tool that DID carry a row could be
registered inline, and the count would silently not see it."* D10 is that tool:
`delete_anonymous_implementation` is registered via `standalone(...)`, and the count moved
**42 -> 43**.

**And the two placements in this diff disagree with each other**, which is what makes it a design
finding rather than a preference:

| operation | placed | what `inline` means | fits? |
|---|---|---|---|
| D9 change 3, dead published member | `inline kind=unreferenced_member` | the thing stops existing, its content **absorbed by its callers** | **no** - an unreferenced member has no callers to absorb into; its content is discarded |
| D10, forwarding anonymous class | **standalone tool** | - | the anonymous class stops existing and its role **is** absorbed by the promoted target - the best `inline` fit in the diff |

So the operation that does not absorb went on `inline`, and the one that does went standalone.

**Actuator, and it is HIS to take or leave, not mine to apply.** If the placement is ever
revisited, `inline kind=anonymous_implementation` settles both halves at once: it returns the
count to 42, keeps the C9 clause intact, and removes the inconsistency above by putting the
operation that DOES absorb on the door that means absorb. Cheap now (one registration, one kind
entry, the published description). **Recorded, not proposed** - the spec ruled.

**AND THE COST SENTENCE THAT STOOD HERE WAS FALSE, which a C5 audit measured.** It read "dearer
later: the M15 probe and `PerformedRefactoringCountTest` now both assert 43." Neither contained
43 in that sense: the probe had no 43 at all, and the test's only 43 is the Fowler ROW NUMBER of
Replace Constructor with Factory Function. What those two actually both assert is `inline` = 6.
So the argument rested on a cost that did not exist - inference written in the language of
measurement, which is the failure this project records as fluency over grounding. Worse, having
claimed the count was pinned twice, the report did not notice it was pinned NOWHERE: see F4.

**Contract change, consumers derived (rule 5).** The published surface moved: a new tool name,
`inline` gained a kind, `data`/`refactor_to_pattern` gained parameters, and `MoveMethodTool`
gained three. **Not re-asked:** Harald has already ruled on this consumer set - *"There is
nothing outisde jawata. Only returns to a calling agent or studio consumes jawata-mcp."* The
enumeration is answered, not incomplete. The one removal - `oldOwnerAccess=getter` - never
shipped in a release, so it is unreachable by any consumer.

---

## F5 - THE POPULATION OF HAND-WRITTEN SURFACE FACTS IS THIRTEEN, AND I UPDATED ONE

Not a finding about the code so much as the measured cost of this checkpoint's one omission, and
it is recorded because I reported the number wrong twice before measuring it out.

D9 change 3 published `inline kind=unreferenced_member`, moving the door from five kinds to six.
Thirteen places hold that fact by hand. **One of them was updated with the change**
(`PerformedRefactoringCountTest`); the other twelve were not:

| where it surfaced | count |
|---|---|
| the full suite's shards | **10** — `Stage6ShippedCountTest`, `ResponseShapeCensusTest`, `ArchitectGateSeesEveryStructuralKindTest`, `DegradationStampMarkingTest`, `EveryShippedKindIsRoutedOrExplainedTest`, `TheRoutingTableIsTheKindListTest`, `Stage6PerRowContractTest`, and three in `FrontDoorDescriptionTest` |
| the END-TO-END gate, which the first red run never reached | **2** — `tool-count-still-42` and `readme-lists-every-tool` |

**The second row is why my own count was wrong twice.** I reported "ten of eleven" and then
"twelve of thirteen", and only the second is right: shard failures short-circuit the e2e gate, so
the first run stopped before it and those two were invisible. A failure count read off a run that
exited early is a count of what the run REACHED, not of what is broken — which is the same
instrument lesson as the trailing `echo`, one layer up.

**Two of the thirteen also held a stale number in PROSE beside the list that settles it**, already
wrong before this checkpoint: `ResponseShapeCensusTest`'s javadoc spelled a sum of 78 against a
map of 79, and `DegradationStampMarkingTest`'s message said "the census population of 78" while
the assertion expected 79. Both had their arithmetic DELETED rather than corrected, which is this
sprint family's own earned cure — a number in prose beside the list has nothing holding it to the
list, and every sweep to correct such numbers has added one more.

**And twice a blind replacement would have corrupted something**: `jawata-mcp#80` / `#79` are
GitHub issue references, and one `22` is a fixture COLUMN number. The population was enumerated
from the failures' own messages rather than by matching the literal.

---

## F4 - THE M15 CLAUSE FAILED: the probe's central assertion was a tautology

Found by the C5 audit, not by me, and it is the sharpest finding of the checkpoint because the
clause it breaks is the one C5 owns.

`build/c5-m15-published-surface.sh` claimed in its own header that it proves *"health_check's
toolCount EQUALS the number of tools tools/list actually returns - the two disagreeing is how a
tool ships invisibly."* **They cannot disagree.** Derived through the production path:
`ToolRegistry.getToolCount()` returns `tools.size()`; `getToolDefinitions()` emits one entry per
element of that same map with no filter; `handleToolsList()` returns that list unchanged. Both
sides of the equality were `tools.size()`, so a tool vanishing moved them together and the check
passed. The only other constraint was a `len(names) > 20` floor, which a 21-tool surface
satisfies.

So M15's `toolCount` half - "recomputed from the shipped list" - was measured by nothing. The
per-door half IS a genuine recomputation (each door's enum read off its wire schema), and that
distinction is the whole finding: half the clause was met and half read as met.

**FIXED in this checkpoint**: both the list length and `toolCount` are pinned to the literal 43,
independently, and the header's false claim is replaced by what the probe now does. A floor
became an absolute for the same reason `EveryShippedKindIsRoutedOrExplainedTest` abandoned
`examined >= 30`: a floor cannot see a tool LEAVE.

---

## F3 - RULE 13 / D-SIX: the member-declaration job was re-derived privately, in the week this sprint deleted six copies of its sibling

**Enumerated by SIGNATURE, not by name** - this plan's own thrice-recorded trap.

`RemoveUnreferencedMemberTool.declarationOf(CompilationUnit, IMember)` and
`tools.shared.MethodLookup.declaration(CompilationUnit, IMethod)` call **the same five callees
in the same order**: `getNameRange` -> `getOffset` -> `NodeFinder.perform` -> `getLength` ->
`getParent`. The same algorithm, differing only in parameter type and return type.

**It carries no correctness defect** - it uses the identity join, not a name key. The finding is
D-SIX: a second implementation of a job the system already does, with one owner and ten callers.

**A finding I was about to make and did not, because it was checked.** The five `codegen`
`findTypeDeclaration(CompilationUnit, IType)` methods each call `TypeLookup.declaration` on the
line immediately after their own declaration (324->325, 507->508, 470->471, 399->400,
417->418). They are **one-line forwarders, not copies.** The type job is clean: one owner, 26
production call sites. Reporting those five as duplicates would have been theme resonance, not
evidence.

**Actuator, and the naive version is wrong.** Widening `MethodLookup.declaration`'s parameter to
`IMember` also widens its return to `BodyDeclaration`, which forces a cast on all 10 existing
callers. Instead: **add** `declaration(CompilationUnit, IMember): BodyDeclaration` to
`MethodLookup` and have the existing `IMethod` overload delegate and narrow. One new method, one
private body deleted, one call site repointed, ten callers untouched. `IMethod extends IMember`
and the body needs only `ISourceReference.getNameRange()`, which `IMember` provides - so this is
behaviour-preserving by construction, and `MethodLookup`'s ten callers are the ready control.

---

## D4 - what the pattern catalogue was asked, and what was selected

The per-checkpoint gate requires this named rather than implied. Asked as prose, since a design
question carries no symbol: *how should a superclass let a subclass contribute to a result the
superclass assembles, without each subclass reimplementing the assembly?* Eight candidates
ranked; **two selected**, six rejected. Ranking is not applicability, so each was read.

### SELECTED - Subclass Sandbox

- **Intent.** Fix the shape of an algorithm in the base and let a subclass supply the specific
  steps, through operations the base itself provides.
- **Consequences, as plainly as the benefit** (the entry's own, unparaphrased): it encourages
  reuse and makes new behaviour cheap to add, but *"can lead to a large number of subclasses"*,
  *"requires careful design to ensure that the base class is flexible enough for various
  extensions"*, and *"increases complexity in understanding the code flow due to multiple layers
  of inheritance."* Payable here: the second trade-off is **exactly F1's diagnosis**, so the cost
  is one already being paid badly.
- **Canonical address.** `java-design-patterns`, `subclass-sandbox/README.md` at
  `22a34127d`; reference implementation `com.iluwatar.subclasssandbox.App`.
- **What it made me do differently.** It reframed F1 from "add a parameter" to "one of the two
  bases is this pattern done correctly and the other is the same pattern with an
  under-specified sandbox operation." `AbstractApplyingRefactoringTool` declares the step
  (`prepareChange`) and the subclass returns a structured `Preparation` carrying change, summary
  and extras; the base assembles. `AbstractRefactoringTool`'s sandbox operation instead hands
  back a FINISHED response, so the subclass has nowhere to put its contribution - and D10's
  discarded extras map is the subclass trying to do the assembly itself, which is this pattern's
  stated failure mode. **So F1's cure invents nothing: make the 26-side match the 42-side.**
  Without this I would have proposed a parameter and missed that the target already exists in the
  tree.

### SELECTED - Delegation

- **Intent.** Hand a task to a helper object the owner holds, instead of inheriting the task from
  a parent.
- **Consequences** (the entry's own): it reduces subclassing and lets behaviour change at
  runtime, at the cost of *"additional layers of indirection, which may result in slight
  performance costs"* and a design that *"can become more complicated since it involves
  additional classes and interfaces to manage delegation."* Payable, and **this codebase has
  already paid it once for the same kind of concern**: `FrontDoorDescription` is *"HELD not
  inherited"*, for the recorded reason that *"`apply_cleanup` and `data` already have a
  superclass each and Java has one."*
- **Canonical address.** `java-design-patterns`, `delegation/README.md` at `22a34127d`;
  reference implementation `com.iluwatar.delegation.simple.App`.
- **What it made me do differently.** It answers a question F1 alone does not: *why are there two
  bases at all?* Because response assembly is INHERITED, so a tool gets exactly one flavour of it
  and the choice is made by an `extends` clause. Held as a collaborator, the flavour becomes an
  argument and the split has no reason to exist. That is the larger of the two cures and the one
  I would put to the seam lane; the Sandbox cure is the smaller step that can land first without
  foreclosing it.

### REJECTED, with the reason - because skipping this is how a pattern becomes a cargo cult

- **Curiously Recurring Template Pattern** - its situation *("a base class must refer to the
  concrete subclass type without casting")* is adjacent and its **consequences are ones I am not
  willing to pay**: a self-type generic threaded through 68 tool classes to deliver a value that
  a parameter delivers. Rejected on cost, not on fit, which is the judgement the consequences
  exist for.
- **Twin** - *"must effectively extend two hierarchies and the language allows only one"*. The
  one-superclass constraint IS part of F1's cause, but the cure is a coupled pair of objects per
  tool, heavier than the concern warrants.
- **Servant** - closest neighbour to Delegation here, rejected because its cure is a stateless
  operator over a group of elements and the assembly needs the tool's own context. Naming it
  rather than silently dropping it, since selecting both would have been noise.
- **Factory Method, Decorator, Single Table Inheritance** - their situations do not describe this
  one (choosing a concrete type, adding responsibilities at run time, a database schema).

---

## The question asked: are the three mutation-found defects one structure or three?

**Three, and the dominant one is not new.** Clustered rather than counted:

| structure | instances in this diff |
|---|---|
| **cannot be distinguished from its own absence** | `oldOwnerAccess=getter` (byte-identical output to `parameter`) - G5 inert (`if (false && ...)`) - change 4's ORDER claim with no assertion |
| **a real effect the response does not name** | `parameter` mode widens `private int budget` to `public` |
| **a refusal shadowed by an earlier one** | D10 answered `FORWARD_RENAMES_THE_METHOD` for an implicit receiver |

**Rule 12 does NOT fire.** The first cluster is the defect class this plan records at every
checkpoint from C3 onward - one recurring symptom, which is ordinary, not each fix exposing a
new face. The third is the shadowing class recorded seven times.

**The sharper point is about the instrument, not the defects.** All three were found by a
**mutation**; none by review. This sprint has a working instrument for the dominant class and
**no gate** for it - mutations are run by hand, per row, by whoever remembers. That asymmetry,
not the three defects, is what will produce the fourth.

**Rule 7 note, pointing the other way:** `parameter` mode makes a private field public. It is
the inverse of an encapsulation refactor, and the only thing preventing it from being invisible
is that the refusal names it and one test pins `public int budget = 100;`. Keep both.

---

## Below the fold

- **The by-name member lookup survives in four places**, as a population rather than an
  instance: `declarationOf(CompilationUnit ast, String name)` in `MoveFieldTool:355`,
  `MoveStatementsIntoFunctionTool:485`, `data/ReplaceDerivedVariableWithQueryTool:484`,
  `data/ReplacePrimitiveWithObjectTool:576`. C8b closed the by-name key for **types** as a class
  (`typeNamed` deleted six times; *"there is no by-name entry point left to spell"*) and left it
  standing for **members**. `MoveStatementsIntoFunctionTool` is the plan's own carried open
  item, *"on notice"* since C8b round 2 - this measurement reframes it from one instance to
  four. `SplitVariableTool.declarationOf(MethodDeclaration, IVariableBinding)` is the control:
  correctly scoped and identity-keyed, so the codebase knows the right shape.
  **Not actioned** - pre-existing, one already carried as a decision.
- **AND THAT POPULATION WAS FOUR WHERE IT SHOULD HAVE BEEN FIVE** - the C5 audit found the
  instance THIS DIFF added, which my own enumeration missed.
  `RefactorToStateTool.bodyWriteRefusal` builds its field set by TOKENISING the condition's
  source text and then joins on the string, so the set holds `null` and `this` alongside the
  field names, and a local or a field reached through a different receiver matches by spelling.
  It over-refuses, which is the safe direction, and resolved bindings were available two methods
  up (`fieldRefusal` uses `resolveBinding()`). A report that enumerates a population and omits
  the member its own checkpoint contributed is the recall-instead-of-measurement failure applied
  to my own diff.
- **D-SIX disposition for the two dead-member doors.** `apply_cleanup kind=remove_dead_code`
  already removes private dead members. This is **not** a duplicate under the seat's own first
  exception: the lanes genuinely behave differently - a private member's consumer set is closed
  by the language, a published member's is not, and `consumersAreInWorkspace` exists to make the
  caller assert exactly that. One pipeline would hide the difference rather than remove it.
- **The sweep door was structurally unavailable for D9 change 3**, not merely unattractive: the
  `CleanupRule` law is that *"no rule adds a parameter of its own"* and a rule cannot refuse,
  while the new operation requires `consumersAreInWorkspace` with no default and refuses seven
  ways. The placement question is therefore only *which* door, which is F2.
- **TEN PUBLISHED REFUSALS AND ONE PUBLISHED PARAMETER SHIP WITH ZERO COVERAGE**, censused by
  the C5 audit over the test tree (0 = no file mentions it): `oldOwnerName`,
  `RECEIVER_IS_NOT_A_VARIABLE`, `CREATED_TYPE_IS_NOT_AN_INTERFACE`,
  `TARGET_LACKS_A_FORWARDED_METHOD`, `TARGET_IS_NOT_IN_THE_WORKSPACE`,
  `TARGET_IS_NOT_A_CLASS_DECLARATION`, `BODY_IS_EMPTY`, `TYPE_UNRESOLVED`, `NOTHING_TO_REMOVE`,
  plus six of `RefactorToStateTool`'s new refusals and D10's already-implements path.
  **ONE of them was CLOSED here and the rest are carried, and the distinction is the whole
  point**: `FORWARD_RENAMES_THE_METHOD` was reachable until THIS checkpoint moved the receiver
  check ahead of the name check — a correct reorder that deleted the only route to the branch it
  reordered, which is half a repair. It now has `wrapperThatRenames` and a test asserting its
  reason code. The others predate the checkpoint or are structurally unreachable — `BODY_IS_EMPTY`
  needs an interface whose body declares nothing, which will not compile — and closing them is a
  coverage project rather than a checkpoint's work. Carried in the same form this sprint family
  carries the `MAX_REFERENCES` caps: named, measured, and not claimed as covered.
- **The end-to-end gate's failure message names a log it never writes.** It printed
  *"Read .../end-to-end.log or re-run it alone"*; no such file exists on disk. The output is
  inline in the suite log, so nothing was lost, but a reader following the instruction finds
  nothing and cannot tell a missing log from a missing run. Not fixed here - it is in the gate's
  own reporting rather than in this checkpoint's subject.
- **`HealthCheckToolTest` asserts `toolCount` 56** and stayed green through D10 publishing a new
  tool, because the registry it counts is its own fixture rather than the application's.
  Carried, measured at C5, and the reason the M15 probe reads the wire instead.
