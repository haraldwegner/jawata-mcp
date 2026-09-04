# Target architecture — the parametric front door as a complete delegation

**Status: PROPOSAL.** Produced by the architect seat in design mode, 2026-09-04, at Harald's
instruction after he named the defect class from one word. Nothing here is applied.

**Scope:** the eight `kind`-dispatching tools in `jawata-mcp`, the `Tool` interface they
implement, `ToolRegistry.register`, and the tests that reconcile against them.

---

## SCOPE — ALL EIGHT doors, a ninth as it is created, and a tenth on one seam only

**A door either satisfies the law or it does not.** Covering six would make it a convention
with two named exceptions, and the next door added has a precedent to point at. Covering all
eight makes it a property of the type: `KindedTool` cannot be implemented without answering
for its delegates, so a door that does not is not a door. That is the difference between a
design and a habit, and it is the whole reason this document exists — every symptom it opens
with is a habit that was not a rule.

**Scope is therefore the eight in `ToolRegistry.REFACTORING_FRONT_DOORS`**, plus
`change_method_signature`, which Stage 4 turns from a single operation into an eleven-kind
door and which therefore joins as a ninth.

**WHEN each adopts the seam is not uniform, and that is a sequencing fact, not an exemption.**

| Door | State | Adopts the seam |
|---|---|---|
| `extract` | grew 6→11 in Stage 6, drifted to 4 of 11 in its USAGE line | the seam stage |
| `inline` | grew, and published fewer kinds than it has | the seam stage |
| `move` | grew 2→6, published 3 of 6 | the seam stage |
| `apply_cleanup` | Stage 3 CLOSED, no active lane. Already derives `KINDS` from `RULES.keySet()` and its bullets from `describe()` — it is the door nearest the target already | the seam stage; the conversion is small there and that is the point, not a reason to skip it |
| `refactor_to_pattern` | Stage 3 closed. Typed fields plus its own `public static publishedKinds()` | the seam stage |
| `generate` | in no lane. Typed fields | the seam stage |
| `data` | **grows 1→10 in Stage 5** | inside Stage 5, as it grows |
| `hierarchy` | **grows 2→7 in Stage 7**. Discriminator is `direction`, not `kind` | inside Stage 7, as it grows |
| `change_method_signature` | **becomes a door in Stage 4**, 1→11 | inside Stage 4, as it becomes one |

**Why the last three adopt in their own lanes rather than in the seam stage.** Converting a
door and then growing it does the work twice; growing it by hand and migrating afterwards
does it twice and drifts in between. Building the new kinds directly on the seam does it
once. This is also what makes the growth safe — the three lanes are exactly where the
measured defect came from, since `extract`, `inline` and `move` broke *because Stage 6 grew
them*.

### `refactoring` — the tenth door, and the reason the two seams are separable

`refactoring` is a kind-dispatching tool by every structural test here: a published
discriminator (`action`, seven values), four typed delegate fields, a hand-written
description, a hand-written `ACTIONS` literal. `DeclaredShapeHonestyTest.frontDoors()`
already treats it as a door. Earlier drafts of this document needed it in three separate
clauses — the `action=` rendering, the boundary test's non-`kind` case, and C-THREE's list
derivation — and left it out of scope, which is an unnamed exception in the section whose
job is to forbid them.

**It is not a peer of the other nine, and the design says so rather than exempting it.**
Their kinds are TRANSFORMATIONS OF CODE. Its actions are LIFECYCLE VERBS over a change the
others produced — apply, undo, inspect, plan. That is a different level, and it is exactly
why `ToolRegistry` excludes it by name today: *"a reporting tool's kind enum is a list of
questions, not of transformations."*

**The arity is the tell, not an obstacle.** Seven actions dispatch to FOUR delegates
(`RefactoringTool:131` — `planTool` serves `plan`, `apply_plan`, `inspect_plan`,
`undo_plan`). A `Map<String, KindDelegate>` with a single-valued `kindName()` cannot express
that, and it should not have to: actions that group over a delegate are not per-delegate
kinds. A door whose kinds map one-to-one onto delegates and a façade whose verbs group over
them are two different things.

**So `refactoring` takes the DESCRIPTION seam and not the DELEGATE seam.** It holds a
`FrontDoorDescription`, so its description is assembled and its `USAGE:` line renders
`action="<apply|undo|…>"` from its own discriminator. It does **not** implement `KindedTool`,
so its verbs never enter the operation namespace.

**This is what C-TWO's composition buys, and it is the reason the choice was not a
coin-flip.** Had the algorithm stayed a shared superclass, a door could not take one half
without the other, and `refactoring` would have had to be either wrongly admitted or
explicitly exempted. Held rather than inherited, the two seams separate cleanly:

| | delegate seam (`KindedTool`) | description seam (`FrontDoorDescription`) |
|---|---|---|
| the nine transformation doors | yes | yes |
| `refactoring` | **no** — verbs group over delegates, and they are not operations | **yes** |

**And this removes the policy problem rather than relocating it.** After the migration
`instanceof KindedTool` means exactly what `REFACTORING_FRONT_DOORS` plus the
`LIFECYCLE_FRONT_DOOR` exclusion mean today — because the structural fact now matches the
policy instead of approximating it. That is why M10 can retire the constant at all; see M10
for what still cannot be derived and stays written down.

**The sequencing this yields, and it is an INPUT dependency rather than a file collision.**
The seam stage touches six doors, none of which any open lane owns — Stage 3 is closed, and
`generate` is in no lane. Stages 4, 5 and 7 depend on the seam stage because they **consume
what it produces**: `KindDelegate` and `KindedTool` must exist before a lane can declare its
new kinds against them. Nothing collides; the types simply have to be there first.

