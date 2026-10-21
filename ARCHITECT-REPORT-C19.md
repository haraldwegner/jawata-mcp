# ARCHITECT-REPORT-C19 — watch mode, Sprint 28g checkpoint C19 (the S19 dogfood patch)

**Scope reviewed:** engine `78006a29`, `2cde4efa`, `914b9f0a`; studio `517ab16`, `850f2cc`, `a02b8fe`, `66f6109`.
**Project declaration:** neither `jawata-mcp/jawata-project.md` nor `jawata-studio/jawata-project.md` exists. Assumed, not declared: the engine is Java on a Maven/Tycho build whose green is `build/release-gate.sh` (compile, the Lombok probe, the whole suite); the studio is Rust + Svelte whose green is `build/run-gates.sh`.
**Target architecture:** there is no `ARCHITECTURE-*.md` for this patch's scope. The one written contract it touches is the plan's **F-28g-WIRE** (studio and engine consume each other, ship separately, and nobody owns the boundary). This report judges against that.
**History (rule 14):** nobody handed me the history; I read it myself. `git log -L` on the refusal lines in `RunTestsTool` shows they last changed in `49ae9446` (the Sprint 23 runner rewrite, not a fix). `lombok.rs` last changed in the rebrand rename. The Lombok line in the distribution pom is new. None of the patch's lines repairs an earlier repair, so the rule 12 alarm does not fire.

## Decisions for you (rule 10a)

None. Every reviewed change should stand.

## Findings (ranked; three proposals)

**1. The only signal that Lombok comprehension is off sits on a channel nobody reads, and the side that owns the fact says nothing (rule 6). BANDAGE on the signal; the design fix is the engine's.**
`517ab16` logs a Lombok workspace on an engine without the jar with `eprintln!` into the resident's log file. That channel only records. The people who suffer the degradation are agents reading `compile_workspace` and `health_check`, and they never see the log.
The ENGINE owns the fact at the instant it matters. It loads the projects, sees Lombok on their classpath, and knows whether the agent is inside its own JVM. The design fix is for the engine to publish the degradation in its own answers.
This is S19-7 ("health healthy despite compile errors"), so it is **one finding, not two**: widen S19-7 rather than open a new row.
- **What the store changed in this design (D4).** The selected lesson 6fecd8cb says to find the test that pins the false claim and move the producer and the test together. It also says where that does not hold: when the claim is a **wire field**.
  - `workspace.healthy` is a wire field. The studio reads it (`workspace_readable_probe`, manager_service.rs) and refactorings are REFUSED when it is false.
  - So the degradation must NOT be expressed by flipping `healthy`. A Lombok-degraded workspace is readable, and refusing every refactoring on it would turn a reporting gap into an outage.
  - Publish it as its own field instead: per project, comprehension degraded, with the reason (`lombok used, agent not loaded`). Stamp a one-line note on `compile_workspace` when its error count includes members the agent would have generated.
- **Producers / consumers (rule 5).**
  - Producer: the engine's health and compile answers.
  - Consumers: the agents, plus the studio's readability probe. The probe must keep reading `healthy` unchanged, which is why the fact goes in a separate field.
  - Whether anything lies outside these repos is already answered (his words: "There is nothing outisde jawata"), so this report does not ask again.
- **Actuator:** S19-7, home **28h (proposed, needs his word)**. No refactoring plan kind applies: this is new behaviour, not a restructure.

**2. The studio's half of the `lombok.jar` layout contract is tested only on a layout the test invents. DESIGN FIX, available now, at a border that already exists.**
- **Producer:** the engine distribution (`build/dist/pom.xml`, the dist-root copy). It is now guarded at the engine's release by `build/lombok-agent-probe.sh`, which checks the jar is present and that it works against a control.
- **Consumer:** `lombok.rs#bundled_lombok_jar` (a sibling of the resolved `jawata.jar`, or up to two directories above it).
- **Contract read against both sides (rule 5e):**
  - `release_manager.rs` unpacks the WHOLE archive (tar and zip alike) and locates `jawata.jar` wherever it lies (`find_relative_jar_path`).
  - The dist root carries both jars as siblings.
  - So the contract holds by construction today.
