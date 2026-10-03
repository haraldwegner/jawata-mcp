# ARCHITECTURE — the principal and the language verticals

Architect seat, design mode. Sprint 28g scope. Written 2026-10-03 against jawata-mcp
at `bcd5261` and jawata-studio at the same date. Every claim below names the file and
symbol it was read from.

**Pattern consulted** — `experience(kind=nominate)`, no lane filter (8 candidates, 4 of
type `reference` from the catalogue). **Selected: Context Object**
(`catalogue:java-design-patterns/context-object/README.md`) — "protocol-specific state
passed as parameters couples every intermediate layer to a protocol it does not use",
which is exactly the JDT-types-in-signatures defect found in `IJdtService` below.
**Declined:** Template Method (the verticals do not share a fixed sequence — coverage of
the shared catalogue varies row by row, not in steps of one algorithm) and Interpreter
(no grammar here).

> ### STRUCK PREMISE — do not reinstate
>
> This document was first written against a ruling that said *"the Java overlay (62 of
> Fowler's 66 refactorings, 22 of 24 smells, the pattern catalogue, the cure table) is
> JAVA-ONLY and will NOT be re-implemented per language. Other languages get the
> principal's reach, not that depth."*
>
> **Harald struck that premise himself on 2026-10-03**, verbatim: *"C++ is object
> oriented. Many patterns and refactorings can be applied here as well. There might be
> quite an overlap."* He is right — Fowler's catalogue was not written about Java, and the
> GoF patterns are about object orientation rather than a language. Extract Method, Move
> Method, Inline, Pull Up, Push Down, Replace Conditional with Polymorphism, Replace Type
> Code with Subclasses and most of the 24 smells are C++ refactorings too; the iluwatar
> catalogue merely carries Java *sources* for intents that are language-neutral.
>
> The layering is therefore **three** parts, not two: the **catalogue is shared** (the 66
> refactoring rows, the 24 smells, the patterns, and the cure table that binds a finding
> to an operation — one table for all languages, not one per language), the **engine is
> per language** (detecting a smell, performing an operation, verifying the result
> compiles: JDT today, clang's own refactoring/tooling infrastructure for C++,
> rust-analyzer or an LSP head for others), and **coverage is per language** (which rows
> of the shared catalogue this engine can DETECT and which it can PERFORM — that, rather
> than an ad-hoc operation list, is the honest shape of a provider). Section 2's
> measurement, section 4-B, the picture and the migration path are written on that basis.
> A later reader who reintroduces "the catalogue is Java's" is reinstating a premise its
> author removed.
>
> ### AMENDED the same day — the catalogue is MULTI-LANGUAGE, not merely language-neutral
>
> Harald, 2026-10-03: *"There might be additional ones for C++, so these would be in the
> catalogue as well and each vertical says what's applicable."*
>
> So the table is not "Fowler's 66 and nothing else". It is the shared table of **rows**,
> and a row may come from anywhere: Fowler's 66, the C++ refactorings nobody else needed to
> name (the Rule of Five, replace a raw pointer with a smart pointer, move a definition
> between header and implementation, pimpl, replace a macro with `constexpr`), and whatever
> Rust turns out to need. They sit beside each other in ONE table.
>
> **The mechanism does not change, which is the sign it was right.** Every vertical declares
> its coverage of the WHOLE table, and `NOT_APPLICABLE` does the work in both directions:
> Java answers `NOT_APPLICABLE` to the Rule of Five exactly as C++ answers it to Encapsulate
> Record, each with the note that says why. The question *"is this row Java's or C++'s"*
> disappears — **no row belongs to a language; a language declares what it can do with each
> row.**
>
> Two consequences, or the one table quietly becomes several:
>
> - **The row key carries its ORIGIN.** Fowler's 1–66 are an external fact and keep their
>   numbers; a C++ row must NOT be number 67, because that would make our own additions read
>   as Fowler's. Keys are namespaced — `fowler:23`, `cpp:rule-of-five`, `rust:…` — so the
>   external catalogue stays uneditable while the table grows.
> - **The integrity test gains a clause.** Pinning "66 rows, 1..66, four declined" is right
>   for the Fowler BLOCK and must stay scoped to it; beside it, every non-Fowler row carries
>   its own origin, and no row anywhere names a language as its owner.
>
> And the wording below is corrected with it: "the catalogue is language-neutral" was not
> quite the claim. **Neutrality is a property of the TABLE, not of each row** — the rows come
> from several traditions, and what makes the table shared is that it belongs to no engine.

