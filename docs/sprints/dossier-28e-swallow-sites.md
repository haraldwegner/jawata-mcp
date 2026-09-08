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

---

# CORRECTION, 2026-09-08 — the population was 45 and is 44, and the classification was not a partition

Everything above stands as the record of what was believed at the time. It is superseded by
this section, which is measured rather than recalled. **The reason it matters is C8's own
wording:** *changed sites plus written exceptions EQUAL the enumerated population.* An equality
is arithmetic, and three faults above make the arithmetic unrunnable.

## Fault 1 — 45 counts OCCURRENCES; there are 44 SITES

`ExperienceMaintenance.java:194` is a ternary containing both openers on ONE line:

```java
try (Stream<Path> s = recursive ? Files.walk(root, Math.max(1, maxDepth)) : Files.list(root)) {
```

The population table ran four separate greps and counted it twice — once under `walk`, once
under `list`. One combined sweep, deduplicated by `file:line`, answers **44**, and the raw
occurrence count of that same sweep is also 44:

```sh
SRC="org.jawata.core/src org.jawata.mcp/src"
grep -rn "Files\.walk(\|Files\.walkFileTree(\|Files\.list(\|Files\.readString(" --include=*.java $SRC \
  | awk -F: '{print $1":"$2}' | sort -u | wc -l      # 44
```

The two instruments agree at 44. **This is the same defect the file's own opening section
describes** — an enumeration taken per-overload rather than once — arriving one section later
in the document that names it.

## Fault 2 — the shapes overlap, so no site can be counted

`HostFs:51` and `:72` are listed under **B** (as their catches `:56`, `:60`, `:75`) *and* under
**E**. They are two sites, and a site has one shape. Under a partition:

- `:51`'s catch carries a written reason AND the check below settles it → **B**
- `:72`'s catch claimed the same reason and it was FALSE for the case that matters → **E**

## Fault 3 — the `walkFileTree` family was classified nowhere

The file says *"All 45 sites are enumerated and classified"*. Both `walkFileTree` sites were
not: `DiskSyncGuard:173` is called *"not yet classified"* and `ProjectImporter:1693` is never
mentioned outside the population list. Read now, they are **B** and **D** respectively.

**And the shape-D count was RIGHT for the wrong reason.** Its list holds 12 entries under a
heading of 13. Adding `ProjectImporter:1693` makes it 13 — so the number was correct about a
population its own list excluded. `5 + 13 + 27 = 45` closed on two errors cancelling.

## The partition — all 44 sites, each appearing exactly once

Coordinates are **current** (the ones above have drifted: this sprint's own D5 edits moved lines
in `HostFs`, `CoverageService` and `RuntimeArtifactStore`). Shapes, as one test each:

| shape | the test | n |
|---|---|---|
| **P** propagates | no local catch; the method throws and its caller reports | 18 |
| **A** reports in its own result | the failure is in the value the caller receives | 8 |
| **B** written reason | the degraded answer IS the designed one, stated in the code | 3 |
| **D** logged, result silently degraded | a human with the log can recover it; a caller cannot | 14 |
| **E** silent, well-formed value | an absence rendered as a number or a null | 1 |

18 + 8 + 3 + 14 + 1 = **44**. Shape C is empty under a per-site partition: the two sites it held
(`CoverageStore`, `RuntimeArtifactStore` per-file `Files.delete` catches) are not in any of D5's
four families at all.

### P — propagates (18, nothing to do)
`CoverageService:174` · `RunnerClasspath:213` · `FieldState:159` · `HostFs:147` ·
`JawataApplication:875`, `:900` · `SiblingRegistry:126` · `ExternalBundlePool:205`, `:210`,
`:212` · `ProjectImporter:1459` · `HsErrParser:63` · `AddDependencyTool:136`, `:247` ·
`GradleBuildSupport:91` · `UpdateDependencyTool:120`, `:208` ·
`ReplaceConstructorWithFactoryTool:180`

**The `readString` family is 12 of these, not 13.** Measured: exactly 4 of its 16 sites carry a
local catch (`ResolvedToken:128`, `ExperienceTool:1258`, `PlanRefactoringTool:570`,
`ExperienceMaintenance:246`), so 12 propagate — and 12 + 2 + 1 + 1 = 16 closes, where 13 did not.

