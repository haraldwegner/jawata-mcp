# Architect watch — the open Stage 16 rows (Sprint 28g)

**Mode:** watch, run as a fresh-context subagent on 2026-10-15 while the jawata engine was
unresponsive. **Degradation declared:** no `find_references`, `find_quality_issue`,
`compile_workspace` or `duplicate_check` could run; caller claims below are read from source or
from `git diff`. The seat is advisory: **nothing here is applied, and each design proposal waits
for a yes.** S16-14's finding was checked by the main loop afterwards (see its row).

Scope: S16-8, S16-9, S16-11, S16-13, S16-14, and the containment cluster S16-20, S16-21, S16-24,
S16-25, S16-28, S16-29, S16-30, S16-32, S16-33, S16-35.

## (A) The containment cluster — DESIGN FIX

**The discriminator the rows converge on:** can something other than the model refuse a write.
Each dialect already holds the fact that answers it, as a constructor field or an argv element.

New, in `org.jawata.mcp.execution`, modelled on `ToolAttachment`:

```java
record WriteContainment(Basis basis, String enforcedBy, String boundedInstead)
enum Basis { MECHANISM, INSTRUCTION, NONE }
// MECHANISM   -> enforcedBy required: names the refusal point outside the model
// INSTRUCTION -> boundedInstead required
// NONE        -> boundedInstead required
// BrainDialect gains `WriteContainment writeContainment();` with NO default, so a fifth
// brain cannot ship without answering (the same rule toolStepsOf follows).
```

| dialect | fact it reads | answer |
|---|---|---|
| codex | `sandbox` field == `read-only` | MECHANISM, "--sandbox read-only" |
| copilot | `readOnly` field | MECHANISM, "vendor headless default denial" (measured 6/6, and 3/3 without our flags). Our `--deny-tool` flags state intent; they do not create the bound |
| claude | `self != null` (argv carries `--tools ""`) | MECHANISM, "built-in editors absent from the process". With `self == null`: INSTRUCTION, now unreachable because `HireTool` refuses it (S16-36), but it must still answer |
| cursor | `readOnly` | INSTRUCTION ("--mode plan", 5 of 15 breached). `--sandbox enabled` is not named as a basis. `readOnly` false: NONE |

**Witness the claude answer from the log.** `BrainLine` already carries `toolsOffered` from the
init event. `HiredRun` should record `writeContainmentWitnessed` = the offered tools contain none
of Edit, Write, NotebookEdit. For codex and copilot a refusal only appears if the brain attempts a
write, so their record says "declared, not witnessed" — not a failure.

**A hard-coded claim to fix in the same change.** `HiredRun.provenance` puts `applied: false` and
`render` prints "NOTHING HERE WAS APPLIED". Both are asserted, not read, and the changed-file
census sits in the same method. Derive both from the census.

**Policy once.** `SeatHire.dialect` passes `readOnly = true` for claude and cursor; copilot and
codex take their defaults inside their constructors. Pass one `readOnly` from `SeatHire.dialect`
to all four.

**Strings replaced.** The containment prose inside `toolAttachment()` shrinks to tool reach only;
provenance gains `writeContainment` and `writeContainmentWitnessed`; `render` gains a "- writes:"
line; the measurement narrative moves to the dialect javadoc.

**S16-35 sweep.** The table is the sweep's output for the write axis. Its test: for each dialect,
argv contains the flag the answer cites. A cited flag absent from argv fails.

**A hire whose brain is INSTRUCTION-only (cursor today) — DECISION for Harald:**
1. Refuse by default, like S16-36, with a per-seat opt-in line. Cursor becomes unhireable unless a
   seat opts in.
2. Allow it, and bound it by running the brain in a throwaway copy of the working directory. A real
   mechanism, but a copy per hire, and it does NOT bound off-tree writes (S16-21, S16-33).

Seat recommendation: option 1.

## (B) The design rows

| row | verdict | smallest action | actuator |
|---|---|---|---|
| S16-8 | DECISION | `answer_gates` IS read (`HiredRun.withAnswerGates`); the unread field is `gates:`. (a) delete `gates:` — the hirer owns tree gates, the seat owns answer gates, which is what ships; (b) let `gates:` seed default steps when the hirer gives none, which changes `HireTool`'s refusal of empty steps. Recommend (a) | `SeatHire.read` rejects an unknown `gates:` key, or the field is removed |
| S16-9 | DESIGN FIX | `ToolRegistry#hasTool` is `containsKey`, and `getTool(name).isPresent()` answers the same question: a second implementation. Delete it; its four tests use `getTool` | `inline(kind=unreferenced_member)`, then compile |
| S16-11 | BANDAGE | An unreachable backstop is the symptom. Smaller design: make `callTool` total, so it throws only `ToolNotFoundException`, by covering its pre-dispatch machinery; test with a failing precedent charge or acceptance check; then delete the catch | red-before test at the registry boundary; conditional on a fault-injection seam existing there |
| S16-13 | DESIGN FIX | The placeholder argv in `HireTool` is a second argv source. Replace the `HostCommand base` parameter of `issue` and `hire` with a type that has no argv | the compiler |
| S16-14 | DECLINE as a finding (answered) | `git diff v4.3.3..HEAD` on `RefactoringDoors.java` adds `DeleteAnonymousImplementationTool` inside `all()`, reached by a loop in `JawataApplication`, not by a `register(` call. Counting `register(` sites was the wrong instrument | the end-to-end gate that pins the published count stays |

## (C) Codeable now, with a red-before test, once a design is accepted

1. `WriteContainment` and its invariants (red: MECHANISM built without `enforcedBy` does not throw).
2. The per-dialect answers.
3. Provenance and `render` carry the basis and the witness.
4. `applied` and the header derived from the census (red: a run with changed files still reports
   `applied:false`).
5. The claude witness from `toolsOffered`.
6. `SeatHire.dialect` passes one `readOnly` to all four dialects.
7. S16-9's deletion, and S16-13 if the compiler-led change is accepted.

**Needs a ruling:** the INSTRUCTION-only policy, S16-8 (a or b), and whether cursor's
`--sandbox enabled` flag stays (harmless, but not a basis).

## (D) NOT RUN

- `find_references` on `hasTool` and `HostCommand.argv`.
- `compile_workspace` and `get_diagnostics`.
- `find_quality_issue` and `duplicate_check` (D-SIX) on `WriteContainment`; `ToolAttachment` is the
  evident sibling to extend.
- Whether codex's `read-only` sandbox and copilot's denial also cover off-tree writes (only the
  working tree was probed).
- Whether copilot's `--available-tools` names are ones the CLI recognises.
- Whether a fault-injection seam exists in `ToolRegistry` for S16-11.