---

## 1. The target, in one sentence

The principal is its own compile-fenced module in front of the language engines, holding
plan / brief / gate / journal over three shared things — a **language-neutral catalogue**
(Fowler's rows, the smells, the patterns, the cures), a **coverage declaration** each
provider makes against that catalogue, and the **capability registry** that already exists
(`OperationRegistry`) — with the knowledge store extracted beside the principal rather
than inside the Java engine, and seats plugged in through one `SeatDefinition` whose
transport is data.

## 2. What is already true, and what was measured

Four of the brief's premises check out; three were false, and the largest was struck by its
own author (above). The measurements that decide the design:

**The catalogue data is ALREADY language-neutral — and it is in the wrong place.**
`org.jawata.mcp.tests/src/org/jawata/mcp/tools/PerformedRefactoringCountTest.java` holds
the 66-row table as `record Row(int number, String name, String operation)`. The first two
fields are Fowler's own and the file says so explicitly — *"an EXTERNAL, HISTORICAL fact …
No object here owns it and nothing here can change it"* — and **not one of the 66 row names
mentions Java**: "Extract Function", "Move Field", "Pull Up Method", "Replace Conditional
with Polymorphism". The same exemption is made for the 24 smells by
`EverySmellIsDetectedOrDeclinedTest`. So the catalogue needs no translation; it needs
**promoting out of a test into production**, because a coverage declaration cannot be read
out of a test class. That cost nobody had named, and it is step 3 below.

**What is Java-bound is the third field and the join, exactly as predicted.** `Row` carries
**one global `operation` string**, and `theHeadlineCountIsRecomputed` joins it by
`shipped.contains(row.operation())` against a set built in `shippedOperations()` from
`RefactoringDoors.all(...)` + `RefactoringDoors.standalone(...)` — one engine's published
doors, with the test itself importing `org.jawata.core.IJdtService` (line 3) to construct
them. There is no room in that shape for a second answer: a row can say what performs it in
one engine only. **That is the join that must become per-provider**, and it is a change to
the row's third field, not to the catalogue.

**The cure table is neutral in its keys and Java-sourced only in its addresses.**
`CureCatalog.Cure(recipe, operation, discriminator, needs)` — `recipe` is a plan kind
(`compose_method`), `operation` is a lookup key (`design:<slug>`) whose address is read off
the row by `org.jawata.mcp.knowledge.CatalogueAddresses`, deliberately *not* stored as an
address. The intent is language-neutral; only the rendition the address resolves to
(`catalogue:java-design-patterns/<slug>/README.md`) is Java. Keys and discriminators stay
shared; a second language adds a rendition, not a table.

**FALSE — "the cure table, the routing guard and the enforcement rules all name operations
by string."** `OperationRegistry`
(`org.jawata.mcp/src/org/jawata/mcp/refactoring/OperationRegistry.java`, 89 references) is
already the capability registry, populated at tool registration via
`OperationSurface.publish(tool)` from `ToolRegistry.register` (line 170). It answers
`all()`, `has()`, `toolFor()`, `ambiguous()`, `invocationOf()`, `isMechanical()`,
`isStructural()`, `isWired()`. The cure table (`CureCatalog` 980/989/1011, `CureLookup` 99,
`CureTier` 106/128), the architect gate (`ArchitectGate` 34/37/50/108) and the coverage
advisory (`MechanicalChangeJournal` 21/35) all resolve **through it**, and its javadoc
records the brief's predicted failure as already suffered and fixed — two `Set.of(...)`
literals of tool names, in two other packages, went silently dead when Stage 1 retired six
names. What *is* still string-literal is narrower and elsewhere:
`ToolRegistry.READ_ONLY_PREFIXES` (line 50), `READ_ONLY_NAMES` (52), `NO_STEER` (368) —
and that file documents its own weakness, *"a policy expressed as names fails open the day
a tool is renamed or added, because no reference-updating refactoring touches a string."*

**FALSE — that a non-Java project's memory needs a Java engine.** `H2ExperienceStore`
imports H2, Jackson, slf4j and the JDK, nothing else. In the 44-file `knowledge` package
the only JDT-touching class is `SymbolAnchorResolver`, which already takes an injected
`Supplier<IJdtService>` and at lines 48–51 **already returns `Optional.empty()` when the
service is null**. The store already runs anchorless with no provider at all.

**CORRECTED — `IJdtService` cannot be the provider port.** 24 members, with
`getJavaProject(): IJavaProject`, `getCompilationUnit(Path): ICompilationUnit`,
`getElementAtPosition(...): IJavaElement`, `getTypeAtPosition(...): IType`,
`findType(String): IType` and `resolveUniqueSourceType(String): IType` in the signatures.
JDT types in the interface. It is the Java vertical's **internal** service and must stay
that; generalising it would drag JDT across the fence.

**UNVERIFIED, so not asserted — "~78 operations."** Measured: 42 published tools
(`health_check` → `toolCount: 42`). I did not run the operation count, and under the
corrected layering the number matters less: a provider is sized by its *coverage of the
shared catalogue*, not by an operation list of its own.

**CONFIRMED.** `conductor.rs` renders seat artifacts (1,200 lines; 10 `EMBEDDED_SEATS`, 8
`COMMAND_MAP` commands, 2 utilities; its name is taken). `runner.rs` is a complete,
**unwired** seat-execution machine — `build/unwired-baseline.txt` is 77 lines of which
**74 are `src/runner.rs`**, the other three being `gateway.rs`, `manager_service.rs` and
`runtime_manager.rs`; `lib.rs` lines 12–34 carry `#[allow(dead_code)] mod runner;` with a
measured note, and its `invoke_handler` registers 38 commands, all `commands::*`, none
reaching `runner`. `gateway.rs` fronts one MCP service and routes to per-workspace JVM
residents over **HTTP** (`tiny_http` in, `reqwest::blocking` POST `/mcp` + SSE out),
refusing to guess on `Ambiguous`. `EmbedPackagePurityTest` is a real precedent and includes
a positive control proving the scanner can fail. **No ACP** dependency or code in any of
the three manifests.

## 3. The modules and the dependency direction

```
                   host renders (Claude Code / Cursor / Copilot / Codex)
                                      │  MCP over HTTP
                        ┌─────────────▼──────────────┐
                        │  studio gateway.rs         │  routes by filePath / projectKey
                        │  (optional supervisor)     │  refuses to guess
                        └─────────────┬──────────────┘
   ╔══════════════════════════════════▼══════════════════════════════════════╗
   ║  RESIDENT (one JVM per workspace)                                       ║
   ║                                                                         ║
   ║   ┌──────────────────────────┐        ┌───────────────────────────┐     ║
   ║   │  org.jawata.principal    │───────▶│  org.jawata.knowledge     │     ║
   ║   │  plan · brief · gate     │        │  store · lanes · recall   │     ║
   ║   │  journal · seats         │        │  (H2, language-neutral)   │     ║
   ║   └──────┬────────────┬──────┘        └─────────────┬─────────────┘     ║
   ║          │            │                             │                   ║
   ║          ▼            ▼                             ▼                   ║
   ║  ┌───────────────┐  ┌──────────────────────────────────────────────┐    ║
   ║  │ org.jawata.   │  │ org.jawata.spi                               │    ║
   ║  │ catalogue     │◀─│ LanguageProvider · Coverage · SymbolResolver │    ║
   ║  │ 66 rows       │  │ + OperationRegistry (what can be CALLED)     │    ║
   ║  │ 24 smells     │  └────▲──────────────▲──────────────▲───────────┘    ║
   ║  │ patterns      │       │ declares     │ declares     │ declares       ║
   ║  │ cures         │       │ COVERAGE     │ COVERAGE     │ COVERAGE       ║
   ║  │ SHARED,       │  ┌────┴───────┐ ┌────┴───────┐ ┌────┴───────┐        ║
   ║  │ no language   │  │ java       │ │ cpp        │ │ rust       │        ║
   ║  └───────────────┘  │ ENGINE     │ │ ENGINE     │ │ ENGINE     │        ║
   ║                     │ org.jawata │ │ (clang     │ │ (r-a / LSP │        ║
   ║                     │ .mcp       │ │  tooling)  │ │  head)     │        ║
   ║                     └─────┬──────┘ └────────────┘ └────────────┘        ║
   ║                           ▼                                             ║
   ║                   org.jawata.core ──▶ Eclipse JDT                       ║
   ╚═════════════════════════════════════════════════════════════════════════╝

   Arrows point from dependent to dependency. FOUR edges MUST NOT exist, and a
   purity test asserts each:
     principal ─✗→ org.jawata.core      principal ─✗→ org.jawata.mcp
     knowledge ─✗→ JDT                  catalogue ─✗→ any engine
   The last is the new one, and it is what makes the catalogue SHARED rather than
   Java's: the table may not know which engines exist. An engine depends on the
   catalogue to declare coverage of it; the catalogue never depends back.
```

The fence is enforced the way the embed package's already is: a purity test per fenced
bundle, each a copy of `EmbedPackagePurityTest`'s mechanism — read the compiled **class
files'** constant pool rather than the imports, because a constant pool names every type a
method body touches, which an import scan misses — and each carrying the same positive
control, since a check that cannot fail proves nothing.

The **Context Object** seam is `BriefContext`: one object carrying workspace, seat identity,
provider id and journal handle, so no intermediate layer takes a parameter typed by a
protocol it does not use. That is the smell it prevents, and it is the mistake
`IJdtService` made.

---

## 4. Answers

**A — where the principal lives: its own bundle in front of the engines, in Java,
compile-fenced from JDT.**
*What must be true, and is:* a purity mechanism that reads class files exists and has a
working control (`EmbedPackagePurityTest`); the store it needs has no JDT dependency; the
resident is already the one long-lived per-workspace process holding both the store and the
per-call choke.
*Why not studio's Rust:* studio is **optional** — a Claude-Code-only user running
jawata-mcp alone must still have a principal, and a Rust home puts a process boundary
between the journal and the store. Keep Rust as the **spawner** (D-next), not the home.
*Why not inside `org.jawata.mcp`:* no fence is available there. That bundle imports JDT
freely and its `tools` package alone is 148 files; and under the corrected layering it is
now *one engine among several*, so the principal must not live inside it for the same
reason the catalogue must not.

**B — the provider interface: a provider DECLARES ITS COVERAGE OF THE SHARED CATALOGUE. It
does not publish operations of its own.**

```java
public interface LanguageProvider {
    String   id();                                  // "java" | "cpp" | "rust"
    boolean  handles(Path file);
    Coverage coverage();                            // the declaration — see below
    List<Finding> detect(SmellKey smell, Scope scope);
    Result        perform(RowKey row, Target target, Args args);
}

/** Coverage of the SHARED catalogue, keyed by the catalogue's own external keys. */
public record Coverage(Map<RowKey, RowSupport>     refactorings,
                       Map<SmellKey, SmellSupport> smells) {}

public record RowSupport(Support detect, Support perform,
                         String invocation,         // how to CALL it in THIS engine
                         String note) {}            // required when PARTIAL / NOT_APPLICABLE

public enum Support { FULL, PARTIAL, NONE, NOT_APPLICABLE }
```

*How coverage is expressed.* Per catalogue **row key**, namespaced by ORIGIN —
`fowler:23` for the 66 external rows, `cpp:rule-of-five` and its siblings for rows a
language needed that Fowler never named, `rust:…` later. Fowler's numbers stay exactly as
they are and our own additions can never be mistaken for his; the table grows without the
external block being edited. Two
independent axes, **detect** and **perform**, because they genuinely come apart: an engine
may find Feature Envy and be unable to move the method. Four values, and `NOT_APPLICABLE`
is deliberately distinct from `NONE`: *"C++ has no checked exceptions, so Replace Error
Code with Exception is a differently shaped refactoring here"* is not the same claim as
*"clang could do this and we have not wired it yet"*. Collapsing the two into a boolean is
what would make this dishonest. A `PARTIAL` or `NOT_APPLICABLE` row must carry a `note`,
which is the only prose the shape demands.

*Where it is stored.* The declaration is **code in the provider's own bundle**, so it cannot
drift from what the provider actually registers — the same argument that put operation
publishing in `ToolRegistry.register` rather than in eight constructors, and the same
failure it prevents (a list in another package cannot know that a name it holds has stopped
existing). At registration the declaration is published into the **existing**
`OperationRegistry`, which keeps its job unchanged — "what can be called, and how do I
spell it" — and gains the row key as an index. The shared tables move to a new production
bundle `org.jawata.catalogue`; the cure table moves with them, and its `recipe` field stops
being a global plan kind and becomes a row key that each provider's coverage resolves.

*How a caller asks "can this language do row 23 here?"* —
`principal.coverage(file).perform(RowKey.of(23))`. The file resolves to its provider via
`handles(Path)`; that is the same longest-prefix shape `gateway.rs` already uses one level
up, so there is one routing idea in the system rather than two. The answer is a
`RowSupport`: `FULL` plus the `invocation` string to call, or `PARTIAL` /
`NOT_APPLICABLE` plus the note that says why. A finding then routes **finding → smell key
→ cure → row key → *this* provider's invocation**, instead of → one global string.

*What breaks today if this is skipped:* the 66-row join stays a `Set.contains` against one
engine, so a second engine's arrival either silently reports every row as unperformed or
forces a fork of the table — and the three string-literal policies in `ToolRegistry` remain
the only classification of a tool, failing open and silently on every rename.

*What genuinely does NOT transfer — briefly, so nobody reads this as "every language gets
everything".* Java-only semantics: checked exceptions (rows 46 and 47 change meaning where
there is no `throws` clause), nest-mate and package-private access (which decides Move
Method's legality), records (row 10, Encapsulate Record), sealed types (row 59's subclass
closure). C++ complications with no Java analogue: a declaration split across a header and
an implementation file, so every rename, move and signature change is a *two-file* edit
with an ABI consequence; templates, where "all references" is instantiation-dependent and
not decidable the way JDT decides it; manual ownership, which makes Replace Primitive with
Object and Change Reference to Value lifetime decisions rather than type changes. **These
make rows harder or differently shaped; they do not make the catalogue Java's** — which is
precisely why `Support` has four values and a note instead of a boolean.