**One consequence to state rather than discover.** `POSITION_REFUSING_KINDS` on
`apply_cleanup` is NOT removed by this design. Its own javadoc says why — *"a property of the
EDIT TREE it produces on a given file, not of the kind"* — so no static role method can carry
it. It stays, with that reason, and the design does not claim it.

---

## REVISION 2 — 2026-09-04, after a watch-mode review found the target false for two doors

Revision 1 said *"the kind set is `delegates().keySet()` — the routing table **is** the
enum"*, and drew `delegates() : Map<String, Tool>` with `AbstractFrontDoor` as a shared
superclass. Both halves are false against the code.

**THE DELEGATE-SHAPE CENSUS IS NOT WRITTEN HERE, AND THAT IS DELIBERATE.** Revision 1 said
the target held for all eight. An earlier draft of this revision said it failed for two.
A review measured and found neither number right. Three hand-written versions of one property
of eight objects, each corrected by reading some of them, is the exact defect this document
is about — so the fourth version is not a table, it is a **measurement step**, M0 below, run
before any door is converted and its result carried into M3b's sizing.

**AND THIS DOCUMENT NO LONGER STATES A COUNT.** A fourth hand-written census would be the
fourth face of the same thing. What the design needs is three QUALITATIVE facts, each of
which is true whatever the numbers turn out to be:

| Fact | Consequence for revision 1 |
|---|---|
| Some door holds delegates that are **not `Tool`s** (`apply_cleanup` holds `CleanupRule`s) | `Map<String, Tool>` cannot type the map — hence `KindDelegate` |
| Some doors hold **typed fields rather than a map** | "each door declares its map" is a CONVERSION, not a rename — hence M3b is per-door work |
| Some doors **already have a superclass** (`apply_cleanup`, `data`) | a shared base is impossible in Java — hence composition |

**Every number lives in one place: `ARCHITECTURE-front-door-census.md`,** produced by M0 and
checked in. Any step whose per-door work disagrees with that file is refused. If you find a
count in the prose of this document, it is a defect — delete it and point here.

**And a correction inside the correction:** an earlier draft called `PullUpTool` and
`PushDownTool` *"plain classes"*, distinct from delegate `Tool`s. They both extend
`AbstractRefactoringTool`, which implements `Tool`. They are delegate `Tool`s held in typed
fields — the same shape as `generate`'s seven and `refactor_to_pattern`'s eleven, not a
special case.

The codebase had already written this down and revision 1 did not read it —
`DeclaredShapeHonestyTest:335`: *"Not delegate maps: their kinds are switch arms over one
implementation, so there is no per-delegate schema."*

A third fact makes the generated `USAGE:` line wrong as drawn: **a front door's discriminator
is not always called `kind`.** `ToolRegistry.java:191` says so in production — *"`kind` on
most tools, `direction` on `hierarchy` and `action` on `refactoring`."*

### The three corrections

**C-ONE — the delegate face is narrowed, and it is a ROLE, not a value object.**
`delegates()` returns `Map<String, KindDelegate>`, where `KindDelegate` is the narrow face a
front door actually needs:

```java
public interface KindDelegate {
    String kindName();          // the value of the discriminator that selects it
    String kindSummary();       // what it does, what it refuses, and WHY
    JsonNode parameterSchema(); // the properties this kind adds to the envelope
    boolean isStructural();     // changes a signature or a hierarchy
}
```

Every existing delegate `Tool` implements it with four one-line methods over what it already
holds. `PullUpTool` and `PushDownTool` implement it directly, which turns `hierarchy`'s two
typed fields into a map without changing what they do. **Each `CleanupRule` implements it
too, and thereby gains behaviour it currently has none of** — which is the standing bias
(give the object its behaviour) rather than exempting two doors from the design.

**This is not the `Kind` value object §3 rejects, and the distinction is the whole reason it
is admissible.** That rejection stands: a separate data class holding name + summary + params
would hold no state the delegate does not already hold and make no decision it does not
already make — a helper on the side. `KindDelegate` is implemented BY the delegate. Nothing
is moved off the object; a narrower view of it is published. Interface Segregation, not a
new collaborator.

**C-TWO — the description algorithm is COMPOSED, not inherited.** `AbstractFrontDoor` as a
shared superclass is unbuildable: Java has single inheritance and `apply_cleanup` and `data`
already have a base each. Inserting it above those bases would apply it to every tool
underneath them rather than to the doors. So the algorithm becomes an object the door
**holds and forwards to** — `FrontDoorDescription` — assembled from `preamble()`,
the generated regions and `footer()`. Cost: one forwarding pair per door, eight in total.
What it buys: no inheritance conflict, no door forced to abandon its base, and the algorithm
stays exactly one implementation.

The `final` guarantee revision 1 bought from Template Method is bought instead by the door
forwarding to a shared instance it does not subclass — a door *can* still write its own
`getDescription()`, and the gate that catches it is C6a's containment rule plus the
per-door test that its assembled output equals the shared algorithm's.

`FrontDoorDescription` takes the **discriminator name** from the door, so `hierarchy` renders
`USAGE: hierarchy(direction="<up|down>", …)` and `refactoring` renders `action=`.

**C-THREE — the hand-written door list is retired, and it is the same defect one level up.**
`ToolRegistry.java:115` holds `REFACTORING_FRONT_DOORS = Set.of("extract", "inline", "move",
"hierarchy", "data", "apply_cleanup", "refactor_to_pattern", "generate")` — a hand-maintained
copy of a fact the objects own, which is precisely what §9's law condemns. Revision 1 left it
standing, so its migration would have ended with the defect class still present in the very
method its first step moves. Once `KindedTool` exists the question is answerable by
`tool instanceof KindedTool`, and the constant is deleted.

