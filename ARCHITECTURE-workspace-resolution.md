# ARCHITECTURE — workspace dependency resolution (design mode, 2026-08-19)

**Scope:** the import/classpath layer of `org.jawata.core` — how a loaded workspace's
projects get their dependencies resolved. **Baseline defect:** 1229 compile errors on a
live 29-project workspace, 1215 of them produced by the resolution layer, not by user
code. **This artifact is the target the watch mode diffs against.**

## The rulings this design is bound by (Harald, 2026-08-19)

1. **No Eclipse installation is required.** The Tycho build's own materialisation —
   `~/.m2/repository/p2/osgi/bundle` — is the platform source. There is no valid
   `.target` file; the Tycho pom is the only authority, and it resolves and downloads
   what is needed.
2. **`Bundle-ClassPath` is core product surface**, not a peculiarity — it is the
   documented OSGi mechanism for carrying non-OSGi jars, forced whenever the target
   platform is p2-only, and industrial RCP uses it (bnd, AEM, Jahia, CS-Studio).
3. **The workspace is a CONTAINER FOR BUILD UNITS, grouped by business domain and
   knowledge.** It can be one build unit; it need not be; it is always a container of
   them. Every member is a FULL build unit that compiles on its own terms — falcon and
   polygonloader are complete projects present by topic, not snippet piles. The core
   idea is multiple complete build units in one workspace with cross-visibility across
   all of them. No membership or coupling may be inferred; the boundary is drawn by the
   user. (Corrected by Harald 2026-08-19 from an earlier "grouping, not a build unit"
   wording.)
4. **jawata's classpath model is dev-time and workspace-wide, deliberately looser than
   any build's.** Its job is comprehension of the codebase, not correctness of an
   artifact. Cross-boundary resolution (the PDE-nature-on-a-Maven-module pattern) is a
   feature.
5. **Unresolved-dependency reporting stays per project and quiet unless asked** — never
   a workspace health verdict. A reference project with no dependencies is not deficient.
6. **One bundle pool per workspace** (= per resident, = per business domain). jawata's
   workspace resolves against jawata's build; the trading workspace against JATS's.

## The measured causes (all three are one defect)

| Symptom | Measured evidence |
|---|---|
| A project cannot see workspace siblings | clicktrader: 431 errors, every sampled one `The import com.jats2.model cannot be resolved` — both of its `Require-Bundle` targets are LOADED workspace projects, and its classpath has zero project entries, while clicktrader.ui (loaded later) resolved its sibling fine |
| Platform bundles do not resolve | 784 errors across three UI plug-ins requiring `org.eclipse.ui`/e4/EMF; the pool searches `~/.p2/pool/plugins` (absent on this machine) and jawata's own headless dist (no UI bundles), while 673+838 platform jars sit unindexed in the Tycho cache |
| Lib-container bundles contribute nothing | `com.jats2.libs`: no source, no output, ~26 jars via `Bundle-ClassPath` — jawata never reads the header, so jfreechart/quickfixj/TwsApi types stay unresolved even where the project entry exists |

**The one defect: there is no resolve phase.** Each project is resolved individually,
during its own import, against whatever is registered *so far* — the thing OSGi's own
design specifically avoids (Equinox installs all bundles, then resolves the complete
graph; boot order affects activation, never resolution). Order-dependence, the global
flat pool, and the unread `Bundle-ClassPath` are all consequences of resolving inside a
single sequential pass.

The prior plan's target — "extract one resolver per build system" — reorganises that
pass without replacing it, which is why its own success measure could not pass.

## The target: one pipeline — Inventory → Resolve → Apply

