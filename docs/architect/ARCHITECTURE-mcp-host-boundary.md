# ARCHITECTURE — the host boundary: jawata-mcp OS-agnostic BY CONSTRUCTION

> Architect seat, DESIGN MODE, fresh context, 2026-08-14. Input: the diagnostic
> report `docs/architect/ARCHITECTURE-mcp-os-agnostic.md` (2026-08-13) and the
> repository at `/home/harald/CursorProjects/jawata-mcp`, re-derived through the
> engine (censuses and consumer sets below carry their deriving call). This is
> the TARGET architecture and its executable migration; it will be executed with
> jawata's own parity-gated refactoring tools (Sprint 29, "jawata refactors
> jawata").

## 1. The target — one boundary, four resource kinds, both directions

The engine core is *proven* OS-agnostic (19 character-identical parity outputs
on Windows). The design is therefore **one thing, not a pattern collection**:
an **anti-corruption layer** — a single leaf package `org.jawata.core.host`
through which ALL host contact passes, in both directions, for all four host
resource kinds (paths, text, processes, filesystem), **and the test suite
crosses the same boundary through the same package's test kit**. Everything
above the boundary speaks only canonical forms: `/`-relative display paths,
LF text, commands as values, capabilities as queries. Everything below it is
native and lives in exactly one place. The individual patterns named in §2 are
this one boundary's per-seam mechanics, not separate ideas.

```
 org.jawata.mcp ─────────────────────────────────────────────────────────────┐
 │ tools · DiffRenderer · CoverageService · ForkedTestRunner · runtime/      │
 │ profile adapters (GdbAdapter keeps the gdb/lldb DIALECT knowledge;        │
 │ HsErrParser stays pure text)                                              │
 └───────────────┬─────────────────────────────────────────────────────────--┘
                 │  sees ONLY canonical forms:
                 │  paths '/'-display · text LF · Command values · capability queries
                 ▼
 org.jawata.core ────────────────────────────────────────────────────────────┐
 │ JDT engines · analysis · refactoring · staging · ProjectImporter          │
 │ (PROVEN OS-agnostic — MUST NOT BE TOUCHED except call-site rewires)       │
 └───────────────┬───────────────────────────────────────────────────────────┘
                 │  every host contact through ONE package
                 ▼
 org.jawata.core.host ═══ THE OS BOUNDARY (anti-corruption layer; LEAF) ═════╗
 ║                                                                           ║
 ║  HostOS          HostPaths          HostText         HostProcesses        ║
 ║  the ONLY        (= today's         canonical=LF;    executable           ║
 ║  os.name         IPathUtils/        normalize on     resolution           ║
 ║  reader;         PathUtilsImpl,     read; \R line    (.cmd/.exe/          ║
 ║  WINDOWS/        moved here —       split; EOL       wrapper names);      ║
 ║  MACOS/LINUX     already the        re-applied on    ONE launch           ║
 ║  + description() proven seam)       write            chokepoint;          ║
 ║  for provenance                                      CannotLaunch ≠       ║
 ║                                                      exit!=0              ║
 ║  HostFs: delete-with-retry (the proven RuntimeSessionRegistry impl,       ║
 ║  relocated — ForkedTestRunner's silent duplicate dies) · capabilities     ║
 ║  as queries, never POSIX calls that throw · crash-artifact globs          ║
 ║  (hs_err *.log vs .mdmp minidump)                                         ║
 ╚═══════════════╤═══════════════════════════════════════════╤═══════════════╝
                 ▼                                           ▲
        JDK NIO / ProcessBuilder / OS            tests cross HERE too:
                                                 TestHost kit (org.jawata.core.tests,
                                                 test-jar → org.jawata.mcp.tests):
                                                 java-launcher fakes (argv-capable on
                                                 every OS) · workspace.json via Jackson
                                                 · in-JVM port fakes for ring-1 suites
```

**Module responsibilities, one paragraph each**

