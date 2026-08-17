# ARCHITECTURE — Sprint 28b: field recordings, /report, seat lane

Design-mode artifact (architect seat), 2026-08-17, against the signed spec
`jawata-enterprise/docs/sprints/jawata-mcp/sprint-28b-sanitized-feedback.md`.
Scope is cross-repo (jawata-mcp + jawata-studio); this file is the baseline
every 28b checkpoint diffs against.

## The picture

```
 jawata-mcp (Java, resident)                 jawata-studio (Rust)
 ┌────────────────────────────────┐          ┌───────────────────────────────┐
 │ tools/* ──▶ domain.Outcome     │          │ jawata-hook binary            │
 │              (Sprint-26 tap)   │          │  ├ observer: nudge inject D4  │
 │                 │ observes     │          │  ├ reminder inject       D9   │
 │                 ▼              │          │  ├ counters (append-only) D5  │
 │ field.FieldRecorder      D1    │  HTTP    │  └ contract ver in requests D7│
 │   │ enum-typed FieldEvent      │◀─────────┤        │ append                │
 │   ▼                            │          │        ▼                       │
 │ field.FieldPile (JSONL,        │          │ <workspace>/field/*.jsonl      │
 │   append-only, versioned hdr)  │─────────▶│        ▲ fold-at-read          │
 │ field.FieldTool          D3/D9 │  files   │ studio UI                      │
 │   pile·mark_posted·silence     │          │  ├ field view + banner   D2    │
 │                                │          │  ├ seat lane + /report tile D10│
 │ knowledge.* symptom            │          │  └ canary timer          D6    │
 │   normalization (REUSED for    │          │ seats/report.md → skill  D3    │
 │   shape dedupe — never copied) │          │   (existing deploy machinery)  │
 └────────────────────────────────┘          └───────────────────────────────┘
        agent ──▶ /report seat (skill) ──▶ FieldTool ──▶ gh issue create (user's own account)
```

## Modules and responsibilities

**jawata-mcp — new package `org.jawata.mcp.field`** (one package, nothing
scattered):
- `FieldEvent` — the sanitizer AS A TYPE: a record of enums and ints only
  (tool, kind, ok, errorCode enum, latencyBucket, client, version, priorTool).
  No `String` field exists, so no path/message/symbol CAN be stored. Pattern:
  allowlist value object; smell prevented: the leak class dies at the type
  level instead of in a filter.
- `ErrorCodes` — the single mapping exception/failure → error-code enum. The
  leak corpus tests THIS seam.
- `FieldRecorder` — observes the `domain.Outcome` tap (Sprint 26; consumers
  today: `Advisor`/`ExperienceAdvisor`). Pattern: Observer; smell prevented:
  per-tool emission code (shotgun surgery).
- `FieldPile` — append-only JSONL under `<workspace>/field/`, versioned
  header, fold-at-read. Pattern: event log; smell prevented: the silence.rs
  read-modify-write corruption class (the standing prohibition: any file a
  hook or resident writes concurrently is append-only or per-process).
- `FieldTool` — the ONE new MCP front door (actions: pile, mark_posted,
  silence get/set, counters). Pattern: facade; smell prevented: tool-count
  creep (the collapse-to-39 lesson). The agent sets the go-silent state
  through it; studio's checkbox writes the same state file atomically.
- Shape dedupe REUSES the store's symptom normalization from `knowledge.*` —
  exposed via a seam, never duplicated (the duplicate-implementation dead-fix
  lesson).

**jawata-studio — hook binary** (`src-tauri/jawata-hook`):
- `counters` — per-channel fired/emitted/suppressed + bounded reason enum,
  append-per-process, folded by studio. Includes `VERSION_MISMATCH`.
- `nudge` — observer-path injection, once per Nth-recurring shape (D4).
- `reminder` — session-start injection when due (weekly, news-gated), strike
  count, third-onward carries the go-silent question (D9).
- D8: the remaining observer-half ports; `role_generations` flips only when
  the binary carries both halves.

**jawata-studio — UI**: field view + banner (D2), seat lane with tile-per-seat
model shipping only the `/report` tile (D10; pattern: composite — 28f fills
the lane instead of building a second one), canary timer (D6). **No OS
notification API is called anywhere** (the no-pop-ups ruling).

## D7 — the version-handshake DECISION (recorded here per the spec's measure)

**A contract version crosses the studio↔store seam.** The hook binary sends a
`contract` integer with each request; the store echoes its own; a mismatch is
a TYPED, counted outcome (`suppressed reason=VERSION_MISMATCH` in D5's
counters, visible in D2's view) — never silence. The pile files carry the same
version in their header, because studio reads them directly. Rationale: the
21c→27a semantic drift was invisible precisely because both sides stayed
individually green; two integers make the seam's next drift loud. Cost: one
field on each side plus one enum constant.

## Dependency direction (who may know whom)

- hook binary → resident (HTTP, existing) and → its own append files. Never →
  studio.
- studio → workspace files (fold-at-read) and → resident via the existing
  manager channel. Never → a live agent session.
- `field.*` → `domain.Outcome`, `knowledge` normalization seam. Nothing in
  `tools/*` (other than `FieldTool`) knows `field.*` — recording is a tap,
  not a call.
- The `/report` skill → `FieldTool` + the user's own `gh`. No jawata
  credential exists anywhere (spec D3).

## Must not be touched

- The guard half of the hook binary (shipped 28a, v3.9.x) — D8 is observer
  only.
- Recall ordering and the 28e fixes (v3.10.0) — the normalization seam is
  read-only reuse.
- Release workflows; client config deployment; the existing tool surface
  beyond the one `FieldTool` addition.
