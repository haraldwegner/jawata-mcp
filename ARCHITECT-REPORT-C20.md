# Architect watch — C20 (Sprint 28g, Stage 20, the carried findings)

**Mode:** watch. **Scope:** jawata-mcp `28dc1b6d^..f46de398` (31 commits) and jawata-studio
`832ed06^..7eb1cfb` (17 commits).
**Verdict:** **DESIGN FIX**, with one second implementation found and folded inside the
checkpoint, one bandage named below, and one unshown claim filed as REL44-30.

**The project's declaration:** neither repository has a `jawata-project.md`. So these are
assumed, not quoted:
- the engine is Java 21 built with Maven (`build/pom.xml`), plus the in-framework test runner;
- the studio is Rust, Tauri and Svelte;
- green is `run-suite.sh --full` for the engine and `cargo test --workspace` for the studio.

---

## What the stage did, against the architecture

S20 is the carried findings: defects homed to closed stages, worked to a red-before test each.
Its recurring subject is ONE shape: **an answer or a gate that reports success over something it
did not do.** Examples:
- an unread file reported as a clean one;
- an ambiguous import declined in silence;
- compile problems never read for a file nobody opened;
- a skip decided by the very parser the test checks;
- an assertion that runs only when the result happens to contain its subject.

Closing that shape is a design fix wherever the fix puts the decision in a place a test can
reach. It is a bandage only where it only adds a message.

| Change | Verdict | Why |
|---|---|---|
| `JfrParser.TopOfStack` + `TopOfStackTally` (C15-1) | **design fix** | The top-frame decision and the counting are objects a test drives with all three outcomes. The C20 audit found the counting had stayed in two untested loops; the tally closes that. |
| `sharePercent` / `rankedSamples` (C15-2) | **design fix** | The share is computed where the whole answer is, and its denominator is published by name. |
| `ExecutionSampleCount` (C15-3) | **design fix** | A test's skip is decided by a reader independent of the code under test. That is the shape catalogue entry `f46ed00f` names. |
| `DiffRenderer` Myers tier + `addedPlaceholders` (REL44-15, -25) | **design fix** | `DiffView` carries the placeholders as a component, so a truncated diff still reports them. |
| `OrganizeImportsTool` declined / unresolved (REL44-13, -27) | **design fix** | The answer now says what the operation did not do: `declinedAmbiguous`, `unresolved`, `unresolvedNotChecked`. All three follow one pattern. |
| `CompilationProblems` (REL44-26) | **design fix** | One place reads a unit's problems so that they are present. Its duplicate is folded (D-SIX, below). |
| `MemberScope.separatorOf` (REL44-23) | **design fix** | In the shared scope every narrowing caller uses, not in one tool. |
| `WatchEngine` first look, refusal logged (REL44-16, REL44-4) | **design fix** | "No record of this file" and "a check that did not happen" are each said, rather than read as "nothing new". |
| `build/pom.xml` error-stub guard + self-test (S16-1) | **BANDAGE — named in F1** | Correct at the right choke, and it proves its own matching. The cause is untouched. |
| test guards: embedder skip, open-condition census, path census, service disposal (REL44-21, -22, -29) | **design fix** | Each is closed as a CLASS, with the population counted and fixed equalling population (counts in the ledger rows). |
| studio `TrayIconPainter` / `IconSink`, `tray_menu_rows` (REL44-10, -11) | **design fix** | Rule 2 applied: the decision moved out of the Tauri-bound applier and builder into objects a test reaches. The C20 audit showed the pure helpers alone left both wirings untested. |
| studio fleet import wait (REL44-12) | **design fix**, with F2 | The wait reads the engine's own answer, and a contract test holds it to the engine's verbatim 4.4.5 text. |

---

## D4 — what the store was asked, and what it changed

Asked, in prose: which patterns or experiences apply to making a tool's answer carry what it did
not do, and to keeping a test's skip independent of the code it tests. Eight candidates came back
(query `cbba1178`). Five were selected; each is listed with what it made me do.

- **`f46ed00f`** — a test that reads state the same way the enforcement does cannot see a defect
  in that read. This is C15-3 exactly: the call-tree gate read its skip from the parser it gated.
  It is now read with the JDK's own reader, and the parser is held to the number.
- **`f93f975a`** — a test that fails in some runs has a bug; it is never "flaky". REL44-5 was
  treated that way: instrumented to say WHICH of three explanations a recurrence is, not retried
  into silence.
- **`6032079a`** — a needle must be words only the branch under test emits. C15-4's test asserts
  the zero-sample branch's own wording AND the absence of the other branch's, and its mutant
  (the branch made unreachable) fails all three clauses.
- **`e4f6ac4b`** — a text assertion over a source file is satisfied by its comments. The three
  new source guards parse the AST; none searches the file as text.
- **`9cc64ebd`** — a green suite says nothing about the paths no test performs. The live tray
  panel and the live three-resident restart (REL44-10, -11, -12) are kept as owed observations
  after a studio release, not counted as shown.