- **`org.jawata.core.host` (new package, module `org.jawata.core`)** — the
  boundary. Owns every read of `os.name`, every `ProcessBuilder`, every
  filesystem-semantics decision (deletion retry, permission capability,
  crash-artifact naming), every EOL normalization. Leaf: depends on the JDK
  only — never on the engine, never on the tools, never on JDT. Everything in
  it is small, deterministic, and contract-tested per environment (§6 ring 2).
- **`org.jawata.core` (engine)** — unchanged in semantics. `ProjectImporter`
  keeps its Maven discovery ORDER (a past-bug fix, owner-protected) but asks
  `HostProcesses` for the per-OS *spellings* it currently computes inline
  (`mvn` vs `mvn.cmd`, `mvnw` vs `mvnw.cmd`). `PathUtilsImpl`/`IPathUtils` —
  already the proven path seam — move into `host` unchanged.
- **`org.jawata.mcp` (tools)** — consumes canonical forms only. The ten
  `ProcessBuilder` sites in this module route through `HostProcesses`;
  `DiffRenderer` splits on any line terminator via `HostText`; provenance
  strings (`HealthCheckTool`, `CoverageService` manifests) come from
  `HostOS.description()` with their exact current format. `GdbAdapter` keeps
  its gdb/lldb dialect knowledge (that is *debugger* variance, not OS
  variance) but launches through the boundary. `HsErrParser` stays pure text —
  which is what makes its Windows/macOS dialects testable anywhere (§6, §7c).
- **`org.jawata.core.tests` / `org.jawata.mcp.tests`** — gain the `TestHost`
  kit (Object Mother for host fixtures) in `org.jawata.core.tests`, published
  as a Maven test-jar to `org.jawata.mcp.tests`. Fake executables are
  java-launcher-based (the JVM at `java.home` is the one argv-faithful
  executable guaranteed present on all three OSes); `workspace.json` is built
  by Jackson, never by string concatenation around `toAbsolutePath()`.

## 2. Seams — pattern and smell prevented, per seam (in service of §1's one target)

| Seam | Absorbs (derived, not recalled) | Pattern | Smell prevented |
|---|---|---|---|
| `HostOS` | the 5 production `os.name` reads: `PathUtilsImpl:119`, `ProjectImporter:1586`, `CoverageService:85,211`, `HealthCheckTool:200` (census: `find_string_literals(query="os.name")` → 12 total, 5 production) | Facade over a primitive | Shotgun surgery — every module re-derives "am I on Windows" from a string |
| `HostPaths` | `org.jawata.core.IPathUtils` + `PathUtilsImpl` (9 references: `IJdtService`, `JdtServiceImpl` ×3, `LoadedProject`, `ScopedJdtService`, + tests — `find_references(symbol=org.jawata.core.IPathUtils)`) | Anti-corruption layer (already proven; promoted into the boundary package) | Primitive obsession; the 8.3/symlink leak class that already shipped twice |
| `HostText` | `DiffRenderer#splitLines` (LF-only split, `DiffRenderer.java:51-63`) + every golden/fixture read in the parity suites | Boundary normalizer | Representation leak — 19 parity reds from one invisible byte |
| `HostProcesses` | the 11 production `ProcessBuilder` sites: `ProjectImporter:1386`, `CoverageService:183`, `GitDiff:54`, `GitHistory:86`, `MutationService:105`, `ForkedTestRunner:227`, `JvmTargets:105`, `HeapHistogram:54`, `Jcmd:42`, `GdbAdapter:75,122` (census: `find_pattern_usages(kind=instantiation, query=java.lang.ProcessBuilder)` → 29 rows; 11 production, 10 test, 8 non-source) | Adapter + single chokepoint; `CannotLaunch` and `NonZeroExit` as distinct outcomes | Shotgun surgery on shell-outs; conflating "error=193 could not start" with "ran and failed" |
| `HostFs` | `RuntimeSessionRegistry#deleteRecursively` (the PROVEN bounded-retry + residue-count impl; consumers: `RuntimeSession:218`, `RuntimeSessionRegistry:83,134`) replaces `ForkedTestRunner#deleteRecursively` (the silent-swallow duplicate, `ForkedTestRunner.java:329-335`); POSIX-permission use becomes a capability query; crash-artifact globs (`hs_err_pid*` AND `.log`) | Adapter; capability object | The duplicate-implementation trap (a fix that lands in only one of two copies); silent best-effort swallows; POSIX calls that throw on Windows |
| `TestHost` (tests only) | `#!/bin/sh` fake executables, `setPosixFilePermissions` fixtures, string-concatenated `workspace.json` | Object Mother for host fixtures | The five test-setup failure families of the Windows run — and the `.cmd`-argv trap (§7b) |