- **What is untested:** the studio's test `bundled_jar_found_as_sibling_and_above` builds its own directory tree. A producer-side move would leave that test green, which is the S19-1 shape exactly: the producer moved in Sprint 22d and nothing on the consumer side noticed.
- **The cheapest border:** the studio's release job already downloads the **published** engine archive, the one users install, in its seam-gate step (`release.yml`, "Cross-product seam gate").
  - One assertion there, `lombok.jar` beside the extracted `jawata.jar`, checks the studio's dependency against the real producer artifact, at the studio's own release.
  - This is not a guard over a fact the studio owns (rule 13): the two sides ship independently, which is the case rule 13 exempts.
- **Ordering consequence:** the studio 4.4.8 release must follow the engine 4.4.13 release, which is the release procedure's order anyway. Otherwise this assertion correctly fails on 4.4.12.
- **Actuator:** fold into the S19 patch (studio `release.yml`); home **S19**.

**3. `workspace.json` has one writer and now two readers, and its format has no owner in the studio. DESIGN FIX, small, not urgent.**
- The writer is `manager_service.rs#write_workspace_json_to_dir`. It builds the document ad hoc with `serde_json::json!{version, name, projects}`.
- The new reader is `lombok.rs#workspace_project_roots`, which parses `["projects"]` ad hoc. The other reader is the engine's workspace loader.
- Reading the file is the RIGHT source: it is exactly the list the resident will load, so the Lombok decision and the resident cannot disagree.
- What is missing is an owner for the format. Two hand-written halves of one schema in two modules will drift on the first new field.
- Give the format a type, `WorkspaceManifest { version, name, projects }`, with `write_to(dir)` and `read_from(dir)`, so the writer and the studio reader are the same object (rule 2).
- **Actuator:** a parity-gated extraction on the studio side (Rust, so outside `refactoring(action=plan)`'s Java kinds: a hand refactor with the existing `lombok.rs` and `write_workspace_json_to_dir` tests as its parity check). Home **28h (proposed, needs his word)**.

## Dispatches

| Finding | Actuator | Home |
|---|---|---|
| 1 | engine work under S19-7: a separate comprehension field in `health_check`, a note on `compile_workspace`; `healthy` unchanged | 28h (proposed) |
| 2 | one assertion in the studio release job's seam-gate step | S19 (this patch) |
| 3 | studio `WorkspaceManifest` type, writer and reader both through it | 28h (proposed) |

No `javadoc_lack` or `coverage_lack` dispatch. The patch's new public behaviour is covered by tests that went red before it: `RunTestsRefusesABrokenProjectTest` (both sites, mutant-checked) and the three `lombok.rs` tests.

## Trend (baseline diff)

- Engine whole suite on the patch commits: 3,805 of 3,809 passed, 0 failed, 4 aborts all on the accepted Linux list. Test-only-caller gate unchanged at 155. End-to-end 105 of 105.
- Studio: hollow-wiring gate unchanged at 82; `svelte-check` 0 errors (the 3 old autofocus warnings, untouched).
- **F-28g-WIRE recurs.** S19-1 is the second recorded case of a producer on the engine/studio seam moving while every consumer-side check stayed green. The first was the hook outage. Finding 2 closes this instance at a border that exists. The seam still has no single written contract; that remains his finding.

## Reviewed diffs (design fix or bandage)

| Commit | Verdict | Why |
|---|---|---|
| engine `78006a29` | **design fix** | The producer ships what its consumer depends on again. The probe sits in the release gate (it acts before publish, rule 6). It is a guard over an independently shipped contract, exempt under rule 13. |
| engine `2cde4efa` | **design fix** | The error taxonomy is corrected at its source. `RunnerClasspath#buildAndCheck` has exactly two production callers, both changed (`find_references`). Contract change to the `run_tests` error code: the studio has no code branching on it (its one `INVALID_PARAMETER` literal is a fixture for another tool). The field pile's shape key changes with the release, and no consumer needs to change. |
| engine `914b9f0a` | **design fix** (verification) | Both refusal sites are pinned; the mutant run shows each test fails on its own site. The helpers came out through `extract`. |
| studio `517ab16` | **design fix** for detection, **bandage** for the signal | Only the launcher can add `-javaagent` at JVM start, so detection lives in the right object; widening it to every project, and upwards, is correct. The `NoJar` log is finding 1. |
| studio `850f2cc`, `a02b8fe`, `66f6109` | **design fix** | Subtraction (one table, one folder field, folds, dead CSS and a dead store action removed). The folder fallback reuses the existing `add_project` job rather than adding a second registration path. |

## Deliberate duplicates

None.

## D4 — what was asked of the store and the pattern catalogue, and what was selected

- **Asked**, as `experience(kind=nominate, lanes=[experience, patterns])` before judging: *which side should own and publish the fact that comprehension is degraded, when a launcher decides from build-file heuristics whether to add an agent the engine needs, and how a file-layout contract between two independently shipped products should be guarded.* query_id `849c5a8e-1812-4448-9bc5-ea78d9328a68`.
- **Offered:** 16 candidates, 8 experiences and 8 catalogue patterns.
- **Selected: one**, `6fecd8cb`, *"When a claim the product itself emits turns out to be false, find the test that asserts it before treating the fix as a wording change … the producer and the test have to move together"*, with its stated exception for wire fields. **What it changed:** finding 1 now forbids expressing the degradation by flipping `workspace.healthy`, because that field is a wire field the studio's probe parses and refactorings key on.
- **Not selected:**
  - The release-skew failure mode (5640b79c): its situation is a generated instruction surface updating before the engine, not a missing file.
  - "Read the producer's own configuration before guarding its output" (5a8bd9cf): there is no unwanted output to suppress here.
  - "Diff the sibling members' inputs" (959ed876) and the other experiences: different situations.
  - None of the eight catalogue patterns. Monolith, client-side UI composition, rule engine, DAO factory, saga, separated interface, bytecode and polling publisher name situations this patch does not have.
- **D-SIX:**
  - The catalogue describes the engine package of `RunTestsTool` (`org.jawata.mcp.tools`). The patch adds no new job there: the refusal changes an existing one.
  - The engine's `LombokDetector` is NOT a second copy of the studio's detection. It finds Lombok use per compilation unit, by import, for the removal tools. The studio decides per workspace, by build files, whether the JVM needs the agent. Different questions.
  - `NOT CATALOGUED: jawata-studio src-tauri (Rust) — D-SIX not checked by the catalogue.` Checked by hand instead: `workspace.json` has exactly one writer in the studio, so the new reader is not a second pipeline (finding 3 is about the format's owner, not a duplicate job).

## Below the fold

- `lombok.rs`'s header says it is a *"Mirror of the fork's source-only LombokDetector … keep the two detection heuristics in sync"*. It is not a mirror (see D-SIX), and the instruction sends the next reader to synchronise two things that answer different questions. Correct the comment. Cheap; can ride in this patch.
- `RunTestsTool#coverageDelta` (line 907): `incomplete_delegation`, a loop recovering identity through a getter chain. Pre-existing and not touched by this patch. It belongs in a later sweep, not this checkpoint.
- `build/lombok-agent-probe.sh` checks the producer only. That is correct for an engine gate; finding 2 adds the consumer half on the studio side.

## Skipped by record

Nothing previously declined touches this scope.

## Ledger rows (rule 16)

- **S19-7 (amend, not new):** add *"Design (C19 architect): the engine owns the fact; publish it as its own per-project comprehension field and a note on compile_workspace, never by flipping `workspace.healthy`, which the studio's readability probe parses and refactorings key on."* Home stays 28h (proposed, needs his word).
- **S19-8 · C19 · architect · open · · · S19 ·** The studio's side of the `lombok.jar` layout contract is tested only on an invented directory tree. Assert, in the studio release job's seam-gate step against the published engine archive, that `lombok.jar` lies beside `jawata.jar`.
- **S19-9 · C19 · architect · open · · · 28h (proposed, needs his word) ·** `workspace.json` has one writer and a new in-studio reader, both hand-written against an unowned format. Give it a `WorkspaceManifest` type owning write and read.