### A — reports in its own result (8, the standard)
`ExperienceMaintenance:194` · `ExperienceTool:1258` · `ResolvedToken:128` ·
`RuntimeArtifactStore:188` (outer) · `RuntimeArtifactStore:280` · `CoverageService:284` (outer) ·
`HostFs:72` (fixed in this pass) · `ProjectImporter:1453`

`ProjectImporter:1453` is the strongest of them and was previously unclassified: on zero files
found after an exit-0 Maven run it **refuses to answer** — *"Refusing to treat that as 'no
dependencies'"* — which is D5's principle stated by code written before D5 existed.

### B — written reason (3, listed, nothing to change)
`HostFs:51` · `ProjectImporter:1333` · `DiskSyncGuard:173`

`DiskSyncGuard:173` is newly classified and is correct as it stands: the guard answers *did
anything change between scans*, and an unreadable root is not a change. Its comment says so —
*"Unreadable root: surface nothing false; the next scan retries."*

## D — the 14 dispositions

The test applied, once, to every site: **does a caller act on this value as if it were
complete?** Yes → the result must carry the failure. No → a written exception, with its reason.

### Move to reporting (5)

| site | why the caller acts on it as complete |
|---|---|
| `CoverageStore:92`, `RuntimeArtifactStore:124` | `list()` answers `List.of()`, so an unreadable store is byte-identical to an empty one. `latest()` then reads `all.get(0)` and the orphan sweeper acts on the same list. **This is the surviving half already recorded**: `UnreadableArtifactIsReportedTest` carries an `assumeTrue` saying so in its own words |
| `CoverageStore:118`, `RuntimeArtifactStore:209` | `delete()` answers `false`, which each javadoc defines as *"there was nothing to delete"*. Two different facts, one boolean |
| `ExperienceMaintenance:246` | the cheapest of the five: its SIBLING at `:194` is shape A and already builds a `skipped` list with a reason per source. The mechanism is in the same class and this call does not use it |

### Written exception (9)

| site | the reason |
|---|---|
| `ProjectImporter:1008` | the result is a SAMPLE by construction — capped at 500 files × 120 lines — so no caller can read it as a census, and a short sample is what the cap already guarantees |
| `ProjectImporter:1598` | a missing jar surfaces downstream as an unresolved type, which `compile_workspace` reports at the place a reader is looking |
| `ProjectImporter:1620`, `:1638` | `countSourceFiles` / `findPackages` feed the project summary a human reads, and the `log.warn` lands in the same session for the same human. The consumer of the number is the consumer of the log |
| `ProjectImporter:1693` | `walkPruned`, whose javadoc already states the reason: *"an unreadable directory must not end the scan of a project"* — and its `visitFileFailed` continues by design |
| `ProjectImporter:1867`, `:2076` | discovery heuristics; a wrong answer surfaces as a missing source root, which the load report's own file count carries |
| `JawataApplication:842` | memory-root discovery — the ingest report already carries a per-source count, which is where a dropped root shows |
| `H2ExperienceStore:1781` | **the weakest of the nine, and said so rather than dressed up.** Its report carries `imported: 0`, and "could not list" versus "nothing to recover" are the same 0. It is excepted only because it is a recovery path a human invokes deliberately and reads the log of; if one of these nine should move, it is this one |

## E — and the LIVE ones are not in D5's four families at all

`PlanRefactoringTool:570` (`return null`) is the only in-family E left. **Both sites this file
named as shape E have been fixed** — and the second was fixed before this pass, so the entry
`CoverageService:272 (catch (IOException ignored) { })` was already stale when it was written:
that catch reads `OptionalLong.empty()` today, with a message naming why.

**But two live swallows of exactly D5's shape sit INSIDE the walk lambdas, where the four
families never looked:**

```java
// RuntimeArtifactStore:191 — inside sizeOf, whose OUTER catch was fixed for this very defect
.mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum()

// CoverageService:289 — inside rootsFingerprint, same
.mapToLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); }
                  catch (IOException e) { return 0; } }).max()
```

The first under-counts a byte total that is then returned as PRESENT and authoritative — the
exact defect the outer catch was changed to stop, surviving one line inside it.

The second is worse than under-counting because of the direction. It is a STALENESS
fingerprint reduced with `max()`, so a class file that cannot be stat'd contributes epoch and
**cannot raise `newest`** — a rebuilt-but-unreadable class makes the artifact look FRESH, and
coverage is then served over stale bytes. That is the `isNoMatch` class of defect this sprint
already fixed in this same file, arriving through the accessor rather than the loader.

