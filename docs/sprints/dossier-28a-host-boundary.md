# Dossier — jawata refactors jawata: the host boundary

> Sprint 28a, stage 1b. This is a DELIVERABLE, not a by-product: Sprint 29
> writes its marketing from this file without re-deriving anything. Every
> number below carries the call that produced it. Every step names the jawata
> tool that performed it, the gate that held it, and the undo handle that would
> have reverted it.

**Baseline design:** `docs/architect/ARCHITECTURE-mcp-host-boundary.md`
(committed `867ecb9`, signed off by Harald 2026-08-14).

---

## Why this refactoring exists — the failure that forced it

Not a tidiness exercise. Two cost events, both measured:

1. **jawata-studio shipped eight releases in one day** (v3.7.8 → v3.7.16,
   2026-08-13) each fixing a different symptom of one design flaw: platform
   knowledge scattered across call sites with no owning boundary. Every one of
   those defects was found by a human reading a folder listing on Windows —
   none by a test — because `cfg!(windows)` is a compile-time constant, which
   makes the Windows branch *unrepresentable* in a Linux test run. 470 green
   tests proved nothing about the code that was broken.

2. **jawata-mcp's three-OS CI matrix ran for the first time** (2026-08-12; it
   had existed for months behind a `pull_request` trigger with no pull
   requests) and produced 39 non-green outcomes, of which a fresh-context
   architect classified 2 as product defects and 37 as test-setup causes — with
   the dominant single cause, line endings at git checkout, in nobody's
   prediction.

The engine itself is *proven* OS-agnostic: 19 refactoring-parity outputs were
character-identical on Windows once the checkout stopped corrupting them. What
was not agnostic was the code around it, and the tests underneath it.

**The design answer is one boundary, not a pattern per finding** —
`org.jawata.core.host`, an anti-corruption leaf package every host contact
crosses in both directions, with the test suite crossing the same boundary
through the same package's kit.

---

## Step ledger

| Step | Tool | Gate result | Commit |
|---|---|---|---|
| M0 | repo config (`.gitattributes`) | shipped in stage 1a; zero content churn | (1a) |
| M1 | `Write` ×2 + `Edit` (declared window) | compile 0/0; HostOSTest **6/6** | `b10c92b` |
| M2 | `move(kind=class)` ×2, staged | compile 0/0; 4 classes **53/53** | `0e48064` |
| M3 | `rename_symbol` ×3, staged | compile 0/0; 4 classes **53/53** | `9d3b939` |
| M4 | `inline(kind=method)` + 2 delegations | compile 0/0; 4 classes **69/69** | `b976cab` |
| M5 | 3 provenance rewrites | compile 0/0; **full suite** (below) | pending |

### M0 — pin bytes at checkout
`* text=auto eol=lf`, `*.cmd text eol=crlf`. Shipped in 1a; the 19 parity reds
cleared on the next Windows matrix run (31808099077), which is the prediction
being verified, not an assumption.

### M1 — `HostOS`: the OS as a value

The point of the seam is `of(String)`: it takes `os.name` **as an argument**,
so every branch is reachable from every runner. `current()` is the single place
that reads the live JVM.

**It found a bug in the code it was replacing, on its first run.** The
predicate at both production sites was
`System.getProperty("os.name").toLowerCase().contains("win")` — and
**`"darwin"` contains `"win"`**. `HostOSTest` classified Darwin as WINDOWS
immediately:

```
=> org.opentest4j.AssertionFailedError: expected: <MACOS> but was: <WINDOWS>
```

The bug never fired in production because the JVM reports `Mac OS X` rather
than `Darwin` on macOS. It had sat in two methods that no test could reach for
the branch that mattered. One reachable branch found it in seconds. Mac is now
tested first; since no `os.name` a JVM reports for Windows contains `mac` or
`darwin`, M4 remained a move rather than a behaviour change.

*This is the whole argument of the sprint, in one test: the defect was not hard
to find. It was impossible to look at.*

### M2 — the proven path seam moves in

`IPathUtils`/`PathUtilsImpl` were already the anti-corruption layer for paths —
the one place that knew about Windows 8.3 short names and macOS `/var`
symlinks. They lived in the wrong package to say so.

Two `move(kind=class)` calls, both **staged (`auto_apply=false`) and read
before applying** — the store carried a hazard that a class move once rewrote
`@link` imports in both the moved and the referencing files. Nine files
touched, import rewrites only, no `@link` damage.

Consumer set derived, not recalled: `find_references(symbol=IPathUtils)` = 9
references.

### M3 — the seam takes the name the design gave it

`IPathUtils` → `HostPaths` (the port), `PathUtilsImpl` → `HostPathsImpl` (the
adapter), matching `HostOS` and the seams M6/M9/M10 still owe. 20 edits over 11
files across three `rename_symbol` calls — including
`{@link IPathUtils#formatPath}` inside a javadoc paragraph, which a
find-and-replace over imports would have left behind.

**A real defect in jawata surfaced here** and is recorded (store
`e9c5a3db`): `move(kind=class)` on a **test** class writes it into the
**production** source root when the target package already exists in the
production module. The file lands in the shipped OSGi bundle with a JUnit
dependency, and — worse — disappears from the test-bundle jar the suite scans,
so it silently stops running while still existing on disk. Reverted with
`refactoring(action=undo, undoChangeId=7aff3020…)`; both roots verified by
listing. `HostPathsImplTest` therefore keeps package `org.jawata.core` until
the tool respects test roots.

### M4 — one reader of `os.name`

`HostPathsImpl#isWindows` had exactly **one** consumer —
`find_references` = 1, the test — so it became a one-line delegate and
`inline(kind=method)` deleted it, rewriting the call site to
`HostOS.current().isWindows()` **and adding the import**.
`ProjectImporter#isWindows` (private) now delegates.

Census moved as the design predicted: production `os.name` reads **5 → 3**.

### M5 — provenance reads, byte-identical

`CoverageService#finalizeArtifact` (85) and `#importArtifact` (211) write
`m.environment` into stored coverage manifests — an external contract read back
by later runs — so `HostOS.description()` reproduces
`osName() + "/" + osArch()` exactly. `HealthCheckTool` (200) uses
`HostOS.osName()`/`osArch()`.

**Invariant §3.2 reached.** `find_string_literals(query="os.name")` returns 13
rows, of which exactly **one** is production:

```
org.jawata.core/src/org/jawata/core/host/HostOS.java:81
```

Every other row is a test. The engine has one reader of the operating system.

---

## What jawata's own tools did, and what they could not

**Did the work:** two class moves, three renames, one method inline — each
staged, diffed, applied, compile-verified, with an undo handle. The renames
rewrote a javadoc `@link`; the inline rewrote a call site *and* added its
import. None of that survives a text edit.

**Could not:** create a class (no refactoring kind creates one), or reorder two
statements inside a method body. Those steps are declared `[additive]` or
authored inside a narrow, logged `jawata-author:` window naming the file and
the edit kind — which is the honest shape, not a loophole. The guard **blocked
the first attempt** at the `HostOS` body edit and made the declaration
mandatory; that refusal is in the transcript.

---

## Open items carried forward

- **M6–M13** per the design: `HostProcesses` and the 11 production launch
  sites; `HostFs` (which kills `ForkedTestRunner#deleteRecursively`, the
  silent-swallow duplicate of the proven bounded-retry implementation);
  `HostText` + the CRLF diff fix; the `TestHost` kit with java-launcher fakes;
  a deterministic prune clock; `HostBoundaryRulesTest` + the per-OS abort
  budget.
- **The `move` test-root defect** (`e9c5a3db`) needs an issue filed.
