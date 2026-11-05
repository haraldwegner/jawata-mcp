# Architect watch, C19-R4 (jawata-mcp f231efbd, 63954732; jawata-studio aa0ece1)

Watch mode, 2026-10-28. Filed from the seat's report; the seat read code and ran jawata queries on the javata-dev workspace and ran nothing. `jawata-project.md` was not read (the watch was scoped to three commits), so the four project facts are assumed, not quoted.

## Verdict per commit

- **f231efbd, S19-30: mixed, leaning bandage.** The ordering fix is right and the test is a real discriminator. The real defect is that "tail" means the last 100 lines of the first 1,000,000 characters, not the end of the run. Smallest design-level alternative: `StreamTail` becomes a true tail (a ring of the last N non-DEBUG lines, read by line) and the DEBUG count becomes a response field beside `stdoutTail`.
- **63954732, S19-63: mixed.** A design fix for run and start; a bandage for the rest: the same first-project line stood in two sibling actions (fixed afterwards in 6feb8b48), and the refusal pushes the ownership question to the caller with a list too short to answer it. Smallest design-level alternative: the workspace model answers which projects hold a type or package.
- **aa0ece1, S19-10: partial fix.** Four screen copies became one, which is real progress; the rule is still written at least four more times in Rust, and the two sides are bound by a text grep.

## Findings, ranked

1. **Incomplete delegation (fixed in 6feb8b48):** `handleCoverageImport` and `handleCoverageMutation` still took `workspace.projects().get(0)`. Closing test: `RunTestsNamesItsProjectTest#theCoverageActionsRefuseAnUnnamedProjectToo`, red before the guard.
2. **The refusal is a helper beside where the object should have the behaviour.** `IJdtService.findType` resolves a type across projects but is first-wins and out of reach of the tool, which sees only `LoadedWorkspace`. A `default` method on `LoadedWorkspace` that returns the projects holding a type or package would let the tool refuse only on a real ambiguity and list two keys instead of twenty. Ledger S19-63c, open.
3. **The 1,000,000-character capture cap is a real hole and the third face of one defect** (7b75b8b2, then fca9ab8a, then f231efbd): DEBUG bytes count against the cap, so after about 10,000 DEBUG lines the failure at the end of the run is gone while the header says "truncated" over lines from the start. Rule 12: the target is the run, not this patch. Ledger S19-30b, open.
4. **The live rule is written in Rust at four places and once in TS;** the grep gate saw two spellings and had no self-test (fixed in 48caa5f: both orders, both quotes, a self-test). The Rust side stays: `RuntimePhase::is_live()` carried as a field on `RuntimeStatusRecord` would remove the TS module and the gate. Ledger S19-10b, open.

## Consumers derived

Kind searched: clients of the tool's published argument and response shape, not callers of a Java symbol. Producer: one tool, `RunTestsTool` (29 constructor sites, one production at `ShippedTools`). Consumers in studio: `runner.rs` `TestWriterGates::run_suite_once` (package scope, no `projectKey`): in a many-project workspace it used to run the first project and now receives the refusal; the sandbox resident of its live tests holds one project. `coverage_mutation` calls in `runner.rs` pass `targetClasses` only. Seats and hooks name the tool only. `jawata-chat`: no mention. `stdoutTail` has no reader in studio, jawata-chat or jawata-enterprise sources. What the numbers do not mean: 29 is constructor sites, not callers of an argument shape; the studio list is a text match for `run_tests`.

## D4

- **nominate** (lanes experience and patterns), question: a tool takes a type or package scope with no project key in a many-project workspace: refuse and list, or let the workspace resolve the owner? 16 candidates, 1 selected: the lesson recorded for S19-63 (`run_tests` answers success with zero tests when the first project was run); it made the watch add the zero-discovery success as a separate finding.
- **No pattern selected.** The patterns lane returned Marker Interface, Dependency Injection, Arrange/Act/Assert, Separated Interface, Multiton, Double Dispatch, Onion Architecture and CRTP; none names the situation, a caller that must pick one of N owners. The fix is a method on an existing interface, not a new seam.
- **nominate** (lane code): which loaded project owns a source file path or a type name. 11 candidates, none selected; the only job-shaped nominee was the new code itself.
- D-SIX: no second implementation for S19-63 or S19-30; for S19-10 the rule exists in Rust and now once more in TS, which is the shape D-SIX names; the cure is one definition (finding 4). `src/lib` and `src-tauri/src`: NOT CATALOGUED, D-SIX not checked by the catalogue.

## Open question for the human

Is every consumer of `run_tests` inside this workspace? Other repositories, other teams, deployed configuration and third-party language providers of `TestRuntime` or `LoadedWorkspace` are invisible to a search. The answer decides finding 2: a `default` method keeps outside providers compiling, but removing "no key means the first project" is only safe if nobody outside depends on it.

## Below the fold

The file-scope branch of `testedProject` had no test (added in 6feb8b48). A run that discovered zero tests still answers success with a note. `StreamTail#tail` is synchronized and joins the gobbler thread while the gobbler takes the same monitor; read, not run.