**The finding under both:** D5's population was scoped to the four OPENERS and never to the
per-element READERS invoked inside them. Measured, that adjacent family is 12 sites —
`Files.size(` 6 (a naive grep answers 8; two are `javaFiles.size()`, a `List` call, which is why
a text sweep is read and not counted), `Files.getLastModifiedTime(` 5, `Files.readAttributes(`
1. Two of the twelve are the swallows above. **The family is named here and is NOT claimed as
enumerated-and-classified**; scoping it is a decision, not a task, because it widens D5's
population after the spec fixed it.

## The equality, restated over the measured population

**And the first version of this table was WRONG, in the section whose subject is wrong tables.**
It read `29 + 6 + 9 = 44` while claiming `PlanRefactoringTool:570` was "counted among the
changed" — it was in none of the three rows, and `already compliant` counted `HostFs:72` as
compliant when it is compliant *because of* this pass. Two errors that summed to the right
total, which is exactly how `5 + 13 + 27 = 45` survived above. Corrected, and left visible:

| | n | which |
|---|---|---|
| compliant BEFORE this pass | **28** | P 18 + A 7 + B 3 |
| changed in this pass | **7** | `HostFs:72` (E→A) · 5 shape-D moving to reporting · `PlanRefactoringTool:570` (E→A) |
| written exceptions | **9** | the shape-D table above |

28 + 7 + 9 = **44**. The equality holds over 44 and could not have held over 45.

**What is NOT in it, said plainly rather than folded in:** the two live swallows inside the walk
lambdas (`RuntimeArtifactStore:191`, `CoverageService:289`) belong to the accessor family, which
D5 never scoped. Counting them here would let a widened population be smuggled in under an
equality that was signed over a narrower one.

---

## CORRECTION TO THE CORRECTION — `ExperienceMaintenance:246` is shape A, and shape D is 13

I classified it **D** on its `log.warn` line. **The next line adds a `skipped` row with the
reason**, which is the standard this whole file measures against:

```java
} catch (IOException e) {
    log.warn("load: cannot read {}: {}", f, e.getMessage());
    skipped.add(Map.of("source", f.toString(), "reason", "unreadable: " + e.getMessage()));
    continue;
}
```

Classifying a catch on its first statement is how a site that already reports gets filed as
one that does not. **P 18 · A 9 · B 3 · D 13 · E 1 = 44.**

**And shape D is 13 for a THIRD different population.** The original list held 12 under a
heading of 13; adding `ProjectImporter:1693` made 13; removing `ExperienceMaintenance:246`
makes it 13 again by a different route. Three populations, one number — which is precisely why
a hand-written count beside a hand-written list proves nothing, and why the count is now
derived from the partition rather than stated beside it.

## The disposition ledger — every one of the 44, in exactly one state

| state | n | sites |
|---|---|---|
| **compliant before this pass** | 29 | P 18 · A 8 · B 3 |
| **changed in this pass** | 3 | `HostFs:72` · `RuntimeArtifactStore:124` · `CoverageStore:92` |
| **written exception** | 10 | the 9 shape-D above · `PlanRefactoringTool:570` |
| **raised as a contract decision** | 2 | `CoverageStore:118` · `RuntimeArtifactStore:209` |

29 + 3 + 10 + 2 = **44**.

### What each changed site does now

- **`HostFs:72`** — the final walk's catch claimed *"gone between the loop and the count"* and
  returned 0, which its own javadoc defines as *the tree is gone*. It now CHECKS, with
  `Files.notExists` rather than `!Files.exists`: both answer false when the filesystem cannot
  say, so only `notExists == true` is confirmed absence, and reading `!exists` would put the
  same defect back inside the check written to remove it. Still there, or cannot tell → 1, a
  floor rather than a count.
- **`RuntimeArtifactStore:124` and `CoverageStore:92`** — `list()` filtered on
  `Files.isRegularFile(manifest)`, which folds *cannot determine* into *no manifest*, so an
  unreadable artifact was not merely unmeasurable, it was INVISIBLE: `latest()` skipped it and
  no caller could describe or delete it by name. Both now filter on `manifestMissing(d) !=
  TRUE`. **Closed as a class**: the two stores are byte-identical here, and the helper existed
  in one and was copied to the other rather than left to be found again.

### The two raised, and why they are not changed here