## 3. Dependency rule — stated so CI can enforce it

**Direction:** `org.jawata.mcp` → `org.jawata.core` → `org.jawata.core.host` →
JDK. `org.jawata.core.host` is a **leaf**: it must not import
`org.jawata.core.*` (outside itself), `org.jawata.mcp.*`, or `org.eclipse.*`.

**Invariants (each with its exact check; enforced in-suite by a new
`org.jawata.mcp.tools.verification.HostBoundaryRulesTest` that drives jawata's
own search engine — jawata guards jawata):**

1. *No process launch outside the boundary.*
   `find_pattern_usages(kind=instantiation, query="java.lang.ProcessBuilder")`
   → every row whose `filePath` is under a production `src/` root must lie
   under `org.jawata.core/src/org/jawata/core/host/`. Test rows must lie under
   the `TestHost` kit or use it.
2. *No OS sniffing outside the boundary.*
   `find_string_literals(query="os.name")` → production rows ⊆
   `org/jawata/core/host/`. (Baseline today: 5 production rows, named in §2.)
3. *The boundary is a leaf.*
   `find_quality_issue(kind=forbidden_edge, from="org.jawata.core.host", forbidden="org.jawata.mcp")`
   and `(from="org.jawata.core.host", forbidden="org.eclipse")` → zero
   findings; plus imports of `org.jawata.core.*` from `host` limited to
   `host.*` itself.
4. *POSIX-only NIO is boundary-internal.*
   `find_references(kind=references, symbol="java.nio.file.Files#setPosixFilePermissions")`
   (and `PosixFileAttributeView`) → production rows ⊆ `host/`.

Steps M13 wires these as JUnit assertions so the matrix fails on the first
regression, not on the next Windows incident.

## 4. What must NOT be touched

- The JDT interaction and refactoring semantics (`org.jawata.core` engines) —
  proven OS-agnostic; only call-site rewires named in §5 touch this module.
- The parity goldens' content and `ParitySupport`'s **byte-exact** comparison
  (`org.jawata.mcp.tools.refactoring.ParitySupport`). Bytes are fixed at
  checkout (M0), never by loosening the assertion.
- `ProjectImporter#resolveMavenCommand`'s discovery ORDER (wrapper → PATH →
  known locations) — a past-bug fix. M7 changes only where the per-OS
  *spellings* come from.
- The assumption-skip mechanism (honest "unproven here, not passing") — kept
  and budgeted (M13), never converted to passes.
- The relocated `deleteRecursively` retry SEMANTICS (10 × 250 ms, residue
  logged with count) — moved verbatim in M9, not rewritten.

## 5. The migration — ordered, parity-gated, reversible (D-TWO)

Conventions for every step: baseline green first (`compile_workspace` = 0/0 —
verified live on this workspace today — plus the step's named suite via
`mvn -pl <module> verify` or the filtered class run); ONE jawata refactoring
with `auto_apply=false`, inspect the diff, apply; re-gate; red →
`refactoring(action=undo, undoChangeId=…)`. Steps marked **[additive]** create
new files only (no refactoring kind creates a class; they are declared as
such, gated identically, reverted by `git revert`). Every step is shippable on
its own — no flag-day.