**Note for anyone comparing lists, CORRECTED.** An earlier draft of this paragraph called
that constant *"the only authoritative enumeration of the eight doors"* and said any list
naming a different eight was wrong. That is false, and a review caught it.
**There are two lists and they answer two different questions, and both are correct:**

- `ToolRegistry.REFACTORING_FRONT_DOORS` answers *whose kinds are OPERATIONS a cure step may
  name* — so it contains `data` and excludes `refactoring`, whose `action` values are
  lifecycle verbs rather than transformations.
- `DeclaredShapeHonestyTest.frontDoors()` answers *who must describe their kinds honestly* —
  so it contains `refactoring` (it publishes a discriminator with six values) and excludes
  `data` (it publishes none yet).

Declaring one authoritative over the other is the copy problem pointed backwards: it would
mark a correct test as wrong. What the design owes instead is that **neither list stays
hand-written** — after M3b the first is `tool instanceof KindedTool` filtered by the
operations policy (see M10), and the second is the same `instanceof` unfiltered.

**The human's diagnosis, which this design accepts:** *"the actual error I assumed
immediately when I heard the word guard is incomplete delegation."*

That is the whole finding. **The guard is the tell.** Every one of S1–S4 was historically
answered by building something on the outside that looks in: a private reader that digs into
a Map, three copies of that reader in tests, a three-axis honesty test, a hand-kept row table
with an arithmetic claim in a comment. You build a guard when the object will not answer the
question. A front door that holds delegates and forwards only `execute` is a delegation that
stops one method short of complete — and every question the front door refuses to answer
becomes someone else's guard.

The delegates already know everything the guards are re-deriving. Each delegate implements
the full `Tool` protocol — its own `getName`, `getDescription`, `getInputSchema` — and *none
of it is reachable*. The front door does not lack information. It lacks a forwarding path.
This design builds the forwarding path and deletes the guards that existed only because it
was missing.

## The four symptoms, for a reader arriving cold

- **S1.** The logic that reads a tool's kind list off its JSON schema exists in production
  once (private, in `ToolRegistry`) and verbatim in three test files, plus a fourth variant.
  Nothing on `Tool` will answer "which kinds do you publish?", so every caller re-derives it.
- **S2.** Hand-written descriptions drift. `inline` published 3 of its 5 kinds in its USAGE
  line, `move` 3 of 6, `extract` 4 of 11. One also claimed a refusal the tool does not
  perform.
- **S3.** The gate meant to catch S2 asserts only that each kind's name appears *somewhere*
  in the description — which a complete bullet list under a stale USAGE line satisfies.
- **S4.** Hand-kept row tables in tests assert nothing about their own size. Two rows were
  found missing in successive audit rounds, each repaired by adding the row.

---

## 1. One coherent target

**The front door is a complete delegating composite over its delegates: everything it
publishes about a kind is a projection of the delegate that implements that kind, computed in
one place, at one time, by one algorithm.**

Three facts follow, and S1–S4 are consequences of them rather than things a test looks for:

| Fact | Consequence |
|---|---|
| The kind set is `delegates().keySet()` — the routing table *is* the enum. | The declared enum cannot differ from the routed set. There is no second list to differ from. |
| The published description's kind-bearing regions are generated from `delegates()`. | A `USAGE:` line listing 4 of 11 cannot be written; it is not written by a human at all. |
| Exactly one method answers "which kinds does this tool publish?", and it lives on `Tool`. | Nothing walks `schema.get("properties").get("kind").get("enum")`. There is no reader to copy. |

Everything below is *one* target expressed at four seams. There is no pattern-per-finding;
the four symptoms are four faces of one missing forward.

---

## 2. The picture

```
                            ┌────────────────────────────────────────────┐
   MCP client  ────────────▶│  Tool          (protocol face, ~40 impls)  │
   reads name /             │    getName()  getDescription()             │
   description /            │    getInputSchema()  execute()             │
   schema, unchanged        │  + default publishedKinds()  -> List.of()  │◀── THE one reader
   (protocol contract       │  + default kindSummary()     -> 1st para   │    (S1 dead)
    is fixed)               │  + default isMechanical/isStructural/…     │
                            └───────────────────┬────────────────────────┘
                                                │ extends
                            ┌───────────────────▼────────────────────────┐
                            │  KindedTool                       (REV 2)  │
                            │    discriminator() : String                │ ◀── "kind" |
                            │    delegates() : Map<String,KindDelegate>  │   "direction" |
                            │    publishedKinds() = delegates().keySet() │     "action"
                            │                       (final, overriding)  │
                            └──┬──────────┬──────────┬───────────┬───────┘
                               │          │          │           │
                        ExtractTool  InlineTool  HierarchyTool  ApplyCleanupTool
                         (AbstractTool)          (AbstractTool)  (AbstractApplying…)
                               │          │          │           │   ← DIFFERENT bases,
                               │ holds ───┴──────────┴───────────┘     which is why the
                               ▼                                       algorithm is HELD
                            ┌────────────────────────────────────────────┐
                            │  FrontDoorDescription   (one instance,     │
                            │                          composed not      │
                            │                          inherited — REV 2)│
                            │   describe(door) =                         │
                            │        door.preamble()     ← hand-written, │
                            │                              KIND-FREE     │
                            │      + usageLine(door)          ← generated│
                            │        USAGE: name(<discriminator>="<a|b>")│
                            │      + bullets(door.delegates()) ← generated│
                            │      + door.footer()       ← hand-written  │
                            │   schema(door) =                           │
                            │        door.envelope()     ← hand-written  │
                            │      + kindEnum(delegates())    ← generated│
                            │      + params(delegates())      ← generated│
                            └───────────────────┬────────────────────────┘
                                                │ reads
                               ┌────────────────▼───────────────┐
                               │ delegates()                    │
                               ▼                                ▼
      ┌───────────────────────────────────────────────────────────────────┐
      │  KindDelegate  (REV 2 — a ROLE the implementor already fills)     │
      │    kindName() · kindSummary() · parameterSchema() · isStructural()│
      │                                                                   │
      │  implemented by:                                                  │
      │    delegate Tools   ExtractMethodTool, InlineVariableTool,        │
      │                     PullUpTool, PushDownTool, …                   │
      │                     — held in a map by some doors, in typed       │
      │                       fields by others; see the census file       │
      │    non-Tools        each CleanupRule          (apply_cleanup)     │
      │                     ── the ONE door whose delegates are not       │
      │                        Tools, which is why the role is not        │
      │                        typed Map<String, Tool>                    │
      │  ─────────  a delegate does NOT know its front door  ─────────    │
      └───────────────────────────────────────────────────────────────────┘


   REGISTRATION — the only write path into the operation model:

      ToolRegistry.register(tool)
                 │
                 ▼
      OperationSurface.publish(tool)          ◀── public; tests call THIS
           tool.publishedKinds()
           tool.isMechanical() / isStructural() / structuralKinds()
                 │
                 ▼
      OperationRegistry.theRegistry()   (process-wide singleton, single writer)
                 ▲
      tests ─────┘   read the derived set through OperationSurface,
                     never through getInputSchema()
```

