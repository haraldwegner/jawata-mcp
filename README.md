# GOJA — IDE-grade Java code intelligence for AI agents

[![GitHub release](https://img.shields.io/github/v/release/haraldwegner/goja-mcp)](https://github.com/haraldwegner/goja-mcp/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

**85 MCP tools driving Eclipse JDT for compiler-accurate analysis, navigation, and refactoring on real-world Java workspaces.** Multi-project workspaces, auto-applying refactorings with one-call undo, code generation, dependency management (Maven + Gradle), workspace-wide verification, and duplicate-code detection + removal — the things a human gets in Eclipse or IntelliJ, packaged for AI coding agents over MCP.

Battle-tested daily on multi-project codebases via the companion **[goja-studio](https://github.com/haraldwegner/goja-studio)** desktop control plane. Improvement loop is live: features, fixes, and ergonomics ship in response to refactoring sessions in production. See the [release notes](docs/release-notes/v1.0.0.md) for the v1.0 launch.

```bash
# Linux — installs the manager (which pulls GOJA automatically)
curl -sSL https://raw.githubusercontent.com/haraldwegner/goja-studio/main/install.sh | bash
```

---

## Why Java, why now — the renaissance

For twenty years languages trended terse — Python, then Kotlin, Scala, Go — because the cost that mattered was *human* authoring. The agent era moves that cost: the agent writes the ceremony tirelessly, and GOJA reads the codebase through the compiler instead of your eyes. The verbosity tax is now paid by something that never tires — and the comprehension dividend is still yours. In the agent era **you are the auditor, not the author**, and auditing favours explicit over clever; that's why explicit, statically-typed Java beats terse Kotlin/Scala (harder to audit) and dynamic Python (no compiler oracle). The types aren't ceremony — **they're the substrate GOJA stands on**: you can't compiler-accurately refactor, prove behaviour-preservation, or detect a smell on code you can't statically type. **GOJA is what flips Java's verbosity from liability to asset** — letting an agent *fully engineer* Java, not merely generate it.

## Companion: goja-studio

The manager is the recommended driver. It's a Tauri desktop app that:

- Manages named workspaces of Java projects with a workspace-first UI.
- Writes `workspace.json` for the file-watcher and live-reconciles add/remove.
- Deploys MCP server entries into Cursor / Claude Desktop / Antigravity / IntelliJ-style configs.
- Polls for fork releases and downloads the latest jar automatically.
- Native system-tray + autostart-on-boot, with session restoration so workspaces resume where you left off.

Linux x86_64 / aarch64 + macOS Apple Silicon. Direct MCP-client config without the manager is in the [Installation](#installation) section below.

---

## Built for AI agents

GOJA exists because **AI coding assistants need compiler-accurate insights that text search cannot provide**. When an AI uses `grep` or `Read` to find usages of a method, it cannot distinguish:

- A method call from a method with the same name in an unrelated class
- A field read from a field write
- An interface implementation from an unrelated class
- A cast to a type from other references to that type
- An annotation usage from any other reference to the annotation type

The result is incorrect refactorings, missed usages, and incomplete understanding of code behavior.

GOJA drives Eclipse JDT — the same engine that powers Eclipse IDE — so the agent gets:

- Type resolution across inheritance hierarchies
- Method overloading and overriding
- Generic type arguments
- Import and classpath dependency resolution
- Cross-bundle navigation (PDE / OSGi `Require-Bundle`)
- LTK-backed refactorings (since v1.5.1)

**Example.** Finding all places where `UserService.save()` is called:

| Approach | Result |
|---|---|
| `grep "save("` | Returns 47 matches including `orderService.save()`, `saveButton`, comments |
| `find_references` (GOJA) | Returns exactly 12 calls to `UserService.save()` |

---

## What GOJA does

### Multi-project workspace (since v1.3)

A *workspace* is a named group of Java projects loaded into one shared GOJA process and exposed as one MCP service (`jl-<workspace-name>`). The agent sees the combined symbol set of every project in the workspace; cross-project navigation, find-references, and refactorings work across the whole group.

- `add_project` / `remove_project` / `list_projects` — agent-callable workspace mutations.
- Workspace-scoped cross-project search by default; optional `projectKey` parameter on every analysis tool to scope down to a single project.

### Live updates via `workspace.json` (since v1.4)

The manager (or any other driver) writes a JSON file at `<data-dir>/workspace.json`:

```json
{ "version": 1, "name": "example", "projects": ["/abs/path/a", "/abs/path/b"] }
```

`WorkspaceFileWatcher` picks up changes through `java.nio.file.WatchService` and reconciles the running process's project list within ~1 s. No process restart, no MCP-client reload, no agent-session refresh.

### Detection-matrix completion (v1.5.0, Sprint 11)

Three correctness gaps in the project-detection layer closed:

| Layout | Source roots | Dependencies |
|---|---|---|
| Regular Maven | `<sourceDirectory>` override + heuristic | `mvn dependency:build-classpath` shell-out |
| **Maven-Tycho** | `.classpath` | **`Require-Bundle` resolved against the workspace bundle pool** (was: broken Maven shell-out) |
| **Pure Eclipse PDE** | `.classpath` | `.classpath kind="lib"` **plus `Require-Bundle`** (was: lib only) |
| **Gradle** | **Tooling API `EclipseProject` model** (was: heuristic) | **Tooling API resolved jars** (was: empty) |

Two PDE bundles loaded into one workspace where bundle A's `Require-Bundle` lists bundle B now have inter-bundle navigation working transparently.

### Tool-surface progression (v1.5.0 — v1.7.0)

Per-workspace tool count: **66 → 55 in v1.5.0 → 60 in v1.5.1 → 62 in v1.6.0 → 73 in v1.7.0 → 75 in v1.8.0 → 79 in v1.9.0 → 81 in v1.10.0 → 83 in v1.11.0 → 85 in v1.12.0**. The v1.5.0 step replaced 13 narrow tools with two parametric ones:

- **`find_pattern_usages(kind, query)`** — `kind ∈ { annotation, instantiation, type_argument, cast, instanceof }`.
- **`find_quality_issue(kind, …)`** — `kind ∈ { naming, bugs, unused, large_classes, circular_deps, reflection, throws, catches }`.

Both declare typed `kind` enums in their schema with per-kind descriptions, so agents discover capabilities through `tools/list` without trial and error. The freed budget under Antigravity's ≈100-tool cap is what made the v1.5.1 refactorings, v1.6.0 verification tools, and v1.7.0 Ring 2/3/4 expansion fit.

### Structural refactorings (v1.5.1)

`org.eclipse.jdt.core.manipulation` and `org.eclipse.ltk.core.refactoring` ship in the target platform; `AbstractRefactoringTool` encapsulates the LTK plumbing — initial / final condition checks, `Change` creation, `PerformChangeOperation`, modified-CU collection. v1.5.1 adds five concrete tools on top:

| Tool | What it does |
|---|---|
| `move_class` | Move a Java type to a different package; rewrites every import and qualified reference workspace-wide. Creates the target package if missing. |
| `move_package` | Rename / relocate a whole package, recursing into every compilation unit. |
| `pull_up` | Move a method or field from a subtype up to its direct supertype; for methods, optionally leave an abstract declaration on the original. |
| `push_down` | Move a method or field from a supertype into all of its direct subtypes. |
| `encapsulate_field` | Generate getter/setter for a field, replace direct accesses, optionally tighten field visibility. |

All accept the inherited optional `projectKey` for workspace-scoped refactorings. On rejection (`REFACTORING_FAILED`), no files are modified. Since v1.9.0 the undo-Change that `PerformChangeOperation` used to discard is captured and returned as `undoChangeId`, and `auto_apply: false` stages the LTK Change for preview-then-commit. See [`docs/release-notes/v1.5.1.md`](docs/release-notes/v1.5.1.md) and [`docs/upgrade-checklist.md`](docs/upgrade-checklist.md) — three of the five tools use `org.eclipse.jdt.internal.corext.*` processor classes; the upgrade checklist documents what to verify on Eclipse target-platform bumps.

### Workspace verification (v1.6.0)

`compile_workspace` runs `IncrementalProjectBuilder` over every loaded project (after `refreshLocal` so the agent's most-recent edits are picked up) and reads `IMarker.PROBLEM` markers — same path Eclipse IDE's Problems view uses. Catches cascading errors in untouched files and project-level errors (manifest, classpath, missing `Require-Bundle`) that per-file AST reconcile (`get_diagnostics`) misses.

`run_tests` launches JUnit 4 / 5 / TestNG via JDT-LTK's launching delegate, headless. Method / class / package scope. Returns parsed pass/fail/skipped counts plus per-failure stack traces with bounded stdout/stderr tail capture. Closes the agent's *refactor → compile → test → fix* loop without shelling out to Maven/Gradle.

See [`docs/release-notes/v1.6.0.md`](docs/release-notes/v1.6.0.md).

### Code generation, dependency management, formatter (v1.7.0)

11 new tools across three rings:

- **Ring 2 (code generation, 6):** `generate_constructor`, `generate_getters_setters`, `generate_equals_hashcode`, `generate_tostring`, `override_methods`, `generate_test_skeleton`. All built via `ASTRewrite` directly — no `org.eclipse.jdt.ui` dependency. Bypasses the small mistakes agents make hand-writing modifiers, generics, annotations, and JavaBean conventions.
- **Ring 3 (build & dependency management, 3 — Maven-only):** `add_dependency`, `update_dependency`, `find_unused_dependencies`. Text-level `pom.xml` mutation preserves user formatting and comments. Gradle/Buildship support is explicitly v1.8.x.
- **Ring 4 (formatter / workflow polish, 2):** `format` (file/package/project/workspace scope, honours the project's own `org.eclipse.jdt.core.prefs`) and `optimize_imports_workspace` (workspace fan-out of import optimisation, idempotent).

See [`docs/release-notes/v1.7.0.md`](docs/release-notes/v1.7.0.md).

### Lifecycle hygiene + agent-trust (v1.8.0)

Theme: nine real-world `docs/bugs.md` entries closed, three high-leverage tools added, dependency management extended to Gradle.

| Area | What landed |
|---|---|
| **`refresh_workspace`** | Consolidated lifecycle tool: refresh resources from disk + invalidate JDT incremental compile cache (`CLEAN_BUILD` + `FULL_BUILD`) + preserve `projectKey` state. The manual override when the file-watcher misses an external edit; closes the lifecycle gap surfaced across every real-world refactor session. |
| **FQN overload for `find_*`** | `find_references` / `find_implementations` / `find_field_writes` / `find_method_references` now accept `symbol="com.foo.Bar"` (type), `"com.foo.Bar#method"` / `"com.foo.Bar#method(int,java.lang.String)"` (method), or `"com.foo.Bar#field"` alongside the existing `(filePath, line, column)` triple. Workspace or project scope. Closes the coordinate-bisection cost for cross-project consumer mapping. |
| **`find_duplicate_code`** | Workspace-scoped clone detection: normalised-token sequence over every `IMethod` body, group by exact match, emit `groups` with per-instance `(filePath, line, methodName, tokenCount, similarity, sourceProject)`. Knobs: `minTokens` (default 50), `crossProject` (default false). Read-only. |
| **Gradle dep management** | `add_dependency` / `update_dependency` / `find_unused_dependencies` now detect Gradle as a fallback when no `pom.xml` is present. Groovy DSL + Kotlin DSL handled; `pom.xml` takes precedence on hybrid projects. Caller runs `refresh_workspace` for classpath sync (Buildship target integration is v1.8.x.x). |
| **`compile_workspace` correctness** | New `clean: bool` param (forces `CLEAN_BUILD` so record / signature shape changes don't false-pass) + new `scope: "main"\|"test"\|"both"` param (default `"both"` — test-source errors no longer silently pass). |
| **`PROJECT_KEY_DROPPED`** | Distinct error code, 5-minute TTL drop marker. Long-lived callers can recover via `list_projects` instead of treating `INVALID_PARAMETER` as a typo. |
| **`rename_symbol` constructor post-pass** | Renaming a type now also rewrites its constructor identifiers (JDT's path missed them). Idempotent. |
| **`move_class` cross-project** | Files physically move on disk via the new `targetProjectKey` param (or auto-detect from the target package); `modifiedFiles` populated via `IResourceChangeListener` (the LTK Change tree is opaque for `ProcessorBasedRefactoring`). |
| **`find_field_writes` graceful degradation** | Near-miss positions return `SUCCESS` with empty `writeLocations` + `nearbyFieldCandidates` (up to 3, ±1 line) instead of `INVALID_PARAMETER`. |
| **`WorkspaceFileWatcher` robustness** | 50 ms debounce + event drain; `OVERFLOW` triggers unconditional reconcile; periodic mtime-fallback poll catches any silent `WatchService` miss within ≤ 2 s. |
| **`ProjectImporter` dedupe** | Classpath entries deduped before `setRawClasspath()` — Tycho hybrid layouts that put the same jar in both `lib/` and `.classpath` load without `setRawClasspath()` rejection. |
| **`run_tests` non-PDE path** | Explicit pre-computed runtime-classpath mementos bypass the JDT JUnit launcher's `PluginClasspathProvider` for plain Maven / Gradle / generic-Java projects. v1.7.1's `INVALID_PARAMETER`-with-workaround dispatch is removed. |

See [`docs/release-notes/v1.8.0.md`](docs/release-notes/v1.8.0.md) for the full per-bug shipped notes + behaviour-change checklist.

---

## Installation

### Prerequisites

- **Java 21+** on `PATH` or `JAVA_HOME`. GOJA runs on JDT 2024-09 and parses Java 1.1–23 source.

### Recommended: goja-studio

For day-to-day use, drive GOJA through **[goja-studio](https://github.com/haraldwegner/goja-studio)** — a Tauri desktop app that:

- Manages named workspaces of Java projects with a workspace-first UI.
- Writes `workspace.json` for the file-watcher.
- Deploys MCP server entries into Cursor / Claude Desktop / Antigravity / IntelliJ-style configs.
- Polls for fork releases and downloads the latest jar automatically.

```bash
# Linux
curl -sSL https://raw.githubusercontent.com/haraldwegner/goja-studio/main/install.sh | bash
```

The manager handles the rest.

### Direct MCP client config (without the manager)

Download a `goja.zip` / `goja.tar.gz` from [Releases](https://github.com/haraldwegner/goja-mcp/releases). Two transport options as of **v1.8.5**:

**HTTP/SSE (default since v1.8.5, recommended)** — one resident JVM serves N MCP clients. Launch the server once and point MCP-client configs at the URL it prints:

```bash
java -jar /path/to/goja/goja.jar -data /path/to/goja-workspaces \
     -port 8765 -token <your-bearer-token>
# Server prints: READY url=http://127.0.0.1:8765 token=<your-bearer-token>
```

```json
{
  "mcpServers": {
    "goja": {
      "url": "http://127.0.0.1:8765",
      "headers": { "Authorization": "Bearer <your-bearer-token>" }
    }
  }
}
```

Multi-client benefit: when 3 Claude windows + Cursor connect to the same URL, they share one fork JVM (~2 GB) instead of each spawning their own private stdio child (which was ~7 × 2 GB = 14 GB+ at v1.8.0).

**Stdio (opt-in fallback for users pinned to v1.8.0 behaviour)** — add `-transport stdio` to the args:

```json
{
  "mcpServers": {
    "goja": {
      "command": "java",
      "args": ["-jar", "/path/to/goja/goja.jar", "-transport", "stdio",
               "-data", "/path/to/goja-workspaces"]
    }
  }
}
```

Both transports expose the same 85 tools through the same JSON-RPC handler — only the wire differs.

Drop a `workspace.json` into `/path/to/goja-workspaces/` to load projects:

```json
{
  "version": 1,
  "name": "my-workspace",
  "projects": ["/abs/path/to/project-a", "/abs/path/to/project-b"]
}
```

The watcher loads them on startup and reconciles edits live. For single-project legacy use, `JAVA_PROJECT_PATH` env var still auto-loads on startup (no `workspace.json` needed).

---

## Tools (85)

### Workspace administration (5)

| Tool | Description |
|---|---|
| `health_check` | Server status, capabilities, workspace summary. |
| `load_project` | Replace the workspace with a single project. |
| `add_project` | Append a project to the workspace. |
| `remove_project` | Drop a project from the workspace. |
| `list_projects` | List loaded projects with their keys. |

### Navigation (10)

`search_symbols`, `go_to_definition`, `find_references`, `find_implementations`, `get_type_hierarchy`, `get_document_symbols`, `get_symbol_info`, `get_type_at_position`, `get_method_at_position`, `get_field_at_position`.

### Search (5 + 2 parametric)

| Tool | Description |
|---|---|
| `find_method_references` | All `Type::method` expressions. |
| `find_field_writes` | Locations where a field is mutated (vs read). |
| `find_tests` | Discover JUnit/TestNG test methods. |
| **`find_pattern_usages(kind, query)`** | Type-anchored pattern search: `annotation` / `instantiation` / `type_argument` / `cast` / `instanceof`. |
| **`find_quality_issue(kind, …)`** | Code-quality analyses: `naming` / `bugs` / `unused` / `large_classes` / `circular_deps` / `reflection` / `throws` / `catches`. |

### Analysis (16)

`get_diagnostics`, `validate_syntax`, `get_call_hierarchy_incoming`, `get_call_hierarchy_outgoing`, `get_hover_info`, `get_javadoc`, `get_signature_help`, `get_enclosing_element`, `analyze_change_impact`, `analyze_data_flow`, `analyze_control_flow`, `get_di_registrations`, `analyze_file`, `analyze_type`, `analyze_method`, `get_type_usage_summary`.

### Refactoring (16 + 4 apply/undo primitives)

**Since v1.9.0 every refactoring auto-applies** and returns
`{ filesModified, diff, undoChangeId, summary }` — the agent's loop is
*refactor → `compile_workspace` / run tests → keep, or `undo_refactoring`*.
Pass `auto_apply: false` to stage instead (`{ changeId, diff }`) and commit later.
The contract is a durable PR gate: [`docs/refactoring-tool-contract.md`](docs/refactoring-tool-contract.md).

**Local:** `rename_symbol`, `organize_imports`, `extract_variable`, `extract_method`, `extract_constant`, `extract_interface`, `inline_variable`, `inline_method`, `change_method_signature`, `convert_anonymous_to_lambda`.

**Structural (LTK-backed, v1.5.1):** `move_class`, `move_package`, `pull_up`, `push_down`, `encapsulate_field`.

**Apply/undo primitives (v1.9.0):** `apply_refactoring`, `undo_refactoring`, `inspect_refactoring`, plus `replace_duplicates` — pass a stable `groupId` from `find_duplicate_code` and every same-type clone delegates to a canonical method, atomically with one undo handle.

**Clean-up (v1.10.0):** `apply_cleanup(kind)` — parametric mechanical clean-ups (`add_final` with binding-checked reassignment detection, `redundant_modifiers` on interface members) via the same apply/undo contract; deliberately non-overlapping with `organize_imports` / `format` / `apply_quick_fix`. `filePath` scopes to one file; omit to sweep the whole project.

### Modernisation (1 parametric, v1.10.0)

| Tool | Description |
|---|---|
| **`find_modernization(kind)`** | Find-only: ranked candidates to adopt a newer Java idiom — `anon_to_lambda` / `switch_to_pattern` / `loop_to_stream` / `optional` / `class_to_record` / `sealed`, plus Lombok-removal `lombok_to_record` / `delombok`. Apply with the matching refactoring tool. |

### Knowledge tools (2 parametric, v1.11.0)

Read-only and **model-free** — they emit facts, evidence, and doclint-correct skeletons; prose and semantic naming are the calling agent's job ([generation boundary](docs/sprints/sprint-future-agent-runner.md)). Both emit the shared `symbol_fact` schema.

| Tool | Description |
|---|---|
| **`analyze_javadocs(kind)`** | `ingest` existing Javadocs → symbol-anchored facts (structured tags HIGH, free-text LOW); `validate` doclint-style (broken refs + missing tags, no getter spam); `generate` a doclint-correct skeleton + evidence (`@param`/`@return`/`@throws` stubs + `prosePlaceholders`; trivial accessors → `skip`). |
| **`analyze_naming(kind)`** | `infer`/`get` shallow-precise conventions per category (type/method/field/constant/package/test) with confidence + examples + exceptions; `suggest(category, intent)` re-cases your intent to the convention (no stem without intent); `check(name, category)` flags violations + a corrected suggestion. |

### Null-safety (2 parametric, v1.12.0)

Recovering Kotlin-style null-safety inside the Java/JDT toolchain. `analyze_nullness` is read-only; `apply_null_annotations` mutates via the apply/undo contract. Both emit the shared `symbol_fact` schema.

| Tool | Description |
|---|---|
| **`analyze_nullness(kind)`** | `detect_style` (project's annotation family); `find_violations` (JDT compiler null analysis — flow + contract); `infer_contracts` (conservative `@NonNull`/`@Nullable` contracts from strong signals, skips framework/generated types); `check` (focused style + violations + contracts for a file/symbol). |
| **`apply_null_annotations(kind)`** | `add` a `@Nullable`/`@NonNull` (+ import) to a return/parameter/field — JSpecify default, public-API guard; `migrate` a nullness family → another (e.g. JetBrains→JSpecify), refusing ambiguous conversions. Apply/undo with byte-exact revert. |

### Verification (4)

| Tool | What it does | Since |
|---|---|---|
| `compile_workspace` | Incremental Java build over every loaded project; refreshes local resources first; aggregates `IMarker` problem markers (compile errors, warnings, project-level errors). v1.8.0 adds `clean: bool` (force `CLEAN_BUILD` for record / signature shape changes) and `scope: "main"\|"test"\|"both"` (default `"both"` — test-source errors no longer silently pass). | v1.6.0 |
| `run_tests` | Launches JUnit 4 / 5 / TestNG via JDT-LTK's launching delegate, headless. Scope is `method` / `class` / `package`. v1.8.0 adds an explicit pre-computed runtime classpath for plain Maven / Gradle / generic-Java projects (bypasses the OSGi-assuming JDT launcher). | v1.6.0 |
| `refresh_workspace` | Consolidated lifecycle tool: `IResource.refreshLocal(DEPTH_INFINITE)` + `CLEAN_BUILD` + `FULL_BUILD` + preserves `projectKey` state. Manual override when the file-watcher misses an external `Write` / `Edit`. Optional `projectKey` scopes to one project. | v1.8.0 |
| `find_duplicate_code` | Workspace-scoped clone detection: normalised-token sequence over every `IMethod` body, group by exact match. Knobs: `minTokens` (default 50), `crossProject` (default `false`). Read-only. | v1.8.0 |

See [`docs/release-notes/v1.6.0.md`](docs/release-notes/v1.6.0.md) and [`docs/release-notes/v1.8.0.md`](docs/release-notes/v1.8.0.md) for the full input/result contracts and known limitations (3 `run_tests` happy-path tests stay `@Disabled` pending the fixture-build pipeline).

### Code generation (6, v1.7.0)

| Tool | What it does |
|---|---|
| `generate_constructor` | Constructor that initialises selected fields. Visibility selectable; optional `super()` chaining. |
| `generate_getters_setters` | Multi-field JavaBean accessors. Boolean fields use `isField()`. Existing accessors are skipped (warning). |
| `generate_equals_hashcode` | `equals(Object)` + `hashCode()` over selected fields. Primitives use `==`, references use `Objects.equals(...)`. Adds `java.util.Objects` import if missing. |
| `generate_tostring` | `toString()` in `STRING_CONCATENATION` (default) or `STRING_BUILDER` style. |
| `override_methods` | Query (returns overridable signatures) or generate mode (`@Override` stubs throwing `UnsupportedOperationException`). |
| `generate_test_skeleton` | Writes a JUnit/TestNG test class adjacent to the source, one `@Test` stub per public method + `setUp()`. |

All Ring 2 tools build via `ASTRewrite` directly — they do **not** require the `org.eclipse.jdt.ui` bundle. See [`docs/release-notes/v1.7.0.md`](docs/release-notes/v1.7.0.md) for the contract.

### Build & dependency management (3, Maven + Gradle)

| Tool | What it does |
|---|---|
| `add_dependency` | Adds a `<dependency>` to `pom.xml`, or a configured-block line (`implementation`, `testImplementation`, …) to `build.gradle` / `build.gradle.kts`. Refuses duplicates. Text-level mutation preserves formatting + comments. |
| `update_dependency` | Bumps the version of an existing dep in place. Maven `<version>` element or Gradle dep coordinate. |
| `find_unused_dependencies` | Read-only: lists deps whose packages don't appear in any source import. Heuristic; treat as suggestions. |

Detection: `pom.xml` (Maven) takes precedence; falls back to `build.gradle` / `build.gradle.kts` (Gradle) when no `pom.xml` is present. Gradle path is text-level via the shared `GradleBuildSupport` helper (since v1.8.0). Buildship target-platform integration is deferred to v1.8.x.x — call `refresh_workspace` after a Gradle dep write to sync the JDT classpath.

### Workflow polish (2, v1.7.0)

| Tool | What it does |
|---|---|
| `format` | JDT formatter at `file` / `package` / `project` / `workspace` scope. Honours the project's own `org.eclipse.jdt.core.prefs`. `dryRun` returns a diff sample. |
| `optimize_imports_workspace` | Workspace fan-out of import optimisation: removes unused imports, sorts the rest. Idempotent. Complements per-file `organize_imports` (Sprint 11). |

### Quick fixes (3)

`suggest_imports`, `get_quick_fixes`, `apply_quick_fix`.

### Metrics (2)

`get_complexity_metrics`, `get_dependency_graph`. (Other quality metrics are exposed as `find_quality_issue` kinds.)

### Project & infrastructure (4)

`get_project_structure`, `get_classpath_info`, `get_type_members`, `get_super_method`.

---

## AI training-bias warning

Models often default to native tools (`grep`, `Read`, generic LSP) over MCP tools, even when semantic analysis gives much better results. Training data is dominated by text-search patterns and the model may not recognise when JDT-driven analysis is correct where text search is wrong.

**Counter the bias** by adding guidance to your agent's project instructions or system prompt:

```markdown
## Code analysis preferences

For Java code analysis, prefer GOJA MCP tools over text search:
- Use find_references instead of grep for finding usages.
- Use find_implementations instead of text search for implementations.
- Use analyze_type to understand a class before modifying it.
- Use the refactoring tools (rename_symbol, extract_method, …) for safe changes.

Semantic analysis from JDT is more accurate than text-based search,
especially for overloaded methods, inheritance, and generic types.
```

---

## Build system support

| System | Detection | Source roots | Dependencies |
|---|---|---|---|
| Maven | `pom.xml` | `<sourceDirectory>` + heuristic | `mvn dependency:build-classpath` shell-out |
| Maven-Tycho | `pom.xml` with `<packaging>eclipse-*</packaging>` | `.classpath` | `Require-Bundle` via workspace bundle pool |
| Pure Eclipse PDE | `MANIFEST.MF` + `.classpath`, no pom | `.classpath` | `.classpath kind="lib"` + `Require-Bundle` pool |
| Gradle | `build.gradle` / `build.gradle.kts` | `EclipseProject` Tooling API | Tooling API resolved jars |
| Bazel | `MODULE.bazel` / `WORKSPACE` | heuristic + BUILD-file scan | `bazel-bin` / `bazel-out` jar walk |
| Plain Java | `src/` directory | `src/`, `src/main/java`, `src/test/java`, … | (none) |

The Gradle Tooling API needs a Gradle distribution at runtime. First call downloads one (~150 MB into `~/.gradle/caches/dists`); subsequent calls use the cache. CI environments without network access can opt out with `-Dgoja.skip.gradle=true`.

---

## How workspaces work (data dir layout)

```
<data-dir>/
├── workspace.json         <- file-watcher's source of truth (manager writes this)
├── .metadata/             <- JDT's index, search caches, builder state
└── goja-<project>-<session-uuid>/   <- linked-folder Eclipse project per loaded source tree
```

The Eclipse projects are **linked folders** pointing at your real source trees — GOJA doesn't copy or modify them. No `.project` / `.classpath` files are added to your source repo.

---

## Configuration

| Env var | Effect | Default |
|---|---|---|
| `JAVA_PROJECT_PATH` | Auto-load this project on startup (legacy single-project mode; ignored when `workspace.json` is present). | (none) |
| `GOJA_TIMEOUT_SECONDS` | Per-operation timeout. | `30` |
| `GOJA_LOG_LEVEL` | `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR`. | `INFO` |
| `JAVA_TOOL_OPTIONS` | JVM options (e.g. `-Xmx2g` for very large workspaces). | (eclipse.ini default 512m) |

---

## Building from source

```bash
git clone https://github.com/haraldwegner/goja-mcp.git
cd goja-mcp
mvn clean verify
```

Distribution archives are written to `org.goja.product/target/products/`. Test counts as of v1.8.0: **122/122** in `org.goja.core.tests`, **510/515** in `org.goja.mcp.tests` (5 `@Disabled`: 1 `EncapsulateField` happy-path from v1.5.2; 3 `run_tests` happy-paths from v1.6.0; 1 `generate_test_skeleton` auto-detect path — see [`docs/upgrade-checklist.md`](docs/upgrade-checklist.md)).

### Build prerequisites

- Java 21+
- Maven 3.9+ (the repo no longer ships a wrapper — use the system `mvn`)
- ~3 GB free disk for the Tycho-resolved p2 cache on first build

### Bumping the Eclipse target platform

When you change the Eclipse release the fork builds against (currently 2024-09), walk through [`docs/upgrade-checklist.md`](docs/upgrade-checklist.md) — it lists the version-sensitive pieces (Sprint 11 Phase E refactorings depend on `org.eclipse.jdt.internal.corext.*`; `gradle-tooling-api` is embedded under `org.goja.core/lib/`; etc.) and the verification commands to run.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  MCP client (Cursor / Claude / Antigravity / IntelliJ / manager)│
└─────────────────────────────────────────────────────────────────┘
                            │ JSON-RPC over stdio
┌─────────────────────────────────────────────────────────────────┐
│  org.goja.mcp                                               │
│    GOJAApplication → ToolRegistry → 79 tools                │
│      • workspace admin · navigation · search · analysis         │
│      • refactoring (15 +4 undo) · verification (4) · codegen (6)│
│      • dep management (3) · workflow polish (2) · quick fixes   │
└─────────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────────┐
│  org.goja.core                                              │
│    JdtServiceImpl  ←→  WorkspaceManager (+ bundle pool)         │
│    SearchService                                                │
│    WorkspaceFileWatcher  ←─── workspace.json (live)             │
│    ProjectImporter (Tycho-aware · Gradle Tooling API · PDE)     │
└─────────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────────┐
│  Eclipse JDT 2024-09 (via OSGi/Equinox)                         │
│    IWorkspace · IJavaProject · SearchEngine · ASTParser · LTK   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Roadmap

Active and future sprint backlogs live under [`docs/sprints/`](docs/sprints/). Highlights:

- **v1.8.0 (Sprint 14, shipped 2026-06-04)** — lifecycle-hygiene bug bundle + `refresh_workspace` consolidated tool + FQN-based `find_*` overloads + `find_duplicate_code` (clone detection) + Gradle path for the Ring 3 dep tools.
- **v1.8.5/v1.8.6 (Sprint 14a, shipped 2026-06-08/09)** — HTTP/SSE as the default transport (one resident JVM serves N clients; stdio stays via `-transport stdio`) + MCP protocolVersion negotiation.
- **v1.9.0 (Sprint 14b, this release)** — refactoring auto-apply + undo across all 21 mutating tools, `replace_duplicates`, `readOnlyHint` annotations, upstream v1.4.2 parity audit.
- **Sprint 15+** — modernisation sweeps (`var`/records/sealed/pattern-switch) + Cursor-feedback DX block, then under the GOJA brand: Fowler smell detection (~18 tools, the biggest single gap), multi-step orchestration (generalising v1.9.0's apply primitives into plan-level transactions), Kerievsky pattern transformations, SOLID violation detection.

The improvement direction is set by live refactoring sessions on real-world Java workspaces, not roadmap-deck speculation. If a tool surfaces friction repeatedly, it gets a sprint.

---

## Heritage

This project started in 2025 as a fork of [pzalutski-pixel/javalens-mcp](https://github.com/pzalutski-pixel/javalens-mcp). The original v1.0–v1.2 work — Eclipse-JDT integration layer, the initial tool surface, the OSGi/Equinox plumbing — is by Peter Zalutski and remains the load-bearing foundation.

From v1.3.0 onward the fork has diverged substantially across nine numbered sprints. Per-workspace tool count progression: **upstream v1.2 baseline → 66 → 55 (Sprint 11 parametric consolidation) → 60 → 62 → 73 (Sprint 13) → 75 (Sprint 14, v1.8.0)**. Major themes added by the fork:

- **Multi-project workspaces** (v1.3.0, Sprint 9) — one goja process serves many sibling projects with workspace-scoped cross-project search.
- **Live workspace.json reconciliation** (v1.4.0, Sprint 10) — `WorkspaceFileWatcher`.
- **Detection-matrix completion + LTK structural refactorings** (v1.5.x, Sprint 11) — Tycho-aware Maven, workspace bundle pool for PDE, Gradle Tooling API, plus five LTK structural refactorings (`move_class`, `move_package`, `pull_up`, `push_down`, `encapsulate_field`).
- **Workspace verification** (v1.6.0, Sprint 12) — `compile_workspace` + `run_tests`.
- **Code generation + dependency management + workflow polish** (v1.7.0, Sprint 13) — 11 new tools across three rings (Ring 2 codegen, Ring 3 Maven dep mgmt, Ring 4 polish).
- **Companion manager + lifecycle hygiene** (Sprint 14, v1.8.0 shipped 2026-06-04) — see Roadmap.

See [`docs/release-notes/`](docs/release-notes/) for per-release detail.

---

## License

**AGPL-3.0** — see [`LICENSE`](LICENSE). GOJA began as a fork of the MIT-licensed javalens-mcp by Peter Zalutski; the retained MIT base is credited in [`NOTICE`](NOTICE), and the combined work is distributed under AGPL-3.0. Contributions are accepted under the [Contributor License Agreement](CLA.md) — see [`CONTRIBUTING.md`](CONTRIBUTING.md). For commercial-licensing enquiries, contact the maintainer.