`delete()` in both stores answers `false` on an unreadable directory, and each javadoc defines
`false` as *"there was nothing to delete"*. Two facts, one boolean — D5's defect exactly. The
fix is a change to a **published return contract** on two classes, which is a design decision,
not a mid-stage edit. Specified, raised at C8, not applied.

The same is true of the residual half of `list()`: an unreadable ROOT still answers `List.of()`.
The artifact-level swallow is closed; the store-level one is the same contract question.

### `PlanRefactoringTool:570` — the exception, and the finding under it

The site is a private parse helper whose `null` is consumed by an **opportunistic** purity
diff, correctly skipped for a step that is not method-scoped. **The defect is at the consumer**:
a step that IS method-scoped whose file cannot be read takes the same branch, so the purity
check never runs and the plan response renders its findings as though it had — *"found
nothing"* and *"did not happen"* being the same answer, on the gate this sprint's parity story
rests on. Closing it means adding a row to what a gate reports. **Raised at C8.** The site
itself now logs which of read-or-parse failed, so the failure is no longer invisible.

## Outside the signed population — two accessor-family swallows, fixed

Not counted in the 44, because counting them would smuggle a widened population under an
equality signed over a narrower one:

- **`RuntimeArtifactStore` `Files.size` → 0**, one line inside the method whose OUTER catch was
  changed for this very defect. A file whose size cannot be read contributed 0 to a total then
  returned as a real number. Now unchecked, so the whole call answers "no size taken".
- **`CoverageService` `Files.getLastModifiedTime` → 0**, and the DIRECTION is what makes it
  serious: a staleness fingerprint reduced with `max()`, so an unstattable class file could
  never RAISE `newest` — a rebuilt-but-unreadable class made the artifact look FRESH and
  coverage was served over stale bytes. Failing open in the one direction a staleness check
  must not.

## The mutation ledger — four, each reverted with `git checkout HEAD --` and the tree verified clean

`git checkout HEAD -- <path>`, never the bare `git checkout -- <path>`: a bare revert restores
from the INDEX, and where the mutation was staged that silently keeps it. The `dirty=` count
after each revert is what catches that.

| # | Mutation | Went red | What that proves |
|---|---|---|---|
| U | `HostFs`'s final catch back to `return 0` | `HostFsResidueTest`, on the aimed assertion, quoting the defect: *"a walk that FAILED must not answer 0: 0 is this method's own word for 'the tree is gone', and the tree is still there. got: 0"* | the check is live, and the test reads the METHOD's own contract rather than a number I chose |
| V | `RuntimeArtifactStore.list()` back to `Files.isRegularFile` | `UnreadableArtifactIsReportedTest`, 1 of 3, on the assertion that replaced the abort: *"an artifact whose directory cannot be read must still be LISTED"* | the artifact-drop is closed, and the control is a case that used to ABORT in every run and now runs |
| **W** | `RuntimeArtifactStore`'s per-file `Files.size` back to `return 0` | **NOTHING — 28/28 green** | see below |
| **X** | `CoverageService`'s per-file `Files.getLastModifiedTime` back to `return 0` | **NOTHING — 28/28 green** | see below |

### W and X stayed green, and that is a finding rather than a formality

Both fixes are correct by reasoning and **neither is guarded**, which is stated here rather
than left for an auditor to discover.

**The reason is structural, not an oversight in the tests.** Reaching either inner catch needs
a path that passes `Files.isRegularFile` and then fails `Files.size` — so the file must exist
at the filter and be gone or unstattable microseconds later at the accessor. That is a RACE,
and no deterministic fixture produces it. The two ways an unreadable thing is normally built
both miss it: strip a FILE's permissions and `stat` still succeeds (it needs execute on the
parent, not read on the file), and strip a DIRECTORY's and `Files.walk` fails lazily at the
directory, which the OUTER catch takes.

**So the same property that hid these two swallows from every reader also hides them from every
test**, and it is why they survived the change to the outer catch in the very same method. What
they are is a real failure mode under a race — a temp file swept mid-walk, an artifact deleted
by a concurrent sweeper — where the answer was a number that read as complete.

They are recorded here as **fixed by reasoning, unguarded by construction**. That is weaker
than the other two and is not dressed up as equal to them: this file's own record already
carries one case (`mutation T`, commit `4494b34a`) where a claim was made for a fix whose effect
no instrument could see, and the correction cost more than the honesty would have.

## C8's third clause — *"the spec's five named rows are among them"*

