# ARCHITECTURE — jawata-mcp OS-agnostic by construction

> Produced 2026-08-13 by the architect seat in DESIGN MODE, fresh context — it
> received the Windows CI log, the repository, and the owner's hypothesis AS A
> HYPOTHESIS to confirm or refute. It did not see any prior diagnosis.

## 1. Verdict on the hypothesis

**The core claim is CONFIRMED; the enumeration is REFUTED as incomplete.**

Confirmed: *"there is nothing OS-specific in analysis or refactoring."* The
strongest evidence is in the failure log itself: all 19 parity failures print
an expected and an actual that are **character-identical** — the JDT engine
produced the exact same renames, extracts, inlines and import edits on Windows
as the Linux-recorded goldens. The engine's semantic output is OS-agnostic,
demonstrated, not assumed. Zero failures are in symbol resolution, AST
rewriting, or reference finding.

Refuted: *"the only OS-dependent parts are external process launch and host
paths."* The measured Windows surface has **four** variance lanes, not two —
and a fifth in the test suite itself:

1. **Host paths** (the already-fixed `PathUtilsImpl#formatPath` defect).
2. **Process launch** — including *what counts as executable*: `mvn` vs
   `mvn.cmd`, shebang scripts vs `CreateProcess error=193`.
3. **Text representation** — line endings at every file/engine and
   file/assertion crossing. This lane alone accounts for 19 of 32 failures and
   is absent from the hypothesis.
4. **Filesystem semantics** — delete-while-handles-linger (JFR repo), POSIX
   permission APIs that throw on Windows, crash artifacts (`.mdmp` minidump
   beside the `hs_err` log).
5. **The test fixtures are part of the OS boundary too** — fake executables
   written as `#!/bin/sh` scripts, JSON hand-built by string concatenation
   around `Path.toAbsolutePath()` (raw backslashes = invalid JSON escapes).

Only **2 of 39** non-green outcomes trace to product code (JFR teardown; plus
the `DiffRenderer` LF-only split as a contributing product wrinkle). The
architecture did not "fail to be OS-agnostic" — the *boundary* was never named
as a seam, so its knowledge is scattered, and the test suite crossed it
unguarded.

## 2. Failure classification — every named failure

Derived from `grep -nE '^  JUnit Jupiter:' win-full.log` (32 rows,
cross-checked against `SPIKE-TESTS total=1783 succeeded=1744 failed=32
aborted=7`), source reads cited per row.

### The 19 parity failures — ONE cause: line-ending divergence at git checkout

AnonToLambda (2) · ApplyCleanup (2) · ChangeMethodSignature (3) ·
ExtractConstant · ExtractInterface · ExtractSuperclass · ExtractVariable ·
InlineMethod (2) · InlineVariable · OrganizeImports · Rename (4).

**Root cause (verified in source):** expected and actual print identically →
the difference is invisible bytes = `\r`.
- The repo has **no EOL policy**: `.gitattributes` contains only export-ignore
  lines; `git ls-files --eol` shows every file `i/lf` with blank attr. On a
  Windows runner (Git for Windows defaults `core.autocrlf=true`), fixtures AND
  goldens are checked out CRLF.
- `ParitySupport.assertParity` compares `Files.readString(golden)` byte-exactly
  against a string rendered in-JVM with `'\n'`; `normalizeDiff` splits on
  `"\n"`, leaving any `\r` attached and invisible.
- `DiffRenderer.splitLines` (DiffRenderer.java:55) splits on `"\n"` only, so
  CRLF content leaves `\r` on diff content lines while structural lines end in
  bare `\n`. Bytes can never match.

**Class: test-infrastructure (repo checkout config), with one product wrinkle**
(`splitLines` LF-only — a real CRLF user project would get `\r`-polluted diff
content lines today). NOT the already-fixed path bug: these diffs show correct
relative `a/src/...` paths.

### The 13 remaining failures