### Dependency direction — who may know whom

```
  delegate Tool      ──▶  Tool, KindDelegate            (allowed)
  KindedTool         ──▶  Tool, KindDelegate            (allowed)
  KindedTool         ──▶  FrontDoorDescription          (allowed — it HOLDS one, REV 2)
  FrontDoorDescription ▶  KindedTool, KindDelegate      (allowed)
  CleanupRule        ──▶  KindDelegate                  (allowed — REV 2)
  KindDelegate       ──▶  Tool                          FORBIDDEN (a rule is not a tool)
  ToolRegistry       ──▶  OperationSurface              (allowed)
  OperationSurface   ──▶  Tool, OperationRegistry       (allowed)
  tests              ──▶  OperationSurface, ToolRegistry(allowed)

  delegate Tool      ──▶  its front door                FORBIDDEN
  anything           ──▶  getInputSchema() internals    FORBIDDEN
                          (the Map is protocol output, not a data source)
  tests              ──▶  OperationRegistry directly    FORBIDDEN
  ToolRegistry       ──▶  OperationRegistry directly    FORBIDDEN (goes via surface)
```

Two of those are mechanically checkable today with
`find_quality_issue(kind=forbidden_edge, from=<delegate package>, forbidden=<front-door package>)`,
and the Map-walking edge with `find_quality_issue(kind=message_chains)` — see §6.

---

## 3. The seams, the patterns, and the smells they close

### Seam A — `Tool.publishedKinds()` : the object answers for itself

`publishedKindsOf(Tool)` is a static reader whose only parameter is a `Tool`. That is textbook
**Feature Envy**: a method that wants to live on the class it interrogates. It reaches through
`schema.get("properties").get("kind").get("enum")` — a literal **Message Chain** into a nested
`Map`, four levels deep, and *four copies of it exist* because a chain is easy to retype and
impossible to reuse.

**Refactoring: Move Method + Hide Delegate** (Fowler, *Refactoring* 2nd ed. — Move Function
§8.1, Hide Delegate §7.7).
**Intent:** put the behaviour on the object that owns the data, and give callers a method on
that object instead of a path through its internals.
**Consequences you pay:** `Tool` grows. Every implementor inherits a member it may not care
about — mitigated by making it a `default` returning `List.of()`, so the ~32 non-front-door
tools compile untouched and mean exactly what they say: *I publish no kinds*.

**Why this is the root fix, not a fourth guard:** *Hide Delegate for part of a protocol is
exactly what "incomplete delegation" means.* The front door already hid `execute`. It did not
hide the rest, so callers went around it. S1 is not "duplication" as an accident; it is the
predictable shape of the gap.

### Seam B — `KindedTool.delegates()` : the routing table is the single source

The front door declares its delegates once, as a map from kind name to delegate.
`publishedKinds()` is `delegates().keySet()`. There is no second declaration to keep in step.

**Pattern: Strategy** (GoF, *Design Patterns* p.315).
**Intent:** define a family of interchangeable algorithms, encapsulate each, make them
interchangeable.
**Consequences you pay:** an extra indirection on every call, and the Context must expose
*something* about the strategies it holds — which is exactly the obligation the current code
declines.

The correction is therefore not "adopt Strategy" — Strategy is already here. It is **widen the
Strategy interface to the full set of obligations the Context publishes on its behalf.** A
Strategy interface narrower than the Context's published surface guarantees the Context will
hand-maintain the difference. That is the general law this whole design instantiates.

### Seam C — `AbstractFrontDoor` : one description algorithm, delegate-supplied parts

The published description keeps its human voice and loses its hand-maintained facts:

```
  preamble()            hand-written · the tool's own voice · WHY it exists ·
                        cross-cutting notes  ·  MUST NOT NAME A KIND        (rule, §6)
  usageLine(...)        generated  ·  USAGE: extract(kind="<a|b|c|…>", …)
  bullets(...)          generated frame, delegate-supplied content:
                          "- <kind>  — <delegate.kindSummary()>"
  footer()              hand-written · postconditions, undo contract, "requires load_project"
```