The five are the `walk` sites the symbol index missed and the text sweep found, named in the
plan's own table at the point the overload discrepancy was settled. Each is here with its
disposition and its CURRENT coordinate — every one of them moved, because this sprint's own
D5 edits shifted lines in all three files.

| the plan's row | now | shape | disposition |
|---|---|---|---|
| `HostFs:51` | 51 | **B** | **written reason, in the code.** Its catch says *"The dir may already be gone — the check below settles it"*, and the check below is a real one at line 62 that returns only on a settled answer. Nothing to change |
| `HostFs:72` | 72 | E → **A** | **CHANGED**, mutation U |
| `CoverageStore:118` | 118 | D | **written reason**, see below |
| `RuntimeArtifactStore:169` | 188 | **A** | already compliant — `sizeOf`'s outer catch answers `OptionalLong.empty()`. Fixed earlier in this stage |
| `RuntimeArtifactStore:189` | 209 | D | **written reason**, see below |

**All five are in one of C8's two admissible states.** That was not true an hour ago, and the
correction is worth recording because the wrong answer was the comfortable one.

### The two `delete()` rows — and why "raised" was the wrong disposition

I first parked both as *raised as a contract decision*, which is a THIRD state C8 does not
admit — a deferral wearing a verdict's clothes, which this plan warns about in those words.
**Reading the code rather than the javadoc settled it.**

`false` did not mean one thing that I was proposing to split. It ALREADY meant three:

```java
if (!Files.isDirectory(dir)) return false;                      // absent
catch (IOException e) { log.warn(...); return false; }          // could not WALK it
for (Path p : paths) { ... catch (IOException e) { ok = false; } }  // could not delete a file
return ok;
```

Only the first is *"there was nothing to delete"* — which is what BOTH javadocs claimed
{@code false} means, and both were therefore **false about their own methods**, in the two
places a caller reads to find out. The walk-failure branch was not introducing an ambiguity;
it was the second of three cases already collapsed into one boolean, and the documentation
named the wrong one.

So the disposition is a written reason **in the code**, and it is a change rather than a
deferral: both javadocs now state the contract the way round that holds for every branch —
`false` means **not gone**. Applied to both stores in one edit, because they are byte-identical
here and correcting one would have left the other saying something untrue.

**The residual is named and NOT counted as disposed**: the method still cannot tell a caller in
its own return whether the artifact was absent or survived. Closing that changes a published
return type on two classes. Raised at C8 — as a residual of a disposed site, not as the site's
disposition.

## The equality over C8's own scope — THREE families, not the four surveyed

C8 says *across all three families*. The survey covers four: `walkFileTree` was added because
its two sites were in the population table and classified nowhere. So the equality is stated
over both scopes, and it must hold over the criterion's scope on its own.

| | three families (`walk`, `list`, `readString`) | + `walkFileTree` |
|---|---|---|
| distinct sites | **42** | **44** |
| compliant before | 28 (P 18 · A 8 · B 2) | 29 (B 3) |
| changed | 3 — `HostFs:72` · `RuntimeArtifactStore:124` · `CoverageStore:92` | 3 |
| written exceptions | 11 — 8 shape-D · both `delete()` javadocs · `PlanRefactoringTool:570` | 12 |
| **total** | 28 + 3 + 11 = **42** | 29 + 3 + 12 = **44** |

Both close. The one site the wider scope adds to *changed or excepted* is
`ProjectImporter:1693` (`walkPruned`), whose reason its own javadoc already carried.

---

# THE ARCHITECT WATCH — `MIXED`, and three of its findings are about text written in this pass

Read-only, over `4494b34a`, `f78e1cbb`, `e94d9860`. Its verdict separates the set: `HostFs`,
its test and the `4494b34a` retraction are DESIGN FIXES; `list()`, the copied helper, the two
`UncheckedIOException` throws and both `delete()` javadocs are BANDAGES over one structure.

**Every claim below was CHECKED against the source before acting. All three held.**

## F-A — a javadoc false about the code twelve lines beneath it, created by this pass

`sizeOf`'s javadoc read *"The per-file 0 inside the sum is a different case and stays: a file
that vanished mid-walk contributes nothing to a total, which is true."* True when written;
`f78e1cbb` made the per-file catch THROW and did not touch the paragraph.

**That is the exact defect `e94d9860` was written to fix on `delete()`, created by the commit
before it and unnoticed by the commit that fixed its twin.** Corrected, with the old sentence
left visible rather than quietly replaced.