*And it runs the other way too.* Those same C++ facts produce rows Fowler never had to name
— the Rule of Five, replacing a raw pointer with a smart pointer, moving a definition
between header and implementation, pimpl, replacing a macro with `constexpr`. They enter the
SAME table under a `cpp:` origin, and the Java vertical answers `NOT_APPLICABLE` to them
with its own note. A vertical is not a subset of Java's coverage; it is a column of the
shared table, and every column may be the reason a row exists.

**C — the knowledge store: extracted beside the principal, not inside an engine.** Content
is already language-neutral (lanes: code, experience, domain, rules) and the dependency is
already injected. Migration cost is **one interface and one adapter**: replace
`SymbolAnchorResolver`'s `Supplier<IJdtService>` with the SPI `SymbolResolver`
(`Optional<String> resolveUnique(String token)`), implemented by each engine. No behaviour
change — the no-provider path is written and reachable today. The 98 existing knowledge test
files are the parity gate and must pass unchanged.

**D — seats: one definition, transport as data, three stages.** The seam is `SeatDefinition`
(YAML frontmatter + stance body; today `seats/*.md` carries `name, model, effort, schedule,
tools, gates, ttl_secs, max_iterations, cost_budget_usd`) plus a new `SeatTransport` port.
The definition lives in the principal; `conductor.rs` keeps rendering it; `runner.rs`
becomes one implementation of the transport.
*Now (stance handoff, per-client command files)* — **must prove before next:** the loop
state survives a handoff, i.e. a brief issued, a gate run and a journal entry written, all
inside the resident, with the host's agent as the only executor.
*Next (principal spawns the seat, engine as its only road to the code)* — do **not** rewrite
`runner.rs`; wire it, and move the decision (which seat, what brief, which gates) into the
principal. **Must prove before later:** one seat end to end, with every file it changed
attributed to its run by the disk-sync join of E. That is P3's "one seat proven".
*Later (transport / vendor / model as data)* — add `transport: stance | cli | acp |
sampling` to the frontmatter; `model:` is already there (`architect.md` declares
`model: claude-sonnet-5`). ACP is greenfield, not an edit.