The explanatory content the constraint protects — *what each kind refuses and why* — is not
lost and is not generated. It is **moved to the delegate that implements the refusal**, as
`kindSummary()`. That is the point: the sentence describing a refusal now lives in the same
file as the code performing it, so the change that alters the behaviour has the sentence in
its own diff.

**Pattern (REVISION 2): Strategy by composition, NOT Template Method.**
Revision 1 named Template Method (GoF p.325) and made `AbstractFrontDoor` a shared
superclass. That is unbuildable: `apply_cleanup` extends `AbstractApplyingRefactoringTool`
and `data` extends `AbstractRefactoringTool`, and Java has one superclass. Inserting the base
above theirs would apply it to every tool underneath them rather than to the eight doors.

So the algorithm is an object each door **holds and forwards to**: `FrontDoorDescription`.
**Intent:** one implementation of the assembly, parameterised by the door it is describing.
**Consequences you pay:** the `final` guarantee is gone — a door *can* still write its own
`getDescription()`, where a superclass could have forbidden it. That is bought back by a
gate rather than by the compiler: per door, the published description must equal what the
shared instance assembles for it. One forwarding pair per door, eight in total, is the other
cost. **We are willing to pay both**, because the alternative is not a better base class, it
is two doors exempted from the design — and an exemption is how the next hand-maintained
copy gets written.

**Smell closed:** **Divergent Change** (the description changed for reasons unrelated to the
tool) and **Incomplete Delegation**.

**Rejected alternatives, and why:**

- **A `Kind` value object** (name + summary + params + flags). Rejected by the standing bias:
  it holds no state the delegate does not already hold and makes no decision the delegate does
  not already make. It would be a helper on the side. The delegate *is* the kind.
- **Composite** (GoF p.163). Rejected: delegates are not front doors, there is no recursive
  tree, and forcing the uniform interface would invent a node type to satisfy the pattern
  rather than the design.

### Seam D — `OperationSurface` : one publishing site, and the tests use it

A named public collaborator with one job: given a `Tool`, project it into the operation model.
`ToolRegistry.register` calls it. Tests call the *same* method — they no longer own a private
copy, and they no longer own a hand-written expectation of what it produces.

**Smell closed:** **Shotgun Surgery**. Adding a kind touched a front door, its description, its
schema, a test table, and a second test table with an arithmetic claim in a comment. After this
seam it touches one `delegates()` map.

---

## 4. Ruling on the two store entries consulted

### Entry 1 — the crude prose gate (Sprint 28d C8, verdict `worked`)

> *"guard the prose axis separately from the enum and parameter axes … The match is
> deliberately crude: it cannot judge whether prose is good, only whether the kind is
> mentioned, which is exactly the failure that occurred."*

**Ruling: SUBSUME the lesson, USE the assertion as the migration's parity harness, then RETIRE
the assertion — and replace it with a narrower successor that catches what generation cannot.**

1. **The lesson is permanently right and generalizes.** "A front door owns the only
   documentation its clients can reach" is why this whole design exists. It is not being
   retired; it is being promoted from a test to a structure.

2. **The assertion becomes a tautology, and a tautological test is worse than no test.** Once
   bullets are a projection of `delegates()`, "every published kind's name appears in
   `getDescription()`" cannot fail. It will read as coverage on the prose axis while covering
   nothing. Delete it at the end of migration — but **not before**: during M5 it is exactly
   the right parity check that the generated description did not drop a kind the hand-written
   one had. It earns its keep on the way out.

3. **It was beaten by a failure it was never shaped for, and the successor must be shaped for
   that one.** Entry 1 was aimed at *omission* — a kind described nowhere. The new failures are
   different: a stale `USAGE:` line **above** a complete bullet list (satisfies the predicate
   exactly), and a description **claiming a refusal the tool does not perform** (a false
   statement, not a missing one). Generation kills omission outright. It does not kill
   falsehood.

   **Successor rule — the preamble containment rule:** *the hand-written regions of a front
   door's description (`preamble()`, `footer()`) must not contain any published kind name.*
   Kind names appear only in generated regions. This is a lexical check over `preamble()`
   against `publishedKinds()` — cheap, total, and it kills the stale-`USAGE:`-line class at the
   root, because the `USAGE:` line is no longer somewhere a human can write.

   It does **not** kill the false-refusal class. See §7.

### Entry 2 — 85 tools in the README, 40 in the code (verdict `failed_avoid`)

> *"A stale test fails; a stale document reads exactly like a fresh one."* Cure recorded:
> derive the list from the registration site.

**Ruling: this design IS that cure, one level down — and the same seam must be extended one
level up in the same migration.** `OperationSurface` is the "registration site" Entry 2 names.
Once it exists and is public, the README tool table and the in-app help are reconciled against
it by an equality assertion (M9). Entry 2's failure mode is the reason the reconciliation must
be **equality**, never containment: a document that lists a subset reads exactly like one that
lists all of it.

Entry 2 also settles S4's shape. A hand-written list in a test is one of two things, and the
design admits only these two:

> **Either it is an oracle compared for EQUALITY against the derived set — or it is deleted.**

A subset assertion, a row count nobody asserts, and arithmetic in a comment are all the same
defect: a document that reads exactly like a fresh one.

---

## 5. Where each deliverable lands