```
        IMPORT (per project, order-free)          WORKSPACE RESOLVE (once)
┌────────────────────────────────┐      ┌────────────────────────────────────────┐
│ parse MANIFEST → BundleFacts   │      │ BundleInventory                        │
│  symbolic name · version ·     │─────►│  every workspace bundle's facts        │
│  Require-Bundle · Import-      │ all  │  + external pools, indexed once:       │
│  Package · Export-Package ·    │ facts│    1. -Djawata.bundle.pools (explicit) │
│  Fragment-Host · Bundle-       │      │    2. ~/.m2/repository/p2/osgi/bundle  │
│  ClassPath                     │      │       (NESTED name/version/jar walk)   │
└────────────────────────────────┘      │    3. jawata dist bundles (fallback)   │
                                        └───────────────────┬────────────────────┘
   non-PDE projects (Maven/Gradle/                          │ complete picture
   Bazel/plain) BYPASS the pipeline —                       ▼
   their jar resolution is untouched    ┌────────────────────────────────────────┐
                                        │ PlatformResolver            (PURE)     │
                                        │  Require-Bundle → provider             │
                                        │   (workspace project wins over jar)    │
                                        │  Import-Package → exporter             │
                                        │  re-export closure (visibility:=       │
                                        │   reexport walked as a graph)          │
                                        │  fragments: current os/ws/arch only,   │
                                        │   attached to their host               │
                                        │  version = newest satisfying the       │
                                        │   declared floor                       │
                                        │  cycles allowed (dev-time model)       │
                                        │  → Wiring per bundle + Unresolved list │
                                        └───────────────────┬────────────────────┘
                                                            ▼
                                        ┌────────────────────────────────────────┐
                                        │ ClasspathApplier         (JDT, edge)   │
                                        │  wire→entry: project refs · exported   │
                                        │  library jars · Bundle-ClassPath       │
                                        │  nested jars as file entries           │
                                        │  setRawClasspath per project           │
                                        └────────────────────────────────────────┘
```

**Pattern per seam, and the smell each prevents:**

- `BundleInventory` — *repository over facts, filled before anyone reads it.* Prevents
  the order-dependence smell that produced clicktrader's 431: no consumer can observe a
  half-filled world.
- `PlatformResolver` — *pure function over the complete inventory.* Prevents the
  accumulator smell this codebase's design alarm just fired on (verdict and presentation
  computed in one mutable pass): the resolver decides, the applier renders, and neither
  can reach into the other. It is an **interface**: the first implementation is a
  deterministic graph walk; an Equinox-backed implementation can replace it behind the
  same seam if the walk's fidelity proves insufficient (named risk below).
- `ClasspathApplier` — *presenter at the JDT edge.* All side effects in one place;
  everything above it is testable without a workspace.

## Decisions inside the target

| # | Decision | Why |
|---|---|---|
| D1 | **Own graph walk first; Equinox resolver behind the seam, evidence-gated** | Dev-time comprehension does not need uses-constraint or singleton arbitration; driving Equinox's resolver over a foreign bundle set from inside a running Equinox is a framework-in-framework risk that only shows on someone else's machine. The seam keeps the upgrade path honest |
| D2 | **Version policy: newest satisfying the declared floor** | Measured: the manifests declare floors (`bundle-version="1.0.0"`) or nothing — never ranges. Newest-wins is a legal reading of what the projects ask for, needs no train knowledge, and the cache records no provenance to do better with |
| D3 | **Fragments are first-class, filtered to the CURRENT platform** — the cache holds gtk, win32 and cocoa fragments side by side (the pom builds three environments); the resolver takes only the os/ws/arch of the machine it runs on, derived from the JVM, never from a pom | `org.eclipse.swt` is a nearly-empty host; the classes live in `org.eclipse.swt.gtk.linux.x86_64` (a fragment). `com.jats2.model`'s two errors — `Composite … indirectly referenced from javafx.embed.swt.FXCanvas` — are exactly this. Indexing `Fragment-Host` and attaching fragment jars to the host's contribution is what closes `#11`, which stops being a separate issue |
| D4 | **`Bundle-ClassPath` for workspace projects: nested jars become direct file entries** | The jars are ordinary files on disk — JDT references them directly. Jar-inside-a-packaged-jar stays honestly unresolved with a reason (JDT cannot read it); that case does not occur in a source workspace |
| D5 | **Circular project references: allowed (JDT severity → warning in the synthesized workspace)** | A resolve phase makes cycles resolvable; the dev-time comprehension model has no reason to refuse what Eclipse itself makes configurable |
| D6 | **`add_project` / `remove_project` re-run Resolve+Apply for the workspace** | Order-freedom's price: the world changed, so the wiring must be recomputed. Inventory is cached per project; resolve is in-memory over facts; apply rewrites only classpaths whose wiring changed. The previous behaviour — silently order-dependent — is the thing being removed |
| D7 | **Pool indexed once per workspace, lazily, cached by directory mtime** | The Tycho cache is ~1500 jars; the existing `DIR_CACHE` mtime discipline extends to the nested walk. Cost is paid once per load, not per project |
| D8 | **Reporting unchanged** | `UnresolvedRequirement` per project (Stage 8 contract), surfaced by `inspect(kind=classpath)` and counted in `health_check`; `healthy` untouched; nothing workspace-level, nothing unprompted |

## What must NOT be touched