**E — enforcement without the host's hooks: the channel exists, and it is narrower than the
brief implies.** `DiskSyncGuard`
(`org.jawata.core/src/org/jawata/core/workspace/DiskSyncGuard.java`) is real, deliberately
JDT-free, `synchronized`, and wired at `ToolRegistry.callTool` via
`StrictDiskSync.syncBeforeCall()` **before every tool executes**, with the delta already
flowing into the watch engine (`syncDelta` → `watch(...)`). `ScanResult(changed, added,
deleted, newRoots, durationNanos, hashedCount)`; mtime/size fast path plus sha256 for files
inside a 2 s timestamp-granularity window.
*It can see:* a `.java` file created, changed or deleted under a **loaded project root**
between two tool calls, by evidence rather than trust, including a same-size edit hidden
inside one filesystem tick.
*It cannot see:* anything that is not `.java` — **and under the corrected layering that is
now a design gap rather than a footnote: a C++ or Rust engine gets no disk-sync channel at
all until the scan's extension set is asked of the providers instead of hard-coded**;
anything outside a loaded root; anything under `target` / `bin` / `build` /
`node_modules`; **who** wrote it, there being no author attribution, so "a file changed and
no seat of mine wrote it" is a **join** against the `filesModified` paths jawata itself
reports (already collected for `MechanicalChangeJournal`); anything at all while no tool
call happens, it being a per-call poll rather than a watcher — an edit and its revert
between two calls cancel and are never seen; and nothing before its own baseline, since
`known` is an in-memory map per resident and `newRoots` primes silently.
**So the free enforcement is after-the-fact attribution of source changes in the languages
the providers claim, not prevention** — and the claim must be scoped that way wherever the
principal makes it.