- **M0 — pin bytes at checkout** *(repo config, not code)*. `.gitattributes`:
  `* text=auto eol=lf` + `*.cmd text eol=crlf`; `git add --renormalize .`.
  Gate: `git ls-files --eol` shows the attr on every file with zero content
  churn (`git diff --stat` = attributes file only); full Linux suite green;
  the 19 parity reds are expected to clear on the next Windows matrix run
  (prediction, verified there — §8). Rollback: `git revert`.
- **M1 — [additive] `HostOS`.** New `org.jawata.core.host.HostOS` (enum
  `WINDOWS/MACOS/LINUX`, `current()`, `isWindows()`, `description()`
  preserving the exact `os.name + "/" + os.arch` provenance format). TDD:
  `HostOSTest` first. Gate: compile 0/0, suite green.
- **M2 — move the proven path seam.**
  `move(kind=class, typeName=org.jawata.core.IPathUtils, targetPackage=org.jawata.core.host)`
  then `move(kind=class, typeName=org.jawata.core.PathUtilsImpl, targetPackage=org.jawata.core.host)`.
  Consumer set (derived, §2): 9 references across `IJdtService`,
  `JdtServiceImpl`, `LoadedProject`, `ScopedJdtService` + 2 tests. Known
  hazard from the store: a class move once rewrote `@link` imports too —
  inspect the staged diff fully before apply. Gate: compile 0/0;
  `PathUtilsImplTest`, `PathRoundTripTest` green. Rollback: `undoChangeId`.
- **M3 — optional polish:**
  `rename_symbol(symbol=org.jawata.core.host.PathUtilsImpl, newName=HostPaths)`.
  Skippable; churn-vs-coherence is the executor's call at apply time.
- **M4 — one `os.name` reader.**
  (a) `org.jawata.core.host.PathUtilsImpl#isWindows` has exactly ONE consumer
  (`PathUtilsImplTest:173` — derived via `find_references`): retarget the test
  to `HostOS`, make the method a one-line delegate, then
  `inline(kind=method, symbol=org.jawata.core.host.PathUtilsImpl#isWindows)`
  to remove it. (b) `org.jawata.core.project.ProjectImporter#isWindows`
  (private, `ProjectImporter.java:1585`) body →
  `HostOS.current().isWindows()`. Gate: compile 0/0;
  `find_string_literals("os.name")` production census shrinks to
  `{ProjectImporter (gone), CoverageService ×2, HealthCheckTool}` → then M5.
- **M5 — provenance reads.** `HealthCheckTool` (line 200) and
  `CoverageService#finalizeArtifact` (85) / `#importArtifact` (211) use
  `HostOS.description()` / `HostOS.current().name()`. Byte-identical output
  strings (manifest format is an external contract). Gate: compile 0/0;
  coverage/health suites green; **census invariant §3.2 reaches its end state:
  production `os.name` reads ⊆ `host/`**.
- **M6 — [additive] `HostProcesses`.** Interface + production adapter:
  `executableCandidates(String base)` (Windows: `base.cmd`, `base.exe`,
  `base.bat`, `base`; POSIX: `base`), `wrapperNames(String base)`
  (`mvnw.cmd`/`mvnw`), `launch(HostCommand)` → result that distinguishes
  **CannotLaunch** (spawn failed — `error=193`, ENOENT) from **NonZeroExit**
  (ran, failed) with output draining owned by the port. TDD: contract test
  first (§6 ring 2). Gate: compile 0/0.
- **M7 — the importer's OS knowledge crosses the seam.**
  `change_method_signature(symbol=org.jawata.core.project.ProjectImporter#resolveMavenCommand(java.nio.file.Path,java.lang.String,java.util.List,boolean))`:
  replace `boolean windows` with `HostProcesses host` (defaultValue
  `Host.processes()` at call sites). Coupled change — the tool's reported
  compile errors (the two ternaries at 1249-1250) ARE the worklist: they
  become `host.wrapperNames("mvnw")` / `host.executableCandidates("mvn")`
  lookups; discovery ORDER untouched (§4). Gate: compile 0/0;
  `ProjectImporterTest` (incl. the three Maven-resolution shapes, now on
  TestHost fakes after M11) green. Rollback: `undoChangeId`.