- The `ImportResult` / `UnresolvedRequirement` / `unresolvedDependencyCount` contract
  (Stage 8) — the studio consumes it (`resolution_status`, Stage 9). The resolver FILLS
  it with better answers; the shape stays.
- `healthy` semantics (refactoring guard) — pinned by test, ruled at C10.
- `addSourceEntries`, compliance-level application, and the Maven/Gradle/Bazel jar
  paths — non-PDE projects bypass the pipeline entirely.
- The per-workspace resident model — the pool's scope rides on it.

## Consumers and producers (rule 5)

**Producer:** `ProjectImporter.configureJavaProject` (sole production caller:
`JdtServiceImpl` at project registration; 9 test call sites — derived by
`find_references`, callers-of-symbol kind). **Consumers of the changed behaviour:** JDT
itself (classpaths), and the Stage-8/9 reporting chain (`inspect(kind=classpath)`,
`health_check`, studio `resolution_status`) — response-payload clients, shape unchanged.
**Outside this workspace I cannot see:** none known — Stage 8 recorded "consumers are MCP
clients and the studio, both ours; JATS is the test case, not a consumer". If that has
changed, this enumeration is incomplete by construction.

## Migration — ordered, parity-gated, each step reversible

| # | Step | Gate (red first where marked) |
|---|---|---|
| 0 | **Fixtures**: two PDE bundles imported in REVERSE dependency order; a lib-container bundle (`Bundle-ClassPath` with 2 nested jars); a fake nested pool dir with a host+fragment pair. `loadWorkspaceCopy` exists only in the mcp test helper — add the core-side equivalent per the old plan's note | fixtures load; the three assertions are **RED** against current code |
| 1 | **Extract `BundleFacts` parsing** from `ProjectImporter` (pure manifest read, no behaviour change). This BEGINS the CC-36 decomposition the old Stages 11–13 wanted — the method dissolves into the pipeline instead of into per-build-system copies | `compile_workspace` 0/0; suite green; parity — no classpath diff on the existing fixtures |
| 2 | **Two-phase import**: register + inventory every project, then configure each against the complete inventory | reverse-order fixture GREEN; clicktrader-shape recovered |
| 3 | **Nested pool indexing** (Tycho cache walk, `Fragment-Host`, floor policy) in `ExternalBundlePool` | host+fragment fixture GREEN; SWT resolvable in a unit test |
| 4 | **`Bundle-ClassPath` in the applier** | lib-container fixture GREEN |
| 5 | **Re-export closure** in the resolver | transitive fixture GREEN; then the LIVE measure: `#11`'s `FXCanvas→Composite` error gone on the orb workspace |
| 6 | *(separate, evidence-gated, not committed)* Equinox-resolver spike behind the `PlatformResolver` seam | adopt/reject on measured fidelity difference only |

**The end measure (replaces C11–C13's):** re-run `compile_workspace` on the live
29-project workspace. Expected mechanics, stated honestly rather than promised: the 431
(sibling wiring) and the 784 (platform from the Tycho cache) collapse to their real
residual; every remaining miss appears as an `UnresolvedRequirement` row naming what and
why. The total is re-measured and recorded against the 1229 baseline. A residual is
acceptable **only if it is listed** — silence about a miss is the defect this whole line
of work exists to end.

## End-state test surface (D-THREE)

- **Environment-independent, run anywhere:** `PlatformResolver` — pure over
  `BundleFacts`; ordering, floors, re-export closure, fragments, cycles and the
  unresolved list are plain unit tests with no JDT, no filesystem, no workspace.
- **Owned by the boundary:** `BundleInventory`'s pool indexing (fixture directories,
  mtime cache) and `ClasspathApplier` (the existing workspace-copy integration tests) —
  each tested against its own contract.
- **Only reality can verify:** that the live 29-project workspace's error count falls to
  its honest residual — a dogfood measurement on the orb resident, recorded in the plan,
  not a suite.

## Named risks

- **Graph-walk fidelity** (D1): a hand-rolled resolver can diverge from OSGi semantics
  in corners (split packages, `uses` conflicts). Bounded by: dev-time model tolerates
  looseness by design; the seam allows replacement; divergence surfaces as wrong
  navigation, not silent data loss.
- **Newest-satisfying-floor** picks a newer platform than the build compiled against
  when multiple trains share the cache. Accepted at C10: legal under the declared
  manifests; revisit only if it produces measurable wrong answers.
- **Re-resolve cost on `add_project`** (D6): the price of order-freedom. Bounded by
  caching; if it measures slow on 29 projects, the applier's "only rewrite changed
  classpaths" is the lever.