---

## 5. What the architecture makes testable, and where

**Environment-independent, run once anywhere.** The principal's whole suite — plan, brief,
gate verdict, journal — over a `FakeProvider` in a new `org.jawata.principal.tests`. No
JDT, no workspace, no host.

**The catalogue, testable on its own for the first time.** Once the 66 rows and 24 smells
live in `org.jawata.catalogue`, `CatalogueIntegrityTest` holds what
`PerformedRefactoringCountTest` holds today minus the join, **scoped to the `fowler:`
block**: 66 rows, numbered 1..66 with no holes, no duplicate number, exactly four declined
(rows 3, 6, 20, 66). Beside it, two clauses for a table that grows: every row outside that
block carries its own origin namespace, and **no row anywhere names a language as its
owner** — a row is applicable or not per vertical, which is the coverage declaration's job
and not the row's. It needs no engine on the classpath, which is itself the proof the
extraction worked.

**The fences.** `PrincipalPackagePurityTest`, `KnowledgePackagePurityTest` and
`CataloguePackagePurityTest` — the last asserting the catalogue references **no engine
bundle**, which is the new edge in the picture. Each copies `EmbedPackagePurityTest`
including its positive control.

**Owned by the boundary, tested per provider.** `LanguageProviderContractTest`, run against
every implementation: every coverage key resolves to a real catalogue row; every `PARTIAL`
or `NOT_APPLICABLE` row carries a note; every `FULL`-perform row's `invocation` is a string
the provider's own door actually accepts (the existing
`EveryRenderedInvocationIsAcceptedByItsDoorTest` is the pattern to copy). **The 62-of-66
headline becomes a per-provider figure computed by this contract test**, so Java keeps its
62 and a C++ provider reports whatever it actually covers — no table fork, no second
hand-written list, and the count still recomputed rather than asserted.

