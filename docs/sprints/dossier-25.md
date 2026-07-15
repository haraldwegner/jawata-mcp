# Sprint 25 — evidence dossier

> Companion to the signed spec
> (`jawata-enterprise/docs/sprints/jawata-mcp/sprint-25-agent-runner.md`, GATE 1
> 2026-07-15) and the GATE-2 plan
> (`~/.claude/plans/make-an-actionable-plan-declarative-harbor.md`, approved
> 2026-07-15 with the God-mode versioning decision). Every number
> expected-vs-actual. Two phases: mcp v2.14.x (the last v2.x interim — engines) →
> **v3.0.0 for both products** (the agent-runner milestone; fix = 3.0.1).

## C0 — Process sync + baselines (2026-07-15)

### Process sync

- The three Sprint-24-audit rules are in `~/.claude/skills/sprint/SKILL.md`,
  verbatim (implementation audit per release · body-clause checkpoint gates ·
  non-author "no narrowing" close-out).
- Spec synced with the GATE-2 God-mode versioning decision (jawata-enterprise
  `b841abf`).

### Baselines (on v2.13.1, mcp HEAD = 08f4142)

| Baseline | Expected | Actual |
|---|---|---|
| Suite (serial, under the JaCoCo agent) | green | **1394/1394** ✓ |
| Suite (sharded, 4) | green | **1391/1394** — 3 timing flakes under quadruple concurrent load (suite + coverage run + cargo + resident sweeps); ALL green focused (3/3 + 13/13); the same HEAD ran 1394/1394 twice on 2026-07-15 (v2.13.1 release + battery). See finding C0-F1 |
| toolCount | 45 | **45** ✓ (health_check live) |
| Resident (jawata-mcp) | 2.13.1+, 553 sources, healthy | ✓ 2.13.1 / 553 / Ready |
| Studio | cargo test green | **174/174** in 2.22s ✓ |
| **Coverage-ratchet baseline** (org.jawata.* production code, full suite under the agent) | recorded | **lines 22,598/29,269 = 77.21% · branches 9,722/15,140 = 64.21% · methods 2,686/3,367 = 79.77%** (first anchor for the release-over-release ratchet) |
| Architect sweep baseline=save — kerievsky | saved | ✓ 3 findings |
| Architect sweep baseline=save — solid | saved | ✓ **82 findings** |
| Architect sweep baseline=save — fowler | saved | ✗ **exceeds the 30s tool timeout, even on an idle machine** — see finding C0-F2 |

Sweep sanity notes (the detectors already agree with the sprint's premises):
`incomplete_delegation` — the architect seat's first priority — fires across the
tree (ScopedJdtService#findType, JdtServiceImpl#lookupType, CoverageAttribution ×3,
Landmarks#rank, …); `divergent_change` independently flags the incident cluster the
Sprint-24 audit called the weakest engineering (ProfileTool 11 commits/9 areas,
DebugTool 12/25, DebugController 9/11).

### Findings

**C0-F1 — three named timing flakes under heavy parallel load (watch item, not
absorbed):** `DebugAmbiguousClassTest#anUnambiguousClassStillResolves` and
`#redefineRefusesAnAmbiguousClass` (the launched target had not reached its
class-load point → TYPE_NOT_LOADED where the test expected loaded/ambiguous) and
`ProfileFloorTest#seededDeadlockIsNamedInSummary` (the seeded deadlock had not yet
formed when sampled). All three are v2.13.1's own runtime tests with fixed timing
budgets; under 4 shards + a coverage JVM + cargo + resident sweeps the budgets were
exceeded. Focused reruns green. Robustness fix (wait-for-condition with deadline
instead of fixed sleeps) is v2.14.x-rider material — candidate work for Stage 7's
dogfood round, NOT silently absorbed.

**C0-F2 — the fowler family sweep does not fit the 30s tool timeout on this tree
(553 sources), even idle.** kerievsky (2 kinds) and solid (8 kinds) baseline fine;
fowler (18 kinds, several AST-heavy + git-history-backed) times out client-side.
Direct Stage-11 consequence: the architect seat's sweep is built on exactly this
call. Options for Harald at C0 (decision not made unilaterally): (a) bump the
resident's `timeoutSeconds` config (currently 30) for sweep-class calls; (b) the
seat sweeps kind-wise/family-split (kerievsky+solid families + fowler kinds
individually) — works today, loses the single fowler-family baseline diff; (c) a
v2.14.x product fix: family sweeps honor a per-call timeout budget or compute
incrementally. The fowler BASELINE remains unsaved until one of these is chosen.

### Stage −1 (prerequisites) — executed LATE, at Harald's prompt

Honest record: the cold-start checklist was initially treated as satisfied by
in-context authorship — Harald asked "have you done −1 prerequisites?" and the
answer was PARTIALLY. Executed in full then: primer refreshed; the prescribed
recalls run — `RecipeEngine#run` (the stale-buffer failure mode: LTK writes vs
Openable buffer invalidation; step N+1 can parse pre-step-N content MID-TOKEN;
Sprint-23 fix = close modified CUs after each step — DIRECTLY governs the Stage 1–3
parity batteries), symptom "flaky test" (the ChangeEngine deferred-delta
"filesModified: []" failure mode — ChangeEngine is the seam's host layer),
`RuntimeSessionRegistry#launch` recorded earlier this session; the
diagnosis-discipline memory read in full (its "surface at Sprint 25/26 drafting"
destination is satisfied — the spec's D6 seat discipline encodes it); toolchain
explicit (java 21.0.10 · maven 3.9.9 · cargo 1.95.0); BOTH residents re-checked at
C0 time (2.13.1, toolCount 45, jawata-mcp 553 sources / orb 29/29 healthy).

### C0 exit vs plan

| Exit criterion | Status |
|---|---|
| SKILL.md carries all three rules verbatim | ✓ |
| Spec version paragraph + Recorded decisions synced and committed | ✓ b841abf |
| Dossier baselines committed | ✓ (this file) |
| baseline=save confirmed for all three families | **✗ 2/3** — fowler blocked by C0-F2, Harald's call |
