# Sprint 23 — evidence dossier

> Companion to the signed spec (`jawata-enterprise/.../sprint-23-coverage-test-grounding.md`)
> and the actionable plan. Every number expected-vs-actual.

## C0 — Baselines + probes + landscape (2026-07-12/13)

### (a) Serial full-suite baseline (the D2 denominator)

- Run: dedicated in-framework serial run on the v2.9.2 dist (idle machine, 24 cores),
  per-class timings captured from the progress listener. Completed 2026-07-13 00:0x,
  exit 0.
- Expected: ~28 min (the 2.9.2 release-gate observation) · Actual: **1761 s wall
  (29:21)** ✓ — the D2 denominator; the ≤50% target is therefore **≤ 880 s**.
- Suite counts expected: 1192 total = 1187 pass + 5 skip, 0 fail · Actual:
  **1192 = 1187 + 5 skip, 0 failed, 0 aborted, 0 unloadable** ✓ (exact).
- Per-class timing distribution (Stage-6 partitioning input): full 229-class list in
  `dossier-23-timings.txt` (sum 1758 s). Shape: classes 1–21 = 217 s (12%) — LESS
  front-loaded than the 22d observation suggested; the load is broad. Slowest
  singles: PathUtilsImplTest 98 s, DiskSyncGuardTest 48 s, ConflictSeamTest 32 s;
  ~15 classes ≥ 20 s. Balanced 4-shard partition ≈ 440 s/shard — the ~8-min stretch
  target is arithmetically reachable if boot overhead per shard stays small.

### (b) Resident self-state (the self-serve substrate)

- `health_check`: v2.9.2, projectKey `jawata-mcp`, **471 source files** (expected 471 ✓).
- `compile_workspace(summary)`: **0 errors / 0 warnings** (expected 0/0 ✓) — verified
  2026-07-12 ~21:05 and re-verified at Stage 0.
- Disabled probe `find_pattern_usages(kind=annotation, query=org.junit.jupiter.api.Disabled)`:
  **exactly 5 sites** (expected 5 ✓) — RunTestsToolTest L221/L242/L256,
  GenerateTestSkeletonToolTest L79, EncapsulateFieldToolTest L41.

### (c) Landscape re-diff (README-level, fetched 2026-07-12 late)

| Competitor | Version / activity | Capabilities (relevant) | Delta vs audit + disposition |
|---|---|---|---|
| **jdtbridge** (kaluchi) | **v2.6.0, 2026-04-05**, 242 commits, ACTIVE — the earlier "last updated 2026-03-18" was stale Marketplace data | streaming test progress + immediate failure reporting; **code coverage via EclEmma "coverage mode"**; jar-source by FQN (Spring/Hibernate/JDK); pipe queries (`@callers`, `@implementors`, `@members`, `@source`, `@problems`); incremental-compile diagnostics; workspace dashboard; refactoring | **MATERIAL: they HAVE coverage** (IDE-run EclEmma display). Assessment: D3's contract (per-diff, per-symbol index, per-test attribution, mutation, provenance/staleness, gate advisory) exceeds an EclEmma display mode on every axis that matters to an autonomous agent; parity items D1/D8/D9 unchanged. Judgment: NO spec change required — **flagged to Harald at C0 for ratification** |
| **javalens-mcp** (pzalutski-pixel) | **v1.5.1, 2026-06-16** (newer than the 05-29 the search reported) | 75 tools / 8 categories; refactorings returned as UNAPPLIED text edits; new: "disk synchronization" (query-time file-state verification, no watchers) | No new gap: no execution/coverage/runtime; jawata has strict disk sync since v2.5.x (StrictDiskSync test family). Table row updated |
| **LSP4J-MCP** (stephanj) | v1.0.0, 2026-01-05 | 5 navigation tools over JDTLS; explicitly NO refactoring execution, NO test running, NO coverage | Confirms the LSP-wrapper class assessment; no gap |

Process note for the record: the pre-sprint parity statement ("no movement since the
audit; coverage = a class neither competitor can follow") was WRONG for jdtbridge —
built on stale Eclipse-Marketplace dates instead of the repo. Corrected here; lesson:
diff the REPO (releases + README), never the marketplace listing.

### (d) Tool versions (chosen + fetch-proven from Central)

| Tool | Version | Fetch proof |
|---|---|---|
| JaCoCo (agent/core/report) | **0.8.15** (latest stable) | `dependency:get` OK ×3 |
| PIT (pitest, pitest-entry) | **1.25.7** (latest stable) | `dependency:get` OK ×2 |
| JUnit platform console-standalone (forked-runner launcher) | **1.14.4** — the latest **1.x**; the metadata `latest` is 6.1.2 (JUnit 6), deliberately NOT chosen: user projects and our test bundles sit on the Jupiter 5.x line | version list verified on repo1 |

### C0 exit status

- (a) ✓ 1761 s / 1192 = 1187+5, 0 failed; (b) ✓; (c) ✓ with ONE material finding
  (jdtbridge has EclEmma display-mode coverage — assessment: D3 exceeds it on every
  agent-relevant axis, NO spec change; presented to Harald with the C0 report);
  (d) ✓. **C0 GREEN** — proceeding under the 2026-07-13 autocontinue overrule
  (stops only at C13, C14, or blocking failures).