- **M8 — the ten mcp-module launch sites, one file per sub-step, each
  shippable.** For each: rewire to `HostProcesses#launch`, gate on compile 0/0
  + that site's named suite, rollback per-step.
  M8.1 `CoverageService#runGit` (183) — `CoverageAdvisoryTest`/`CoverageDeltaTest`;
  M8.2 `GitDiff:54` + M8.3 `GitHistory:86` — diff/smell suites;
  M8.4 `MutationService:105` — mutation suite;
  M8.5 `ForkedTestRunner:227` — the run_tests spine suites;
  M8.6 `JvmTargets:105` + M8.7 `HeapHistogram:54` + M8.8 `Jcmd:42` —
  `DebugSessionSpineTest`, `ProfileFloorTest`, `HotspotTest`;
  M8.9 `GdbAdapter:75,122` — `NativeTriageTest` (dialect knowledge STAYS in
  the adapter; only the spawn moves).
  **M8 exit gate = census invariant §3.1: production `ProcessBuilder`
  instantiation ⊆ `host/`.**
- **M9 — `HostFs`: one deletion, one owner.** [additive] `HostFs` receives
  the `RuntimeSessionRegistry#deleteRecursively` implementation VERBATIM
  (semantics protected, §4); registry method becomes a delegate, then
  `inline(kind=method, symbol=org.jawata.mcp.runtime.RuntimeSessionRegistry#deleteRecursively)`
  rewrites its 3 derived call sites (`RuntimeSession:218`, registry 83/134);
  same delegate-then-
  `inline(kind=method, symbol=org.jawata.mcp.execution.ForkedTestRunner#deleteRecursively)`
  for the silent duplicate — the swallowing copy DIES and the runner's
  teardown gains the retry (intended behavior change, stated). Plus
  `capabilities().posixPermissions()`, `setExecutable(Path)`
  (`File.setExecutable` where POSIX views are absent), and
  `crashArtifacts(dir)` globbing hs_err as `startsWith("hs_err_pid") &&
  endsWith(".log")`, minidumps (`.mdmp`) reported as their own artifact class.
  Gate: compile 0/0; `DebugSessionSpineTest` + runner suites green.
- **M10 — `HostText` + the CRLF diff fix (backlog e).** [additive] port:
  `canonicalizeToLf`, `splitLines` on `\R`, `eolOf`. TDD: a red
  characterization test — `DiffRenderer.unifiedDiff` over CRLF content must
  yield `\r`-free diff lines — then
  `org.jawata.mcp.refactoring.DiffRenderer#splitLines` (lines 51-63) delegates
  to `HostText.splitLines`. Gate: the FULL parity battery byte-identical
  (LF inputs are unaffected by `\R`; zero golden churn proves it) + the new
  CRLF test green.
- **M11 — the `TestHost` kit (backlog b).** [additive]
  `org.jawata.core.host.test.TestHost` in `org.jawata.core.tests`, published
  as a test-jar consumed by `org.jawata.mcp.tests` (build change; if the
  reactor resists, fallback is a tiny `org.jawata.testkit` module — one
  decision, flagged risk). `fakeExecutable(behavior)` = a generated launcher
  that invokes `${java.home}/bin/java(.exe) -cp testkit FakeToolMain …` — the
  JVM is the one executable that is argv-faithful AND present on every runner;
  a `.cmd` demonstrably is not (exit-3 evidence, run 31804158929).
  `workspaceJson(…)` via Jackson only. Migrate: `ProjectImporterTest` fake
  `mvnw`, `NativeTriageTest` failing adapter, `RcpLaunchShapeTest` launcher
  (drop `setPosixFilePermissions` for `HostFs.setExecutable`),
  `WorkspaceFileWatcherTest` JSON. Gate: those suites green on Linux; their
  Windows verdict lands on the next matrix run (§8).