| Test | Root cause (source-verified) | Class |
|---|---|---|
| ProjectImporterTest ×3 (Maven resolution shapes) | fake wrapper is `#!/bin/sh` named `mvnw`; the product's `resolveMavenCommand` on Windows correctly looks for `mvnw.cmd` (ProjectImporter.java:1249) → fake never selected → the runner's REAL mvn.cmd ran | Test setup (product verified correct) |
| WorkspaceFileWatcherTest picksUpNonAtomicDirectWrite | test hand-builds JSON by string concat embedding `toAbsolutePath()` — raw `\` = invalid JSON escapes on Windows; every reconcile parse fails, and `reconcileFromDisk`'s `finally { recordCurrentMtime(); }` then disarms the mtime fallback | Test setup; **latent product hazard** (below the fold) |
| DebugSessionSpineTest launchedSessionLeavesNoRecordingBehind | `RuntimeSession.close`: destroyForcibly → unchecked `waitFor(10s)` → `deleteRecursively` swallowing every failure, no retry — Windows releases handles late | **Product defect** |
| NativeTriageTest ×6 ("exactly one hs_err file") | `name.startsWith("hs_err_pid")` also matches the Windows minidump `hs_err_pidN.mdmp` written beside the `.log` | Test setup |
| NativeTriageTest runBacktraceReportsANonZeroExit | fake failing adapter is a `.sh` — `CreateProcess error=193`; test never reaches its scenario | Test setup (+ product verify after) |
| RcpLaunchShapeTest | bash fake launcher + `setPosixFilePermissions` (throws on Windows) | Test setup |

### The 7 aborted — all honest assumption-skips, zero defects

Gradle Tooling ×4 (deliberate `-Djawata.skip.gradle=true` on this job) ·
embedding corpus ×2 (no `-Djawata.embed.corpus`) · unreadable-directory guard
×1 (Windows cannot express the POSIX read-bit — correctly reported "unproven
here, not passing"). The right mechanism; keep it, budget it (§4).

## 3. Target architecture — OS-agnostic by construction

**Principle:** the engine core is *proven* OS-agnostic; what is missing is a
single named boundary through which ALL host contact passes. Today the OS
knowledge is scattered — `windows ? "mvn.cmd" : "mvn"` inline in
`ProjectImporter`, canonicalization in `PathUtilsImpl`, an LF assumption in
`DiffRenderer`, no owner at all for deletion/permission semantics. That
scattering is the smell (**shotgun surgery**); the fix is a **Ports & Adapters
boundary package** — an anti-corruption layer between engine and host.

```
      MCP tool layer (org.jawata.mcp: tools, DiffRenderer, RuntimeSessionRegistry)
                     |  sees ONLY canonical forms:
                     |  paths '/'-relative . text LF . Process via port
                     v
      Engine core (org.jawata.core + JDT engines)          <-- PROVEN OS-agnostic
      analysis . refactoring . staging                         (19 identical parity
                     |                                          outputs on Windows)
                     |  every host contact through ONE package
                     v
   +----------------------------------------------------------------------+
   | org.jawata.core.host   — THE OS BOUNDARY (Ports & Adapters /         |
   |                          Anti-Corruption Layer)                      |
   |                                                                      |
   |  HostPaths            HostText            HostProcesses   HostFs     |
   |  canonical =          canonical = LF;     resolves the    delete     |
   |  absolute, real,      normalize on read,  EXECUTABLE      w/ retry;  |
   |  symlink/8.3-         re-apply file's     (.cmd/.exe/     perms as   |
   |  resolved; display    own EOL on write    shebang), one   CAPABIL-   |
   |  = '/'-relative       (JDT tracks it)     ProcessBuilder  ITIES, not |
   |  [exists today as                         chokepoint;     POSIX      |
   |   PathUtilsImpl —                         launch-failure  calls;     |
   |   becomes this port]                      != exit!=0,     artifact   |
   |                                           both honest     globs      |
   +----------------------------------------------------------------------+
                     |                                ^
                     v                                | same ports, test impls
              JDK NIO / ProcessBuilder / OS    tests: TestHost fixture library
                                               fakeExecutable() -> .cmd on Win,
                                               shebang elsewhere; JSON via
                                               Jackson, never string+path concat