**The test a non-Java user can run** — `PrincipalRunsWithNoJavaProviderTest`: boot the
principal with an **empty** provider set and assert that `OperationRegistry.all()` is empty,
`isWired()` is false, **the catalogue still loads and still reports 66 rows**, every
coverage query answers `NONE` rather than throwing, a seat brief can still be issued and
journalled, `experience` record/recall still work, and the store's anchor path returns
empty rather than throwing. The last clause already holds (`SymbolAnchorResolver` 48–51), so
that part is a regression lock on existing behaviour; the catalogue clause is the new one
and it is the point — the shared table is readable with no engine at all.

**Only reality can verify.** The stance-handoff rendering in each host (Claude Code, Cursor,
Copilot, Codex) — named E2E smoke, not a suite, because what is under test is the host's
rendering of `conductor.rs`'s output. And the spawn path's process-group kill under a real
timeout.

---

## 6. Migration path — ordered, independently verifiable

**P1 — enforcement, recorded engine defects, guard switch**
1. Replace `ToolRegistry.READ_ONLY_PREFIXES` / `READ_ONLY_NAMES` / `NO_STEER` with a
   declaration asked of the tool, the way `rewritesSource` already asks the type.
   *Verify:* `ReadOnlyHintAnnotationTest` green, plus a new test that renaming a tool
   cannot silently change its read-only hint.