| Deliverable | Home | Kind of code |
|---|---|---|
| `publishedKinds()`, `kindSummary()` | `Tool` (same file/package as today) | `default` methods — zero compile impact on ~32 implementors |
| `KindDelegate` | `org.jawata.mcp.tools`, beside `Tool` | interface — `kindName()`, `kindSummary()`, `parameterSchema()`, `isStructural()`. Implemented by delegate `Tool`s, by `PullUpTool`/`PushDownTool`, and by each `CleanupRule` |
| `KindedTool` | `org.jawata.mcp.tools`, beside `Tool` | interface — `discriminator()`, `delegates() : Map<String, KindDelegate>`, `publishedKinds()` = `delegates().keySet()`, and a `default getDescription()` forwarding to the shared `FrontDoorDescription` |
| `FrontDoorDescription` | `org.jawata.mcp.tools`, beside `KindedTool` | ONE instance, HELD not inherited — `describe(door)` and `schema(door)`. **Revision 2 replaced `AbstractFrontDoor` with this**; the row below it in earlier drafts still named the abstract class and is deleted |
| `preamble()`, `footer()`, `envelope()` | each of the 8 front doors | the surviving hand-written prose |
| `kindSummary()` overrides | the delegates that carry refusal nuance | the surviving explanatory content, next to the behaviour |
| `OperationSurface.publish(Tool)` | package of `ToolRegistry` | the single publishing site, public |
| Forbidden-edge + containment checks | the honesty test's file, rewritten | property tests over the registry |

### What must NOT be touched

- **The MCP protocol surface.** `getName`/`getDescription`/`getInputSchema` keep their
  signatures, their types, and their meaning. Description stays free text; schema stays a
  nested `Map`. Nothing in this design changes a byte of what a client receives except that the
  description's kind list becomes complete and correct.
- **Delegate `execute()` bodies.** No behaviour moves. This is a documentation-and-derivation
  seam only.
- **The ~32 non-front-door tools.** They gain two `default` methods they never override.
- **`OperationRegistry`'s own API.** It gains a single caller, not a redesign (see §7).

---

## 6. The end-state test surface

**What becomes environment-independent** — pure in-process property tests over the registry,
with no MCP client, no protocol round-trip, no filesystem:

- *Completeness* (replaces the enum axis): for every registered `KindedTool`,
  `publishedKinds()` equals `delegates().keySet()`. **This is a tautology by construction and
  should therefore not be written.** Its absence is the deliverable.
- *Containment* (replaces the prose axis): for every front door, `preamble()` and `footer()`
  contain no published kind name. One test, all eight doors, data-driven from the registry.
- *Reconciliation* (replaces S4's hand tables): every table-driven test asserts
  `assertEquals(derivedSet, tableRows.keySet())` — equality, both directions — so a missing row
  and a stale row both fail.
  **(REV 2) The "7 + 5 = 12" comment is a DIFFERENT case and revision 1 rendered it wrong.**
  It said `assertEquals(12, structural.size() + mechanical.size())` "with both operands read
  from the registry". Measured: `OperationRegistry.classify` populates `mechanical` and
  `structural` from two independent `if`s on the same call, so they are **not disjoint and
  not a partition** — a refactoring door's kinds land in both, their sizes double-count a
  registry-wide population, and that population is not 12. Worse, the split the comment
  counts is *name-addressable vs not*, which is a property of each row's TARGET (a statement
  range, a local variable) and is not registered anywhere.
  So the honest form is: the row tables are reconciled against `delegates().keySet()` for the
  three doors they cover, and the 7/5 split is asserted as **each table's size plus their
  sum**, with the mutation proof. That IS a hand-written number, and it is admissible only
  because no derived operand for it exists until the row-to-tool table exists in code — which
  is a separate deliverable. Anywhere a derived set DOES exist, equality or deletion remain
  the only two options.
- *No re-derivation*: `find_duplicate_code` reports zero clones of the schema-walking shape;
  `find_quality_issue(kind=message_chains)` reports zero chains into `getInputSchema()`.
- *Direction*: `find_quality_issue(kind=forbidden_edge, from=<delegates>, forbidden=<front doors>)`
  is empty.

**What the boundary owns** — one test class against `FrontDoorDescription` (not the deleted
`AbstractFrontDoor`) with a fake door and two fake delegates covers the assembly algorithm
for every real door: slot order, separator handling, **the empty-delegates case** — which is
`data`'s literal state until Stage 5 gives it kinds — a delegate whose `kindSummary()` is
multi-line, idempotence of `getDescription()`, and **a door whose discriminator is not
`kind`**, since `hierarchy` uses `direction` and `refactoring` uses `action`.

This test class is a DELIVERABLE of the seam stage, not an implication of it. A stage whose
exit waives the per-row test convention has to name the tests it does owe, or it owes none.

**What only reality can verify** — stated plainly, because a design that hides this has not
finished:

- Whether the prose is *useful to an LLM client*. Only a live client session or a human read
  judges that. No assertion approximates it.
- Whether a claim in `preamble()` about behaviour is **true**. See §7.
- Whether the *set of kinds is right* — a kind that ought to exist and does not is invisible to
  every check here, because every check is a projection of what exists.

---

## 7. What this design does NOT cover

**1. False claims — the largest surviving hole.** Generation kills omission; it does not kill
mendacity. S2 included *"claimed a refusal the tool does not perform — it said a shape is
refused; the tool accepts it with an extra parameter."* After this design that sentence lives
in the delegate's `kindSummary()` instead of the front door's blob. That is a real improvement
— the lie and the code are now in the same file, so a reviewer of the change sees both — but it
is a **proximity** improvement, not a mechanism. Catching it still requires a behavioural test
asserting the refusal, or a fresh-context auditor reading claim against code. Note that Entry
1's own history says exactly this: the C8 gap was *"found by a fresh-context auditor, not by
the author and not by the architect's watch."*

**2. The flat-schema ambiguity the protocol forces.** `getInputSchema()` must publish one flat
property set for all kinds, so the union will permit parameter combinations no single kind
accepts (`kind=variable` with `superclassName`). Only runtime validation inside the front door
rejects those, and no static check sees the gap. This is a protocol constraint, not a design
choice — recorded so it is not mistaken for an oversight.