- **M12 — deterministic prune (backlog d).**
  `change_method_signature` on the `org.jawata.mcp.knowledge.H2ExperienceStore`
  constructor: add `java.time.Clock clock` (defaultValue
  `java.time.Clock.systemUTC()`); `pruneAged` (line 892) computes its cutoff
  from `Instant.now(clock)`. `ExperienceStore`/`RecoveringExperienceStore`
  interfaces unchanged. `ExperienceToolHygieneTest#prune_removes_only_aged_rejected_or_superseded`
  pins a fixed clock — the proven Windows-only timing flake (A/B on identical
  commit: runs 31814248338 fail / 31808099077 pass) becomes impossible, not
  rarer. Gate: hygiene suite green; no sleeps added.
- **M13 — CI enforcement (backlog a).** [additive]
  `HostBoundaryRulesTest` asserting §3's four invariants through the engine;
  per-OS abort budget: committed `expected-aborts.{linux,windows,macos}` lists
  + a job step failing on `aborted > expected(os)` — a new skip fails the job
  instead of silently shrinking coverage. Gate: three-OS matrix green with
  budgets enforced.

Order rationale: M0 alone clears the 19-red lane (cheapest cause first); M1-M5
establish the boundary where a seam already exists (paths, OS identity); M6-M9
move the launch/filesystem lanes one proven site at a time; M10-M12 close the
product wrinkles; M13 locks the door. After any step the repo ships.

## 6. The end-state test surface (D-THREE)

The surface is a consequence of §1: everything above the boundary computes over
canonical forms, so its tests cannot vary by OS; everything below it is
contract-tested against the port's own promises, per environment; what neither
ring can prove is named as E2E smoke. This matches the owner's ruled end state:
**full suite on Linux; on Windows/macOS the host-boundary suite plus
end-to-end dogfood prompts.**

**Ring 1 — environment-independent (run once, anywhere; in practice: the full
Linux suite).** All engine/analysis/refactoring suites and the parity
batteries (byte-exact everywhere because bytes are pinned at checkout and text
is canonical LF); `DiffRenderer` incl. the CRLF characterization test;
`HsErrParser` dialect tests driven by COMMITTED per-OS fixture logs (pure text
— the Linux dialect today; Windows `EXCEPTION_ACCESS_VIOLATION` and Mach-O
underscore fixtures when §7c executes); experience-store hygiene under a fixed
clock; every suite that needs a "host" uses TestHost's in-JVM port fakes.

**Ring 2 — the boundary's own contract, per environment (the host-boundary
suite; runs on all matrix OSes).** One contract test per port, asserting the
port's promises against the REAL adapter:
`HostPathsContractTest` (8.3/symlink round-trip: canonical display for a
short-named/symlinked root), `HostProcessesContractTest` (finds `mvn.cmd` on
Windows; argv fidelity through a real launch of the java-based fake;
CannotLaunch vs NonZeroExit distinguished on a missing and on a failing
executable), `HostFsContractTest` (delete-under-late-handle-release; capability
queries answer truthfully; crash-artifact glob keeps a planted `.mdmp` out of
the hs_err set and reports it separately), `HostTextContractTest` (CRLF
checkout tolerance). Plus the per-OS abort budget gate (M13) — the honest-skip
mechanism, bounded.

**Ring 3 — only reality can verify (named E2E smoke, not a suite).**
(1) *Windows dogfood prompt:* load a real Maven project, run one rename, read
the diff — proves `mvn.cmd` resolution, relative `/`-display paths, and
EOL-clean diff in one pass. (2) *macOS dogfood prompt:* induce a crash in a
launched fixture JVM, run native triage — proves the lldb dialect end-to-end
including leading-underscore symbol correlation. (3) *RCP launch smoke* on
each desktop OS. These are prompts in the release dogfood checklist, not
assertions a runner can fake.