## F-B — the copies were already divergent, under a javadoc asserting they were identical

`CoverageStore.manifestMissing`'s javadoc said *"the two are byte-identical stores with
byte-identical list() methods"*. Neither half was true when written: the stores share ten
members and differ elsewhere, and the two copies of the helper differ in javadoc, in log text
and in one `Files` qualification. **A claim of sameness inside the copy that disproves it.**
Corrected.

The architect's ruling on the copy itself, which answers the question put to it: it is the
beginning of the drift, and *"capability was copied; need was not"* —
`RuntimeArtifactStore` genuinely uses all three states (`list()` reads `!= TRUE`,
`pruneOrphans` reads `== TRUE`, opposite defaults from one helper), while `CoverageStore` has
one consumer that treats `null` and `FALSE` alike.

## F-C — the remedy my own javadoc prescribed routed through an unfixed copy of the defect

`delete()`'s new javadoc says *"A caller that must tell those apart asks `exists(String)`
first"*. **`exists()` was `Files.isRegularFile`** — the same fold, on the same path, in both
stores. So the disambiguator named as the cure answered "no such artifact" for exactly the case
it was named to distinguish.

**FIXED in both**, using the helper that already existed: `manifestMissing(dir) != TRUE`.

## F1 — and this one is NOT closed: the `list()` fix is inert at `CoverageStore`'s consumers

Derived by the architect with the reference tools, not recalled: `CoverageStore#list` has seven
references — one in-class (`latest()`) and six production sites in `RunTestsTool` and
`CoverageLackDetector` — **and all seven resolve the id through `readManifest`**, which carries
the identical `Files.isRegularFile` fold on the identical path and drops what it cannot read.

So an unreadable coverage artifact now survives `list()` and is dropped one method later.
**The `CoverageStore` half of "closed as a class" changes nothing a caller can see.**
`RuntimeArtifactStore` fares better only because `describeAll` uses `orElse(Map.of())` and still
emits a row — which is the single observable effect of the whole change, and is what the test
asserts.

**Why it is raised rather than fixed here:** `readManifest`'s fold is `Files.isRegularFile`,
which is NOT one of D5's four enumerated families. Fixing it widens the signed population, and
this file has already declined that once for the accessor family on the same ground. What is
recorded instead is that **C8's equality is over OPENER SITES, not over lies removed** — the
architect's own words — and two of the three changed rows are inert or observable only
indirectly. That belongs in the ledger rather than left for an auditor.

## An undeclared behaviour change, found by the architect and not by me

`latest()` returns `list().get(0)`. With an unreadable artifact now surviving `list()`, if it is
the NEWEST one then `RunTestsTool` → `coverage.model(id)` → `readManifest` → null →
**"Unknown coverage artifact 'X'."**, and the whole default-artifact coverage query fails.
Before the change it was skipped and the query quietly answered about an OLDER artifact.

**The direction is right** — silently answering about the wrong artifact is worse — but it was
neither intended nor declared, and the message names the wrong cause.

## F2 — for C8, and one question only Harald can answer

`delete()`'s corrected javadoc (`false` means NOT GONE) is contradicted by three tool handlers
that render `false` as `symbolNotFound` — `DebugTool`, `ProfileTool`, `RunTestsTool` — an error
KIND meaning *not found*. **So the correction did not remove the falsehood; it moved it from a
comment developers read to a wire response users read.** A fourth site, `ProfileTool`:665,
discards the return entirely and can now silently leave a directory it reports as cleaned up.

The architect measured the in-workspace consumer set rather than escalating it:
`org.jawata.mcp.coverage` and `org.jawata.mcp.runtime` appear in **no `Export-Package`**, so the
nine references are complete, enforced by the manifest.

**THE QUESTION IT COULD NOT ANSWER, raised at C8:** changing `symbolNotFound` to a "could not
delete, it is still there" response alters an MCP error kind that agents and clients see, and
that set is outside this workspace by construction. *Is any client keying on `symbolNotFound`
for `artifact_delete` / `coverage_delete`?*

## F3 — RULE 12 FIRES, and the alarm is measured rather than asserted

Asked whether these fixes are one defect being moved around, the architect clustered by
structure instead of counting: **six different symptoms, one structure — a partial function
forced into a total codomain.** The failure state has no representation in the return type, so
it is aliased onto a legal success value: `false`, `0`, `List.of()`, `null`. The JDK performs
the fold first (`isRegularFile`, `exists`), so the codebase inherits it free at every use.