```

| Seam | Becomes / absorbs | Pattern | Smell prevented |
|---|---|---|---|
| `HostPaths` | `PathUtilsImpl` (already the seam; promote to named port) | Facade / ACL | Primitive obsession; the bug class already shipped once |
| `HostText` | new; `DiffRenderer.splitLines` + every fixture/golden read | Boundary normalizer | Representation leak — 19 reds from one invisible byte |
| `HostProcesses` | `resolveMavenCommand`'s OS branch, gdb/adapter launch, RCP exec | Adapter + single chokepoint | Shotgun surgery — each shell-out re-invents OS conditionals |
| `HostFs` | `deleteRecursively` (+ retry), permission probes as capability queries, artifact globs | Adapter; capability object | Silent best-effort swallows; POSIX calls that throw |
| `TestHost` (tests only) | fake executables, fake launchers, workspace.json builders | Object Mother for host fixtures | The five test-setup failure families |

**Incomplete-delegation finding (first, per seat rule):** `ProjectImporter`
holds the data (which OS, which wrapper name) and does the logic inline at
1249-1265 while `PathUtilsImpl` owns the sibling concern — OS knowledge and
its logic live apart across modules. Move the logic to the object that owns
the knowledge: the host port.

**Dependency direction (enforceable):** `org.jawata.mcp` → `org.jawata.core` →
`org.jawata.core.host` → JDK. No engine or tool class outside `host` may
instantiate `ProcessBuilder` or call POSIX-only NIO APIs. Enforce in CI with
the repo's own engine: `find_pattern_usages(kind=instantiation,
query=java.lang.ProcessBuilder)` must return only `host/*` + `TestHost` sites.

**What must NOT be touched:** the JDT interaction and refactoring semantics
(the proven-correct part) · the parity goldens' content and the byte-exact
comparison (fix the representation at checkout, never loosen the comparison) ·
`resolveMavenCommand`'s discovery order (correct, Windows-aware, a past-bug
fix) · the assumption-skip mechanism (the honest shape for host-absent
capability).

## 4. What the three-OS CI matrix should run

**The full suite on all three OSes — not a subset.** The owner's hypothesis
predicted failures only in process-launch and path modules; the actual reds
were dominated by a lane the hypothesis didn't contain (text representation)
and were caught **only because the full battery ran** — the parity tests are
nominally "pure engine" tests, exactly the ones a subset-matrix would have
excluded on Windows. This repo's own history (features shipped unwired past
green suites) argues the same way: claims of OS-agnosticism stay true only
under full exercise.

Two amendments:
1. **Abort budget per OS** — the job asserts `aborted <= expected(os)` against
   a committed per-OS list, so a newly-appearing skip fails the job instead of
   silently shrinking coverage.
2. **Un-skip Gradle on Windows** once TestHost lands; supply the embedding
   corpus or accept those 2 as permanent-and-listed.

macOS's single failure homes to the same work: the debugger adapter there is
`lldb`, and Mach-O symbols carry a leading underscore — an adapter-level fact.

## 5. The cheapest ordered sequence to an honest Windows 0

Each step fixes a cause; none weakens an assertion or skips a test. Expected
red-count after each step in brackets.

1. **Pin bytes at checkout** — `.gitattributes`: `* text=auto eol=lf` plus
   `*.cmd text eol=crlf`, then `git add --renormalize .`. The byte-exact parity
   contract holds because the INPUTS are identical, not because the comparison
   got softer. [32→13]
2. **hs_err glob** — also require `.endsWith(".log")`. [13→7]
3. **TestHost.fakeExecutable** — `.cmd` on Windows, shebang elsewhere; use in
   ProjectImporterTest, the NativeTriage failing-adapter, the RCP fake launcher
   (replace `setPosixFilePermissions` with `File.setExecutable`). [7→2]
4. **workspace.json via Jackson** — never string-concatenated paths. [2→1]
5. **JFR teardown (product)** — bounded retry in `deleteRecursively`; check the
   `waitFor` result before deleting. [1→0]
6. **Then re-verify** the NativeTriage adapter-words assertion under the
   portable fixture — surfaced only now that the test can reach it.

Product-polish (not needed for green, needed for CRLF *user* projects):
7. `DiffRenderer.splitLines` → split on `\R` so diff content lines are
EOL-clean regardless of the target project's line endings.

## Below the fold

- **Watcher hardening (latent, cross-platform):** `reconcileFromDisk` records
  mtime in `finally` even when the parse failed — one bad read permanently
  disarms the 2s fallback. Record mtime only on success.
- **Manager-side check (jawata-studio):** does it write `workspace.json` with a
  real JSON serializer? The same backslash trap exists for any hand-templated
  writer on Windows.
- **`HostFs.deleteRecursively` telemetry:** report residue count instead of
  swallowing — an empty result on failure is a lie applies to cleanup too.
- **Forbidden-edge CI gate:** run the ProcessBuilder pattern-usage check in the
  workflow once the host package lands.
- **macOS NativeTriage:** gdb/lldb/cdb adapter abstraction inside
  `HostProcesses`; Mach-O underscore handling in the symbol correlator.
- **Migration is incremental** — one seam at a time; no flag-day.

## What I could and could not verify

**Verified (with the deriving command):** all 32+7 identities (`grep` on the
log, cross-checked against the SPIKE-TESTS line) · parity mechanics
(ParitySupport.java, DiffRenderer.java:55) · EOL storage state
(`git ls-files --eol`: all `i/lf`, zero attrs) · the Maven seam
(ProjectImporter.java:1249-1265 vs the POSIX fake in the test) · the watcher's
`finally` mtime record · JFR teardown ordering · the NativeTriage glob and the
`.mdmp` sibling · the RCP POSIX fixture.

**Could not verify:** the runner's `core.autocrlf` (inferred from the Git for
Windows default; the fix is correct regardless of which side introduced the
`\r`) · exact byte offsets of divergence · the precise Windows file-hold
mechanism (retry is robust to either) · the macOS root cause (log not
provided; classified by feature shape) · no Windows run was executed after any
proposed change — the per-step red counts are derived, not measured.
