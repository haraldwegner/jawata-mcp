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

## Classification — `walk` + `list` families complete (26 of 26)

D5 admits two states per site: it **reports "could not read" in its own result**, or it is
**listed with a written reason**. This file is that list. Measured, the sites sort into five
shapes, and only the last needs work.

### A — reports in its own result (the standard)
`ExperienceMaintenance:205` — `skipped.add(Map.of("source", …, "reason", "cannot list: " + …))`.

### B — written reason present in the code, transcribed here
- `HostFs:56` — *"Retried by the next pass"*
- `HostFs:60` — *"The dir may already be gone"*
- `HostFs:75` — *"Gone between the loop and the count"*
- `ProjectImporter:1604`, `:2079` — the `UncheckedIOException` note: `Files.walk`/`Files.list`
  fail LAZILY, so the catch covers the stream's own throw as well as the open
- `ProjectImporter:1343` — `return null; // unhashable tree → no cache key`

These are races and lazily-thrown wrappers where a degraded answer is the designed behaviour.
Listing them here is what D5 asks; nothing to change.

### C — the result carries the degradation, the log carries the reason
`CoverageStore:129`, `RuntimeArtifactStore:200` — set `ok = false`, which IS returned. The
caller learns it failed; only *which* file is log-only. Partial by design, listed.

### D — logged, result silently degraded
`ProjectImporter:1013`, `:1623`, `:1647`, `:1875`, `:1879` · `CoverageStore:99`, `:121` ·
`JawataApplication:845` · `H2ExperienceStore:1787` · `RuntimeArtifactStore:131`, `:192`, `:243`

A human reading a log can recover these; a caller cannot. **Whether each should move to shape A
is a per-site judgement about whose answer it degrades**, and it is the survey's remaining body.

### E — SILENT, and the answer is a well-formed number
`CoverageService:272` (`catch (IOException ignored) { }`) · `RuntimeArtifactStore:174`, `:178`
(`return 0`) · `HostFs:51`, `:72` (`return 0`, though B's comments cover the sibling catches)

**This is the sharpest shape and the reason D5 exists.** A failed directory walk returns a byte
total of **0**, which is a well-formed answer no caller can tell from an empty directory — an
absence rendered as an emptiness, in a NUMBER rather than a message, which is why no reader has
ever caught it. These are the sites to fix first.


## Classification — the `readString` family (16 of 16)

**This family is largely healthy, and the spec's fear of it was misplaced twice.** It estimated
">60"; there are 16 production sites. And 13 of them have NO local catch at all — the failure
propagates to a caller that reports it, which is the correct shape and needs nothing.

| shape | sites |
|---|---|
| **propagates** — no local catch; the caller reports | `ProjectImporter` · `FieldState` · `JawataApplication` · `SiblingRegistry` · `HsErrParser` · `AddDependencyTool` · `GradleBuildSupport` · `UpdateDependencyTool` · `ReplaceConstructorWithFactoryTool` |
| **reports in its own result (A)** | `ExperienceTool:1266` → `ToolResponse.invalidParameter("path", …)` · `ResolvedToken:130` → throws, naming that the token file exists but cannot be read |
| **logged, result degraded (D)** | `ExperienceMaintenance:249` — `log.warn("load: cannot read {}: {}", …)` |
| **returns null (E-adjacent)** | `PlanRefactoringTool:576` — `return null;` |

## Where the survey stands

**All 45 sites are enumerated and classified.** What remains is not discovery but disposition:

- **shape E, 5 sites** — `CoverageService:272`, `RuntimeArtifactStore:174`, `:178`,
  `HostFs:51`, `:72`. A failed walk answers with a byte total of **0**. These are the fix.
- **shape D, 13 sites** — logged, result silently degraded. Each is a per-site judgement about
  whose answer it degrades; several are import-time debug logs where the tool's own result
  already carries a count the caller can read.
- **shapes A, B, C — 27 sites — are DONE**, either reporting in their result already or listed
  above with the written reason the code states.

## A CORRECTION, caught by a mutation that stayed green

The `RuntimeArtifactStore` orphan-sweep fix was committed (`ee9bba8d`) claiming **data loss**:
that an unreadable artifact was classified as abandoned and therefore DELETED.

**The classification half is true and is fixed.** `Files.isRegularFile` returns false when a
file is absent, is not a regular file, or CANNOT BE DETERMINED — so an unreadable directory
read as having no manifest, which is this store's definition of an abandoned capture.

**The deletion half is NOT demonstrated, and in the only case constructible here it is false.**
Mutation T restored the old behaviour and **every assertion stayed green**: `delete(id)` walks
the directory too, so on a directory that cannot be READ the deletion fails for the same reason,
and the artifact survives either way.

So the fix is right by reasoning — do not act on "I could not tell" — and its effect is
**unobservable from outside** in this fixture. The test's assertions on the kept artifact are a
regression lock, not proof, and the file says so; the only discriminating assertion in it is the
control that a genuinely unmanifested aged directory is still swept.

**A mutation that stays green has found something** — here, that I had asserted a consequence I
had not measured, in a commit message, one turn after the C7 record made exactly that point
about someone else's code.
