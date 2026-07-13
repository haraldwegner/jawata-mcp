# Sprint 24 — evidence dossier

> Companion to the signed spec
> (`jawata-enterprise/docs/sprints/jawata-mcp/sprint-24-dynamic-analysis.md`)
> and the GATE-2 plan. Every number expected-vs-actual. Three phases, one
> release each (v2.11.x / v2.12.x / v2.13.x), each with a dogfood-in-anger
> round.

## C0 — Baselines + spec/process sync (2026-07-13)

### Baselines (on the released v2.10.0, HEAD = a7ec390)

| Probe | Expected | Actual |
|---|---|---|
| Suite (sharded) | 1230/1230, 0 skipped | **1230/1230, 0 skipped, wall 164 s** ✓ |
| toolCount (live resident) | 43 | 43 ✓ |
| Resident version | 2.10.0 | 2.10.0 ✓ |
| Self-workspace sources | 500+ | 500 ✓ |
| Self compile_workspace | 0 / 0 | 0 errors / 0 warnings ✓ |

### Spec + process sync (the two GATE-2 decisions)

- SPEC: R1 amended (three phased releases supersede "fallback, not a
  pre-split") + both GATE-2 decisions added to Recorded decisions —
  committed to jawata-enterprise.
- PROCESS: the dogfood-in-anger + re-release rule folded into the /sprint
  skill's execution discipline (`~/.claude/skills/sprint/SKILL.md`) — the
  plan auditor now checks such a stage exists for every release a plan
  contains.
