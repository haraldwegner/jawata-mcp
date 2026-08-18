# Dossier — Sprint 28b (field recordings, /report, seat lane)

Execution record. Every number carries the command that produced it, and every
claim its measurement. Spec: `jawata-enterprise/docs/sprints/jawata-mcp/sprint-28b-sanitized-feedback.md`
(signed at Gate 1, 2026-08-17). Design: `ARCHITECTURE-field-recordings-28b.md`.

---

## Baselines · 2026-08-17

| Gate | Command | Result |
|---|---|---|
| mcp suite | `./build/run-suite.sh` | 1842 total · 0 failed · 2 expected aborts · 341s |
| studio | `cargo test` in `src-tauri` | 327 passed · 0 failed · 7 ignored |

## Stages and their commits

| Stage | Ships | Commits |
|---|---|---|
| 0 | leak corpus + three hook fixtures | mcp `7b7ad71` · studio `1b147b1` |
| 1 | D1 recording engine, D7 contract echo | mcp `5377cea`, `8629433`, `7ed7055` |
| 2 | D5 counters (fold), D8 observer port + cutover | studio `a934405`, `1625779`, `455c849`, `e883222` |
| 3 | D3 field tool + /report seat, D4 nudge | mcp `ba21e16` · studio `d89a099` |
| 4 | D9 reminders | studio `69abb65` · mcp `c2fa0a4` |
| 5 | D2 view, D10 seat lane, D6 canary | studio `8e86239` |
| — | architect-directed fixes (R1–R4) | mcp `f1839d4`, `0feea0a` · studio `b16cc26`, `6a06f8f` |

## What the gates caught that the code did not

Five defects reached a green suite before an audit found them. Recorded because
the pattern matters more than the instances:

1. **A false green, twice over.** `run-suite.sh` executes the last INSTALLED
   dist, so a suite run without `mvn install` reports on code that was never
   built; and `| tail -3` replaces the runner's exit code with tail's zero. The
   tell was the total not moving. Gates now run install-then-suite with
   `set -o pipefail`, and the counts are read rather than the exit status.
2. **A whitelist that let an identifier-shaped secret through.** The token
   grammar accepted digits, so a 38-character API-token lookalike passed as a
   "tool name". Tokens are now digit-free; versions get a parsed three-int type.
3. **A transform that laundered content past its own gate.** Client names were
   normalised by replacing punctuation with underscores — which preserved the
   content the allowlist existed to reject, and defeated the leak test's
   substring check. Replaced by a closed vocabulary.
4. **A tag nothing constructed.** `answer-unusable` was declared, classified and
   never produced: shape drift and tool refusals still folded into
   `query-failed`, which the dead-channel condition ignores. The alarm added for
   the two-week outage could not have fired for it.
5. **A capability with no control on its wiring.** Reverting the nudge's call
   site — and separately the reminder's — left every test green. Both are now
   pinned by tests that go red when the call is deleted (verified by deleting).

## The architect escalation · 2026-08-18

The plan's trigger fired (a defect introduced by a fixing commit, twice). The
architect's diagnosis, which outranks its individual findings:

> This codebase writes its preconditions in javadoc and enforces them nowhere.

Five sites, one per system reviewed: `profile` promising it handles the no-model
case and not guarding it; `AbstractTool` promising a non-null service and passing
null; `FieldState` documenting the append-only prohibition and doing a
read-modify-write in the same class; `conductor.rs` claiming the seat files are
the single source while the truth lived in Rust arrays; the observer documenting
four nudge conditions with an undocumented fifth.

Ranked risks R1–R5. R1–R3 executed (test axis · seat registration with its
consistency test · nudge arbitration) plus the profile guard. **R4 (the
`executeWithoutService` split) and R5 (splitting `state.json` by the nature of
its data) are HELD for Harald's ranking** — R5 crosses a release boundary
because a released hook binary reads the current format.

Open question for Harald, which no instrument here can answer: are all consumers
of those two contracts inside these two repositories?

## Corrections to my own claims

- I reported the profile defect as a live null-pointer. Measured: the unguarded
  path answers `JCMD_FAILED` first, because the session check precedes the model
  lookup — the null dereference needs a live debug session AND an empty
  workspace. The guard is right; my description of its reachability was not.
- `/report`'s registration is committed, but its appearance in a client tree
  needs a Studio redeploy that has not been exercised headlessly. UNPROVEN until
  Stage 6 says otherwise.