**3. `OperationRegistry` is a process-wide singleton with no reset seam.** This design gives it
exactly one writer, which is a strict improvement and enough for the problem at hand. It does
not address test-order coupling or the hidden global itself. **Flagged, deliberately out of
scope** — folding a singleton redesign in here would turn one coherent target into two.

**4. `kindSummary()` accuracy about its own delegate** — same class as (1), now localized.

**5. Whether the *preamble* is worth reading at all.** Nothing here prevents a preamble decaying
into three words. Human judgement only.

---

## 8. Migration path — ordered, parity-gated, reversible

Every step: `refactoring(action=plan)` → `apply_plan` where a plan kind exists, otherwise the
named single refactoring with its `undoChangeId`. **Common gate for every step:**
`compile_workspace` 0 errors/0 new warnings + the full test suite green. Steps add a
*discriminating* gate on top — one check that would fail if the step did nothing.

| # | Step | Refactoring kind + target | Discriminating gate | Revert |
|---|---|---|---|---|
**M0 IS NOT A MIGRATION STEP — it is a PREREQUISITE of the stage, and the move matters.**
As a row in this table it stood as a written exception to the stop rule below it, in the
stop rule's own first row. As a prerequisite it holds nothing open, and its output gets the
durable home it needs.

> **M0 — measure the delegate shapes, before any door is converted.** Per door: a map, typed
> fields, or none, and the declared type of each. `inspect(kind=type_members)` per door plus
> `find_references(kind=implementations)` over `AbstractRefactoringTool` and
> `AbstractApplyingRefactoringTool`. **The output is CHECKED IN as
> `ARCHITECTURE-front-door-census.md`**, each row carrying the call that produced it.
>
> It has no mutation gate and does not pretend to: it changes no code. What replaces the
> gate is a mechanism rather than a memory — **every later step's per-door list is read from
> that file, and a step whose list disagrees with it is refused.** An earlier draft said the
> same sentence with no file behind it, which left the refusal to a reviewer's recollection —
> exactly what a fresh-context reviewer does not have.
| **M1** | Give `Tool` the reader. Move `ToolRegistry.publishedKindsOf(Tool)` onto its own parameter's type; body unchanged (still walks the schema Map). **AND a second reader exists that this step must own:** `RefactorToPatternTool.publishedKinds()` is `public static` with four live callers (`CureLookup`, `CureTier`, and two tests) and returns its own `KINDS` literal. A `static` method cannot coexist with an inherited instance method of the same erasure, so M1 breaks the build in that class unless it retires or renames that accessor in the same step. | `change_method_signature(visibility=public)` then `move_method(symbol="…ToolRegistry#publishedKindsOf", target="tool")` | `find_references(symbol="…Tool#publishedKinds")` returns the production call site; `ToolRegistry` no longer declares the method. | `undo_refactoring` |
| | **MEASURED, not a risk: it IS `static`** — `ToolRegistry.java:163`, package-private, so `move_method` will refuse and the two-hop route is the plan of record, not a contingency: make it an instance method first, or place the `default` on `Tool` by `extract(kind=interface)` and `inline` the old body. **And its body cannot move unchanged** — it reads `ToolRegistry`'s private `LIFECYCLE_FRONT_DOOR` and `REFACTORING_FRONT_DOORS`, which is the registry's own POLICY about which tools' kinds are operations. That filter does NOT travel to `Tool`: after M10 the question is `tool instanceof KindedTool`, so M1 moves the reader and M10 replaces the filter. Until M10 lands, `Tool.publishedKinds()` returns the door's own kinds and `OperationSurface` applies the filter — never the other way round, because dropping it put hundreds of non-operations into the operation namespace once already (`ToolRegistry.java:164`). | | | |
| **M2** | Kill the copies. **(REV 2) The population is ENUMERATED AT THE STEP, never carried as a count** — revision 1 said "three test copies plus a fourth variant" and two independent reviews returned different sets. Run `search_symbols(query="publishedKinds*", kind=Method)`, and treat every hit that walks the schema itself as a copy; a hit that CALLS the production reader is not one. Retarget each to `tool.publishedKinds()`. | `change_method_signature(retargetCallsTo=…)` per copy, then delete on evidence | Per named copy, `find_references` returns ZERO after retargeting — a per-copy check that cannot be satisfied by the population being smaller than assumed. (Revision 1's `find_duplicate_code` gate is DROPPED: the copies are not token-identical, so it reports zero both before and after, which is a gate that cannot fail.) | per-copy undo |
| **M3a** | **(REV 2)** Introduce `KindDelegate` — the four-method role. Every existing delegate `Tool` implements it over what it already holds; `PullUpTool`/`PushDownTool` implement it directly; **each `CleanupRule` implements it**, which is where `apply_cleanup`'s kinds gain an owner. | `extract(kind=interface)` on one delegate, then implement per class | `find_references(symbol="…KindDelegate")` names an implementor in each of the SIX doors this stage converts (the other three adopt in their own lanes; see SCOPE). Zero for either is the step not done. | per-class undo |
| **M3b** | Introduce `KindedTool` with `discriminator()` + `delegates() : Map<String, KindDelegate>`; each door declares its map. `publishedKinds()` = `delegates().keySet()`. | `extract(kind=interface)`, then `move_in_hierarchy(direction=up)` per door | For each of the SIX doors this stage converts, the **old** Map-walking result equals the **new** `delegates().keySet()` — a temporary test, deleted at M6. Plus: `hierarchy.discriminator()` returns `"direction"`, not `"kind"`. | per-door undo |
| **M4** | **(REV 2 — composition, not a superclass)** Introduce `FrontDoorDescription` as a held collaborator; wire `extract`, `inline`, `move` first. Generated regions initially reproduce the *current* description text. NO reparenting: `apply_cleanup` and `data` keep their existing bases. | `extract(kind=class)` for the algorithm, then one forwarding pair per door | Golden-file: the assembled description for a door with a *complete* hand list is byte-identical to today's. AND `inspect(kind=type_hierarchy)` shows no door changed superclass. | `undo_plan` |
| **M5** | Per door (SIX reversible steps here; the other three inside their own lanes), delete the hand-written `USAGE:` line and bullets, keep `preamble()`/`footer()`; delegates gain `kindSummary()` carrying the moved refusal prose. | `extract(kind=method)` for `preamble`/`footer`; `move` the bullet text to the delegate | **Two checks, because the first cannot fail on a no-op.** (a) PARITY HARNESS — Entry 1's crude gate: every published kind name must still appear in the assembled description, which fails loudly if a bullet was dropped rather than moved. (b) **THE DISCRIMINATOR (REV 2)** — no front-door source file retains a literal `USAGE:` line, and each door's published description equals what the shared `FrontDoorDescription` assembles for it. Check (a) passes unchanged if M5 does nothing, so on its own it is a harness and not a gate; (b) is what fails. A human still reads the first door's before/after. | per-door undo |
| **M6** | Retire the tautologies. Delete the enum axis and parameter axis from `DeclaredShapeHonestyTest`; delete Entry 1's prose assertion; add the **preamble containment rule**. Delete M3's temporary parity test. | hand-authored test edit | Mutation check: hand-insert a kind name into one `preamble()` — containment must fail. Then revert the mutation. | git |
| **M7** | Reconcile the hand tables. Both test tables assert **equality** against the registry-derived set; the "7 + 5 = 12" comment becomes an assertion over registry-read operands. | hand-authored test edit | Mutation check: delete one row — the test must fail (today it does not). Restore. | git |
| **M8** | Extract `OperationSurface.publish(Tool)` from `ToolRegistry.register`; point the tests at it. | `extract(kind=method)` then `move_method` to the new type | `find_references(symbol="…OperationRegistry")` names `OperationSurface` as the sole caller; **and the direction rule is checked per delegate, NOT by `forbidden_edge`** — that detector takes package prefixes, and delegates share a package with their doors (`InlineTool` and `InlineMethodTool` are both in `…mcp/tools/`), so the query returns every intra-package reference and an empty result would mean the query was mis-scoped rather than the rule holding. Instead: `find_references` per delegate type, asserting zero references to any front-door type. | `undo_refactoring` |
| **M9** | Entry 2's cure, one level up: a test reconciling the README tool table and the in-app help against `OperationSurface` by equality. **Name the file and section at the step** — the README carries a prose count (`README.md:29`, "43 MCP tools") against a live `toolCount` of 46, and a per-tool table may have to be introduced before it can be reconciled. | new test | Mutation check: remove one tool row from the README — the test must fail. Restore. | git |
| **M10** | **(REV 3) Retire `ToolRegistry.REFACTORING_FRONT_DOORS` — AND IT CANNOT RUN IN THE SEAM STAGE.** A review measured why: the seam stage converts six doors, so `instanceof KindedTool` yields six there, and swapping an eight-name allowlist for it would DROP `hierarchy`'s registered `up`/`down` operations. The predicate equals the allowlist only once `data`, `hierarchy` and `change_method_signature` have converted — i.e. after Stages 5, 7 and 4. **M10 therefore belongs in Stage 9**, and the seam stage must not carry "the constant no longer exists" as an exit clause. | `find_references(symbol="…ToolRegistry#REFACTORING_FRONT_DOORS")`, retarget each, then delete | The constant no longer exists, AND `OperationRegistry.all()` before equals after — the second half is what catches the premature run, and it is the one gate in this migration that already did. | `undo_refactoring` |
| **M10b** | **(REV 3) What CANNOT be derived, and stays written down on `OperationSurface`.** Two things in `publishedKindsOf` are policy, not shape: (a) `LIFECYCLE_FRONT_DOOR` — now redundant, because `refactoring` is deliberately not a `KindedTool` (see SCOPE), so the structural test finally means what the name-exclusion meant; delete it and say so. (b) the reader iterates `List.of("kind", "direction")` and **deliberately does not read `action`** — that survives as a written policy on `OperationSurface`, because "which discriminators name operations" is a product decision no `instanceof` carries. | hand-authored, on the type M8 creates | mutation: add `"action"` to the read list — `OperationRegistry.ambiguous()` must become non-empty, since the pattern operations are published by two tools. Then revert. That is the boot failure `ToolRegistry:119-133` records, made into a test instead of a comment. | git |

**Order matters in exactly two places.** M1 must precede M2 (the call shape must exist before
the copies can be retargeted). M5 must precede M6 (Entry 1's gate is the harness for M5 and
cannot be deleted until M5 is done for all eight doors). Everything else can be resequenced;
M4/M5 are per-door and can land one door per checkpoint.

**Stop rule.** If any step's discriminating gate cannot be made to fail on a deliberate
mutation, the step has not been verified — do not proceed to the next one. A gate that cannot
see the defect it exists for is the very failure this document is about.

---

## 9. The law this design instantiates

> **A delegating object must publish everything about its delegates that its clients can see.
> Every fact it declines to forward becomes a hand-maintained copy somewhere else — and the
> copy will be guarded rather than derived, because a guard is what you build when the object
> will not answer.**

S1 was the copy. S2 was the copy going stale. S3 was the guard, correctly built for the failure
it had seen and blind to the next one. S4 was the same copy again, in the tests, guarding
itself. One defect, four faces, one forward missing.
