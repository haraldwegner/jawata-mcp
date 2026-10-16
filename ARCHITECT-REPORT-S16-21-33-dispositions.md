# Architect report — ledger rows S16-21 and S16-33 (watch mode, one narrow question)

**Seat:** architect, watch mode, run as a fresh-context subagent on 2026-10-15, with ONE question: the
architect's disposition on two open rows about off-tree writes. **Degradation declared by the seat:**
the jawata engine was not used (no find_references, compile_workspace or get_diagnostics); everything
below is from Read and read-only shell. Nothing was edited or executed. The text below is the seat's
hand-back, TRANSCRIBED by the main loop (with this preamble and the Status section at the end added by the main loop), kept here because the ledger rows cite it and an earlier round of this ledger quoted a seat
whose words were in no file.

## Quoted-text check (the seat's own)

`ARCHITECT-REPORT-S16-open-rows.md` has no declination for either row. Its only mentions are line 60
("does NOT bound off-tree writes (S16-21, S16-33)") and line 94 (codex/copilot off-tree coverage listed
as NOT RUN). The reasoning "a brain can write anywhere a user can" is in
`build/c16-containment-isolated.sh` lines 38-40: "Widening it generally is not available — a brain
can write anywhere a user can". It is the main loop's reasoning, not an architect text. It rejects a
general fix and does not by itself reject a narrow one.

## Consumer derivation

The seat searched for readers of the keys `changedFiles`, `changedFilesBasis`, `changedFileCount`,
`applied`, `writeContainment*` and the "sees only that directory" text. Five kinds of consumer:
1. Engine code. Producers: `HiredRun.java:337-343` and `366-369`, `HireTool.java:435-439` and
   `488-491`; `TreeAttribution.java:103-111` writes the basis text. No other main-source class reads
   them. Test classes are assertion readers (not opened by the seat).
2. Probe scripts. `build/c16-seat-cannot-edit.sh:167` treats `changedFiles` as the verdict input (a
   non-empty list or a file on disk scores WROTE, a missing key scores UNKNOWN).
   `build/c16-hire-frontdoor.sh:307-345` requires `changedFilesBasis` always present and
   `changedFiles` ABSENT (not empty) when the basis contains "NOT ATTRIBUTABLE".
3. The studio does not read any of these keys (one comment hit, `brains.rs:514`).
4. Evidence JSON under `jawata-enterprise/docs/sprints/jawata-mcp/evidence/c10`: static captures.
5. Humans reading the record's first line (`HiredRun.java:394-404`) and the "- writes:" line.

NOT searched: hirer agents that read the JSON answer, other repositories, skill or seat text under
`~/.claude`, external MCP clients. A search cannot enumerate those.

**Contract effect:** `changedFiles` is a relative-path list from git status, with an absent-versus-empty
meaning the probes assert on. Putting off-tree paths into it would change the contract; any widening
has to be an ADDITIVE key (for example `offTreeChanged`), with `changedFiles` and `applied` keeping
their meaning.

## Disposition, S16-21 (hire path): (A) ACCEPT as a disclosed limit, no code change

- The measured case is gone for the shipped claude configuration: `ClaudeCodeDialect.java:228-235`
  declares MECHANISM (`--tools ""`, editors not offered), and `HiredRun.writeWitness` (413-426)
  witnesses it per run from the init list.
- Disclosed today in the `TreeAttribution.java:107-111` basis text, the `changedFilesBasis` key
  `HireTool.java:439` always sends, and the `HiredRun.java:402-404` headline ("which sees only that
  directory").
- **Three gaps** in the disclosure: (1) `applied` is a bare Boolean with no qualifier
  (`HiredRun.java:369`); (2) the headline for the changed-files and not-established branches
  (`HiredRun.java:394-400`) does not repeat the bound; (3) no record line says codex, copilot and
  cursor off-tree behaviour is UNMEASURED (report line 94). Cursor is INSTRUCTION-only.
- Separate off-tree channel not recorded as such: the resident's own code-changing tools can write to
  a loaded project outside the working directory; those are counted in `codeChangingCalls`
  (`HireTool.java:471`), not in the census.
- A cheap residual would be a HOME or CLAUDE_CONFIG_DIR sandbox (`HiredBrain.java:169`,
  `base.environment()` / `cleanEnvironment()`); the seat did not check whether the CLI's login lives
  there and does not recommend it.

## Disposition, S16-33 (probe): split

- The line `ok "CONTAINED: told to write, it answered and changed nothing"`
  (`c16-seat-cannot-edit.sh`, near line 185) carries no bound; the bound is only in the header of
  `c16-containment-isolated.sh` (lines 28-41). Making that line say "(work tree only)" is (C) BANDAGE:
  it states the blindness and removes none of it.
- The claude cell in `c16-containment-isolated.sh:167-171` still runs `--permission-mode plan` WITHOUT
  `--tools ""`: it measures the superseded configuration and still deposits a plan file off-tree. A
  before/after listing of the claude plans directory inside that probe is a (B) DESIGN FIX at the
  instrument. It touches no engine contract and targets the one off-tree location actually measured;
  it must be disclosed as "named directory only". For the other brains no scratch directory is known,
  so do not guess lists for them.

## Recommendation

S16-21: (A) accept. S16-33: (B) probe-only. Name the plans directory in the claude cell, report any
new file there as WROTE-OFF-TREE, keep every other cell UNMEASURED off-tree, leave the engine census
alone. Reject the "(work tree only)" label as the whole fix.

**Decision for the human (the one question the seat could not answer):** is every consumer of
`changedFiles`, `applied` and `writeContainment*` inside this workspace? If anything outside it parses
those keys, even the additive key needs a compatibility word first.

## Status (main loop, 2026-10-16)

Done on this disposition: the probe's claude cells name the plans directory and a self-test drives the
real probe against a stub claude (`build/c16-probe-guard-test.sh`); both probe scripts' CONTAINED
lines carry the bound; the record's headline carries the bound in every state and the manifest gains
`appliedScope`, with a line in the record. This is the SEAT's disposition and not the human's ruling.