2. Promote the disk-sync delta from an advisory input to an **attributed record**: join
   `syncDelta` against the `filesModified` jawata itself reports (the join already exists
   for `MechanicalChangeJournal`) and publish "changed, unattributed" as a first-class
   resident fact that states its own scope. *Verify:* a hand-edit to a loaded `.java` file
   appears as unattributed on the next call; a jawata refactoring's own files do not.

**P2 — seat and workflow definitions, the PM seat, `/setup`, the UX seat, studio seats view**
3. **Create `org.jawata.catalogue` and MOVE the shared tables into it** — the 66 rows and
   the 24 smells out of the test tree into production, plus `CureCatalog`, `CureLookup`,
   `CureTier` and the pattern keys. The row's third field becomes a `RowKey` only; the
   global `operation` string is dropped. **Keys are namespaced by origin from the first
   commit** (`fowler:1` … `fowler:66`), so the table can take a `cpp:` or `rust:` row later
   without renumbering anything or retrofitting a scheme. *Verify:*
   `CataloguePackagePurityTest` (no engine reference) and `CatalogueIntegrityTest` both
   green with no engine on the classpath, the latter scoping its 66-row assertion to the
   `fowler:` block.
4. Create `org.jawata.spi` with `LanguageProvider`, `Coverage`, `RowSupport`, `Support` and
   `SymbolResolver`. Nothing implements it yet. *Verify:* it compiles, and references
   neither JDT nor `org.jawata.core`.
5. Create `org.jawata.principal` over `spi` + `catalogue` + the store only, with
   `PrincipalPackagePurityTest`. *Verify:* purity green **and** its positive control fails
   as designed.
6. Add the PM seat as `seats/pm.md` — project management only, no code-writing operation in
   `tools:`. *Verify:* `conductor.rs` renders it in all three clients; a test asserts the PM
   seat declares no operation the registry classifies as mechanical or structural.

**P3 — the hire path: seats spawned by jawata, ACP transport, one seat proven**
7. Extract `org.jawata.knowledge` as its own bundle; swap `SymbolAnchorResolver`'s
   `Supplier<IJdtService>` for the SPI `SymbolResolver`, the Java engine supplying the
   adapter. *Verify:* all 98 knowledge test files green unchanged, plus
   `PrincipalRunsWithNoJavaProviderTest`.
8. Make the Java vertical implement `LanguageProvider` and declare its coverage; retire
   `PerformedRefactoringCountTest`'s single-engine join in favour of the contract test.
   *Verify:* `OperationRegistry` snapshot identical before and after
   (`Snapshot` / `restore` exist for exactly this), **and** the recomputed Java figure is
   still 62 of 66 with the same four declines.
9. Wire `runner.rs` for one seat, spawned by the principal, the engine as its only road to
   the code. *Verify:* `build/unwired-gate.sh` — the 74-entry baseline is the
   before-number, and the drop is the measurement.

**P4 — Harald's UI review**
10. Studio's seats view reads the principal's journal over the gateway, and a coverage view
    renders each provider's `Coverage` against the shared catalogue. Nothing new to design;
    it renders what P2 and P3 made real.

### Must NOT be touched
`org.jawata.core` / `IJdtService` (the Java vertical's internal service, deliberately
JDT-typed — do not try to generalise it; the SPI goes beside it, not through it);
`org.jawata.mcp`'s **detection and transform code** — the engine stays exactly as it is and
is merely reclassified as one provider among several, which is a change to what *declares*
it, not to what it does; `conductor.rs` (it works, and its name is taken); `gateway.rs`'s
`Ambiguous` refusal (a shipped fix — the principal must not re-add guessing); `runner.rs`'s
`reap_process_tree` and `Ceilings` (wire them, do not rewrite them);
`EmbedPackagePurityTest` (it is the precedent — copy it, leave it alone); and the **66 row
names and 24 smell names themselves**, which are an external historical fact that no step
here may edit.
