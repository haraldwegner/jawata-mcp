# Sprint 28e / D5 — the swallow-site enumeration

Generated 2026-09-08 over `org.jawata.core/src` and `org.jawata.mcp/src` (production
sources only; test sources are out of D5's scope). Regenerate with the sweep recorded at
the foot of this file.

## Why this file exists rather than a number in the plan

C8's exit is that *changed sites plus written exceptions EQUAL the enumerated population*.
An equality is only as good as the population, and the population was wrong once already:
the first pass reported **3** `Files.walk` sites because it queried one of the two
overloads under the bare method name. Both overloads together resolve to 12, which the
text sweep matches site for site. The two instruments agree; the first enumeration did not
follow the plan's own "one query per overload" rule.

## Population

| family | production sites |
|---|---|
| `Files.walk(` | 12 |
| `Files.walkFileTree(` | 2 |
| `Files.list(` | 15 |
| `Files.readString(` | 16 |

Adjacent families the spec does not name, counted so a later reader knows they were
looked at and deliberately left out of D5's scope:
`Files.readAllLines(` 6,
`Files.lines(` 1.

## The sites

### `Files.walk(`

- `org.jawata.core/src/org/jawata/core/host/HostFs.java:51`
- `org.jawata.core/src/org/jawata/core/host/HostFs.java:72`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:1333`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:1453`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:1598`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:1620`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:1638`
- `org.jawata.mcp/src/org/jawata/mcp/coverage/CoverageService.java:261`
- `org.jawata.mcp/src/org/jawata/mcp/coverage/CoverageStore.java:118`
- `org.jawata.mcp/src/org/jawata/mcp/runtime/RuntimeArtifactStore.java:169`
- `org.jawata.mcp/src/org/jawata/mcp/runtime/RuntimeArtifactStore.java:189`
- `org.jawata.mcp/src/org/jawata/mcp/knowledge/ExperienceMaintenance.java:194`

### `Files.walkFileTree(`

- `org.jawata.core/src/org/jawata/core/workspace/DiskSyncGuard.java:173`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:1693`

### `Files.list(`

- `org.jawata.core/src/org/jawata/core/host/HostFs.java:134`
- `org.jawata.core/src/org/jawata/core/project/ExternalBundlePool.java:205`
- `org.jawata.core/src/org/jawata/core/project/ExternalBundlePool.java:210`
- `org.jawata.core/src/org/jawata/core/project/ExternalBundlePool.java:212`
- `org.jawata.mcp/src/org/jawata/mcp/coverage/CoverageStore.java:92`
- `org.jawata.mcp/src/org/jawata/mcp/execution/RunnerClasspath.java:213`
- `org.jawata.mcp/src/org/jawata/mcp/JawataApplication.java:842`
- `org.jawata.mcp/src/org/jawata/mcp/coverage/CoverageService.java:174`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:1008`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:1867`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:2076`
- `org.jawata.mcp/src/org/jawata/mcp/runtime/RuntimeArtifactStore.java:124`
- `org.jawata.mcp/src/org/jawata/mcp/runtime/RuntimeArtifactStore.java:238`
- `org.jawata.mcp/src/org/jawata/mcp/knowledge/H2ExperienceStore.java:1781`
- `org.jawata.mcp/src/org/jawata/mcp/knowledge/ExperienceMaintenance.java:194`

### `Files.readString(`

- `org.jawata.mcp/src/org/jawata/mcp/JawataApplication.java:875`
- `org.jawata.mcp/src/org/jawata/mcp/JawataApplication.java:900`
- `org.jawata.mcp/src/org/jawata/mcp/transport/ResolvedToken.java:128`
- `org.jawata.core/src/org/jawata/core/project/ProjectImporter.java:1459`
- `org.jawata.mcp/src/org/jawata/mcp/tools/ReplaceConstructorWithFactoryTool.java:180`
- `org.jawata.mcp/src/org/jawata/mcp/tools/PlanRefactoringTool.java:570`
- `org.jawata.mcp/src/org/jawata/mcp/tools/build/UpdateDependencyTool.java:120`
- `org.jawata.mcp/src/org/jawata/mcp/tools/build/UpdateDependencyTool.java:208`
- `org.jawata.mcp/src/org/jawata/mcp/tools/build/AddDependencyTool.java:136`
- `org.jawata.mcp/src/org/jawata/mcp/tools/build/AddDependencyTool.java:247`
- `org.jawata.mcp/src/org/jawata/mcp/tools/ExperienceTool.java:1258`
- `org.jawata.mcp/src/org/jawata/mcp/tools/build/GradleBuildSupport.java:91`
- `org.jawata.mcp/src/org/jawata/mcp/runtime/profile/HsErrParser.java:63`
- `org.jawata.mcp/src/org/jawata/mcp/field/FieldState.java:159`
- `org.jawata.mcp/src/org/jawata/mcp/models/SiblingRegistry.java:126`
- `org.jawata.mcp/src/org/jawata/mcp/knowledge/ExperienceMaintenance.java:246`

## How to regenerate

```sh
SRC="org.jawata.core/src org.jawata.mcp/src"
grep -rn "Files\.walk(\|Files\.walkFileTree(\|Files\.list(\|Files\.readString(" --include=*.java $SRC
```

One line of the walk output is a COMMENT (`ProjectImporter` — "Files.walk fails
LAZILY") and is excluded from the count above; a regeneration that includes it reads one
high.

## Classification — the `walk` family, 8 of 14 sites read

The standard is `ExperienceMaintenance:194`, which the plan names: on failure it adds a row
to the RESULT — `skipped.add(Map.of("source", …, "reason", "cannot list: " + e.getMessage()))`
— so the caller sees which root could not be read and why.

Measured against it, the shapes are three, and they are not equally bad:

| shape | sites | what a caller receives |
|---|---|---|
| **returns a VALUE that reads as a real answer** | `HostFs:51`, `HostFs:72`, `RuntimeArtifactStore:169` | `catch (Exception ignored) { return 0; }` — a size or count of **0**. "Could not read" and "it is empty" are the same number. This is D5's subject in its purest form |
| **silent, answer unchanged** | `CoverageService:261` | `catch (IOException ignored) { }`, then returns the newest timestamp found so far — a partial walk reads as a complete one |
| **logged, but not in the result** | `CoverageStore:118`, `RuntimeArtifactStore:189` | `log.warn(…); return false;` — recoverable by a human reading a log, invisible to the caller |
| **already compliant** | `ExperienceMaintenance:194` | the standard above |

`DiskSyncGuard`'s `walkFileTree` is a visitor returning `SKIP_SUBTREE`/`CONTINUE` and is a
different shape; it is counted in the population and not yet classified.

**NOT YET READ: `ProjectImporter`'s five `walk` sites and its `walkFileTree`.** Stated so the
table above is not mistaken for the whole family — 8 of 14 are classified, 6 are not.