**The proof it is structural is in the cures, which are five mutually inconsistent inventions of
"cannot tell", all authored in one pass:** a three-valued `Boolean`; a sentinel floor
(`return 1`); `OptionalLong.empty()` reached by throwing; a corrected javadoc with no code
change; and a `log.warn` with the value unchanged. *A fifth site will invent a fifth.*

And the tri-state is the surveyed defect in miniature: `Boolean` is a two-state type plus
`null` — an absence rendered as a legal value — chosen as the cure for two-state-plus-fold.

**The proposal, raised at C8 and NOT built here:** one `ArtifactDir(Path, String manifestFile)`
value type owning `manifestState() : PRESENT | ABSENT | UNKNOWN`, `readManifest()`,
`exists()`, `sizeOf()` and `delete()`, held by both stores. A static helper cannot serve —
`MANIFEST_FILE` is per-store, so the fact needs an instance rather than a utility. That is what
makes the fold impossible to reintroduce at the fourth method.

---

# THE C8 FRESH-CONTEXT AUDIT — `REFUSE`, six blocking findings, and the ledger above is WRONG

Every finding was checked against source before acting. **All six held.** Three drove code and
three drove this arithmetic. The tables above stand as the record of what was believed; what
follows supersedes them.

## B1 + B2 — the equality closed on two cancelling errors, for the THIRD time in this file

`ee9bba8d` — *"S8/D5: 'I could not read your manifest' was becoming 'delete it'"*, a Stage-8/D5
commit **of this pass** — changed `RuntimeArtifactStore.sizeOf` and
`CoverageService.rootsFingerprint` from `return 0` / `catch (IOException ignored) { }` to
`OptionalLong.empty()`. Both were shape **E** before it. I counted them under *compliant before
this pass*, because they were compliant by the time I wrote the ledger.

**+2 in one row, −2 in another, same total.** Exactly the fault this document was opened to
correct, in the document correcting it, for the third time.

**And the reason I gave for excluding them is false.** I wrote that the E entry
`CoverageService:272` was *"already stale when it was written"*. Measured from the commit
timestamps:

```
39232214 04:16:00  S8: the walk and list families fully classified — shape E is the target
ee9bba8d 04:23:03  S8/D5: "I could not read your manifest" was becoming "delete it"
```

The entry was written at 04:16:00 and the fix landed **seven minutes later**. It was accurate
when written and was made stale by this stage's own work — the opposite of what I claimed, in
both halves.

## B3 — `HostFs:51` was disposed on a claim the same method refutes 21 lines below

I filed it **B**, *"the check below is a real one at line 62 that returns only on a settled
answer"*. Line 62 was `if (!Files.exists(dir)) return 0;` — and `!exists` is **true when
existence cannot be determined**, so it returned the number this method's javadoc defines as
*the tree is gone*, about a directory nobody could look at. Line 47's entry guard had it too.

**The comment I wrote at line 79, in this same pass, forbids exactly that** — *"reading
`!exists` here would put the same cannot-tell-means-no defect back in the check written to
remove it."* I wrote the correct reasoning at one guard and left the identical defect at the
two above it, then disposed a NAMED ROW on the claim that they were sound.

**FIXED**: both read `Files.notExists`. **Guarded**: a second case in `HostFsResidueTest`, and
the first case could never have caught it — stripping a directory's own permissions leaves it
stat-able, because `exists` walks the PARENT's execute bit. Making the parent unreadable is
what produces cannot-tell.

**Mutation Y** (both guards reverted, the final catch's fix left alone so a red cannot be
credited to the already-proved change): `total=2 succeeded=1 failed=1` — case 1 correctly
green, case 2 red on *"existence could not be determined, so 'the tree is gone' is a claim we
have not earned — 0 is exactly that claim. got: 0"*.

## B6 — "unguarded by construction" was FALSE for one of the two, and it is the serious one

The claim: reaching either inner catch needs a file to pass `Files.isRegularFile` and then fail
the accessor — a race no fixture produces.

**True of `RuntimeArtifactStore.sizeOf`**, whose walk really does filter on
`Files::isRegularFile`. **False of `CoverageService.rootsFingerprint`**, which filters on

```java
.filter(p -> p.getFileName().toString().endsWith(".class"))
```