Not selected:
- Arrange/Act/Assert (`9be47a5c`): no test in this range interleaves its phases.
- The Tycho surefire entry (`e4f9b873`): this build is plain Maven.
- "Run the text" (`6aa616d1`): nothing here generates a script or a payload.

## D-SIX — a second implementation, found and folded

- **Is the package catalogued?** `org.jawata.mcp.tools.shared` is described (116 jobs).
- **`duplicate_check` on `CompilationProblems.parse`:** zero nominees.
- **Found by reading instead.** The check was blind here, so this finding rests on the code:
  - REL44-27 (06c8c703) made `OrganizeImportsTool.organize` configure exactly the parse
    REL44-26 (3c68d6d9) then created `CompilationProblems` for: bindings, recovery, forced problems.
  - One job, two copies, both written in this checkpoint.
- **Folded in `f46de398`:** organize reads through `CompilationProblems.parse`, and an
  unparseable unit is an explicit refusal. Behaviour is unchanged (20 of 20).
- **`RemoveDeadCodeRule`'s parse is NOT a duplicate:** it sets its own compiler options.

**And the fold's control found something.** With forced problem detection removed from the
shared parse, every test that reads problems through it stays green. So the setting the comments
call necessary is needed by no test.

That was filed as **REL44-30** and resolved by measurement (3b6f9007). A working copy, the
shape a refactoring holds, also has its problems read with forcing off. So the setting is kept as
defensive, the comments now say so, and a new test pins the working-copy case.

## The suite's own gate found a hollow member this stage made

Both full runs at this checkpoint passed every test and **failed the hollow-wiring gate**:
`QualityLedger#gate(String,boolean)` had only test callers. The cause:
- REL44-8 (a52b3b9a) moved the recall onto the batched `gate(String,long,long)`;
- the single-verdict form stayed behind as a forwarder only tests called.

Filed as **REL44-31** and folded with the refactoring tool (inline, 7500e7a6). The gate now
passes, with the baseline unchanged at 158. This is the "give the object its behaviour" rule
from the other side: an object should not keep a second door that only its tests use.

## Rule 1 — incomplete delegation first

`find_quality_issue(kind=incomplete_delegation)` reports 86 findings across the workspace. **None
lies in a line this range changed.** I compared the changed hunks against the flagged lines for the
four touched files that carry findings: `CoverageAttribution`, `H2ExperienceStore`,
`ExperienceTool` and `RunTestsTool`. None was introduced, and none was touched.

---

## F1 — THE STUB GUARD IS A BANDAGE ON A SHARED OUTPUT FOLDER

S16-1's cause, measured on 2026-10-15:
- An editor that imports these modules as Maven projects compiles into the SAME
  `target/classes` as the build, with the Eclipse compiler.
- On code that does not compile, it writes an error-stub class that is newer than its source.
- Maven then reports "Nothing to compile" and packages the stub: BUILD SUCCESS, exit 0.

The guard refuses any packaged class carrying the stub marker. It is correct, it sits at the one
choke every build passes, and as of this checkpoint it proves its own matching on the suite's gate
path.

**But it guards the symptom.** The two compilers still share one folder. The smallest design fix:
**give the editor its own output folder** (the Java language server's Maven import can write to a
separate path). After that the stub is never in Maven's output for the guard to find. The guard
stays as the backstop.

Dispatch: a settings change in the editor's workspace configuration, not an engine change. It is
named in the plan's open items for his ranking, because the settings are his machine's.

## F2 — `manager_service.rs` carries the fleet start, and it is 17,262 lines

- **Measured:** about 7,250 lines of production code before its first `#[cfg(test)]` module;
  +665/−155 in this range.
- **What S20 added there:** the fleet-start wait (`wait_until_imported`, `import_wait_over`, the
  stagger handling).
- **Why that is a lifecycle:** it starts residents one at a time and reads each one's health. That
  lifecycle has nothing to do with deploys, settings or the dashboard, which the same file owns.

The fleet start is a candidate for its own module: one boundary, the runtime manager plus the
health probe, and its tests beside it. This is advisory and NOT done in this stage:
- the move is a Rust refactor no jawata tool performs;
- it would be one parity-gated hand step (move the functions, re-export, `cargo test --workspace`
  green before and after).

Dispatch: a structural refactoring for his ranking, not a finding against S20's rows.

---

## The test surface this stage leaves

**Environment-independent (runs anywhere):**
- the tally, the share, the burst-by-the-clock and the stub guard's self-test;
- the four test guards;
- the studio's painter, menu plan and import-wait contract.

**Per environment (the boundary's own contract):**
- the embedder-off CI job, REL44-24, whose first run is owed at a push;
- the Windows hook tests, REL44-2, owed at the release job.

**Only reality can verify (named E2E smoke, not a suite):**
- the tray panel on the Ubuntu/Pop!_OS AppIndicator shell (REL44-10, -11);
- a three-resident restart under a CPU ceiling (REL44-12);
- the second scheduled backup copy at a live day boundary (S13-9).