## 7. Homed backlog — where each of the five hardening items landed

| Item | Home |
|---|---|
| (a) per-OS abort budget in CI | **M13** (committed per-OS expected lists; job fails on excess) + ring 2 |
| (b) argv-capable Windows fake executable | **M11**: java-launcher fakes (the `.cmd` argv loss — exit-3, run 31804158929 — is designed OUT, not worked around); argv fidelity proven per-OS in `HostProcessesContractTest` (ring 2) |
| (c) hs_err Windows dialect (`EXCEPTION_ACCESS_VIOLATION`, `.mdmp`) + Mach-O/lldb dialect | The architecture PLACES it: `HsErrParser` stays pure text → dialect support is fixture-driven ring-1 work; `.mdmp` becomes a first-class artifact in `HostFs.crashArtifacts` (**M9**); lldb/Mach-O launch+parse stays in `GdbAdapter` behind `HostProcesses` (**M8.9**). The parser-dialect FEATURE itself is **out of scope of this migration — destination: the Sprint-28-family native-triage batch**, executed inside these seams; its per-OS fixture capture is a one-time ring-3 action |
| (d) `ExperienceToolHygieneTest#prune` timing flake | **M12** (injected `java.time.Clock`; fixed-clock test) |
| (e) `DiffRenderer.splitLines` LF-only split | **M10** (delegate to `HostText.splitLines` on `\R`; parity battery byte-identical as the non-regression gate) |

## 8. What I could and could not verify

**Verified, with the deriving call:** `ProcessBuilder` census — 29 rows, 11
production sites as listed in §2, 10 test sites, 8 rows carrying no source
path (non-source matches), via `find_pattern_usages(kind=instantiation,
query=java.lang.ProcessBuilder)` · `os.name` census — 12 rows, 5 production,
via `find_string_literals(query="os.name")` · consumer sets:
`PathUtilsImpl#isWindows` = 1 (test only), `RuntimeSessionRegistry#deleteRecursively`
= 3, `IPathUtils` = 9, via `find_references` · `DiffRenderer` lives at
`org.jawata.mcp.refactoring.DiffRenderer` (the input's unqualified name was
stale; corrected by the engine's SYMBOL_RELOCATED answer) · the JFR-teardown
retry ALREADY SHIPPED (`RuntimeSessionRegistry.java:145-190`, bounded 10×250 ms
+ residue count — the diagnostic's step 5 is done; M9 relocates, it does not
re-fix) · `ForkedTestRunner#deleteRecursively` is still the silent-swallow
duplicate (`ForkedTestRunner.java:329-335`) · `pruneAged` cutoff comes from
`Instant.now()` (`H2ExperienceStore.java:892-893`) · hs_err globbing exists
only in `NativeTriageTest` + `ProfileTool` doc strings, none in production
scanning (`find_string_literals(query="hs_err_pid")`) · `compile_workspace`
on this repo is 0/0 TODAY (run live), so it can serve as every step's gate —
the older "self-analysis is classpath-degraded" store entry no longer holds ·
the prune flake's Windows-only A/B evidence (store recall, runs
31814248338/31808099077).

**Could not verify (and how each is bounded):** any Windows/macOS behavior —
no such runner in this session; M0's 19-red clearance and M11's fixture
verdicts are predictions gated by the next matrix run, and ring 2 exists
precisely so those predictions become assertions · the `.cmd` argv-loss
evidence (run 31804158929) — taken from the owner's brief, not re-run; M11's
java-based fakes do not depend on its precise mechanism · the test-jar wiring
from `org.jawata.core.tests` to `org.jawata.mcp.tests` — a build change I did
not prototype; flagged in M11 with a named fallback · `retargetCallsTo` /
static-method move limits in the refactoring catalog — where no catalog kind
fits, the step says [additive] or delegate-then-`inline` honestly instead of
naming a kind that does not exist.