— a string test on the file NAME that performs no stat at all. A **dangling symlink named
`*.class`** reaches the accessor deterministically: `Files.walk` does not follow links so it
yields the link itself and the name test passes; `Files.getLastModifiedTime` does follow it and
throws. One `createSymbolicLink` call, no race, no mock.

That is the one this file called the more serious of the two. **`StaleFingerprintIsNotFreshTest`
is the guard the claim said could not exist**, with a readable control that runs first.

**Mutation X2** (the swallow restored): RED, and the message is the defect —
*"got: OptionalLong[1788836944146]"*, a real-looking timestamp answered for a class file that
could not be read.

**The transferable lesson, and it is why this sits in the test rather than a commit message:**
*"no test can reach this" is a claim about a FILTER, and it must be checked against the filter
that is actually written* — not against the sibling it resembles.

## B4 — `RuntimeArtifactStore.pruneOrphans` is D, not A

Its catch is `log.warn(...); return List.of();` — byte-identical to *"no orphans found"*, which
is this file's own shape-**D** test, and the reading it applied to `CoverageStore:92` and
`RuntimeArtifactStore:124` before changing them.

**Disposition: written exception.** Its only production caller is `sweep()`, which discards the
list; the one caller that reads it is a test. The value no caller consumes cannot mislead one.
That is thinner than the other exceptions and is said plainly rather than dressed up.

## B5 — already fixed before the audit read it

`sizeOf`'s javadoc saying the per-file `0` *"stays"* was corrected when the architect watch
found it. The audit read HEAD, which did not yet carry the fix. Recorded so the two reports do
not read as two defects.

## Non-blocking, all confirmed

- **Coordinates are NOT current** where the tables claim to be — the five-rows "now" column and
  the E section give the `ee9bba8d` snapshot, already stale when written and stale again after
  `e94d9860` shifted two of them. Measured at HEAD: 160, 196, 241, 586. **Site identity is by
  file+method, so the equality is unaffected**, but the word "current" is not earned.
- `ProjectImporter:1453` is filed A and is **P** — the walk has no catch; the zero-files refusal
  is a separate check.
- **"Closed as a class" over two stores, and there is a THIRD**: `H2ExperienceStore:1783`
  carries the same cannot-determine-reads-as-absent predicate on a manifest-equivalent, inside
  an enumerated site. Unremarked until now.
- `DiskSyncGuard:173`'s B fails open in the direction this pass called serious — an unreadable
  root makes a change-detector answer "nothing changed". It carries a written reason so D5
  admits it; the asymmetry of judgement is recorded.

## THE LEDGER, corrected — and this time the rows are derived, not assembled

Current shapes over the 44: **P 19 · A 7 · B 3 · D 14 · E 1**. (`ProjectImporter:1453` A→P;
`pruneOrphans` A→D.)

| state | n | which |
|---|---|---|
| **compliant before this pass** | **25** | P 19 · B 2 (`ProjectImporter:1333`, `DiskSyncGuard:173`) · A 4 (`ExperienceMaintenance:194`, `:246`, `ExperienceTool:1258`, `ResolvedToken:128`) |
| **changed in this pass** | **6** | `HostFs:51` (its guards, so its written reason is now true) · `HostFs:72` · `RuntimeArtifactStore` sizeOf · `CoverageService` rootsFingerprint · `RuntimeArtifactStore` list · `CoverageStore` list |
| **written exceptions** | **13** | the 12 remaining shape-D · `PlanRefactoringTool` (E) |

25 + 6 + 13 = **44**.

**What makes this one different from the two that closed on cancelling errors:** the shape
counts are a partition of the measured 44, and each ledger row is that partition sliced by a
single question — *did this site need code changed in this pass?* — rather than three numbers
totted up to a target. The previous version reached 44 by adding two errors; this one reaches
it because 19 + 7 + 3 + 14 + 1 does.

## The four named rows re-checked, since B3 moved one

| the plan's row | disposition |
|---|---|
| `HostFs:51` | **CHANGED** — was disposed on a false written reason; its guards now settle what its comment claims |
| `HostFs:72` | **CHANGED**, mutation U |
| `CoverageStore` delete | **written exception** — the javadoc now true of every branch |
| `RuntimeArtifactStore` sizeOf | **CHANGED** in this pass (was miscounted as compliant-before) |
| `RuntimeArtifactStore` delete | **written exception**, same |

All five in one of C8's two admissible states, and now for reasons that survive reading the code.
