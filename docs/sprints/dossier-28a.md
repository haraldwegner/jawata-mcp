# Dossier — Sprint 28a (client + OS coverage)

Execution record. Every number carries the command that produced it.

---

## Stage (−1) — cold-start orientation · 2026-08-12

**Workspace health** — `health_check` on both jawata MCP workspaces:

| Workspace | Projects | Healthy | Tools |
|---|---|---|---|
| `jawata-javata-dev` | 4 (jawata-mcp + 3 seat fixtures) | ✅ all | 45 |
| `jawata-orb-strategy` | 29 | ✅ all | 45 |

Both on server v3.7.1, Java 21.0.10 (Ubuntu), embedder
`sentence-transformers/all-MiniLM-L6-v2/384/v1` on the Vector API backend, 8 lanes.

**Toolchain** — `mvn --version` · `java --version` · `cargo --version` · `node --version` ·
`gh auth status`:

    Maven   3.9.9
    Java    openjdk 21.0.10 2026-01-20
    cargo   1.95.0 (f2d3ce0bd 2026-03-21)
    node    v22.22.2
    gh      logged in as haraldwegner, https

**Repository state** — `git status --short` + `git branch --show-current` per repo:

| Repo | Branch | Head | Working tree |
|---|---|---|---|
| jawata-mcp | main | `f66a03c` | clean |
| jawata-studio | main | `b5dc5ea` | `skills/sprint.md` modified (round cap + auditor checks 12/13) |
| jawata-enterprise | main | — | the 28a spec/raw + four sprint docs + marketing, uncommitted |

**Recall hit that changes how the sweeps must be run.** Store finding #4 (2026-07-19): a
silent edit feed was traced to a **stale app install** — the deployed hook scripts carry
**no version stamp**, so an out-of-date Studio is invisible and every probe run against it
reports on the wrong build. *Consequence:* each sweep stage (7, 9, 11) must first prove the
installed Studio matches the build under test. A sweep against a stale install produces
findings that are worse than none, because they look real.

---

## Stage 0 — baselines · 2026-08-12

### Clients present on this Linux machine

`command -v` per client:

| Client | On PATH |
|---|---|
| Claude Code | `~/.local/bin/claude` |
| Cursor | `~/.local/bin/cursor` |
| Codex | `~/.nvm/versions/node/v22.22.2/bin/codex` |
| Copilot CLI | `~/.nvm/versions/node/v22.22.2/bin/copilot` |
| Grok | `~/.local/bin/grok` |
| Antigravity | `~/.local/bin/antigravity` |
| VS Code | `/usr/bin/code` |
| IntelliJ | no CLI launcher on PATH (its config directory exists — installed, but not launchable by name) |

### Which config files carry a jawata entry today

`grep -qi jawata` per known config path:

| Config | State |
|---|---|
| `~/.claude.json` | has jawata |
| `~/.cursor/mcp.json` | has jawata |
| `~/.codex/config.toml` | has jawata |
| `~/.config/JetBrains/IntelliJIdea/mcp.json` | has jawata |
| `~/.gemini/antigravity/mcp_config.json` | has jawata |
| `~/.config/Claude/claude_desktop_config.json` | exists, no jawata |
| `~/.config/Code/User/settings.json` | exists, no jawata |
| `~/.copilot/mcp-config.json` | absent |
| `~/.grok/mcp.json` | absent |

**Read this carefully before concluding D1 is partly done.** Codex and IntelliJ already
carry jawata entries — but those were **written by hand during the 2026-08-11 probing
session**, not deployed by Studio. D1's deliverable is *Studio deploys them*; the file
existing proves nothing about the adapter. Grok's absence is expected: it reads the files
Studio writes for other clients rather than having its own.

### The shipped README's parity claim — a defect, not just staleness

`jawata-mcp/README.md:125`, verbatim:

> **Honest client parity:** the tools and the guard are identical on every MCP client. The
> memory *auto-push* (session primer, prompt-boundary recall, on-edit recall) is **full on
> Claude Code** and **best-effort on Cursor** …

**"the guard are identical on every MCP client" is false.** The guard runs on Claude Code
and Cursor — two of six measured clients. D3 treats this as a sentence to rewrite from the
matrix; it is also a wrong claim shipping in the product's front-page documentation today.

### Platform-gated steps in the studio release workflow

`grep -B3 "if: matrix.platform == 'ubuntu-22.04'" .github/workflows/release.yml`:

| Step | Line |
|---|---|
| Test the studio crate | 73 |
| Cross-product seam gate (hook binary vs published store) | 87 |
| Hollow-wiring gate (test-only items) | 179 |

The hook crate's own suite (`Test the hook crate`, line 60) carries **no** platform
condition — it runs on all five targets. That asymmetry is what Stage 2's filtered
all-platform step is modelled on.

### The CI matrix, as it stands

`jawata-mcp/.github/workflows/ci.yml`:

    on:
      pull_request:
        branches: [main]

    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest, macos-latest]
      fail-fast: false

Test step carries `timeout-minutes: 75`. Confirmed by `gh run list --workflow=ci.yml`:
**no runs, ever.**

### The wait clock

Measured durations feed the W1–W4 pairing in the plan. Filled as each is observed rather
than estimated:

| Wait | Job | Measured |
|---|---|---|
| W1 | three-OS CI matrix | *unknown until Stage 1's first push — it has never run. Test step allows up to 75 min per OS.* |
| W2 | studio crate suite (`cargo test --release`) | **50–54 s** warm (build cached). Cold, with a release build, it is longer — measure again at Stage 2 |
| W3 | jawata-mcp full suite | **~10 min** (13:15:15 → 13:25:28) on a warm build. The `mvn -B -f build/pom.xml install` ahead of it was 11 s incremental; CI builds cold, so the CI figure is larger |
| W4 | studio release workflow | *pending* |

### jawata-mcp suite baseline

    java --add-modules jdk.incubator.vector \
         -Djawata.test.fixtures=$PWD/org.jawata.core.tests/test-resources/sample-projects \
         -Djawata.experience.shared.dir=/tmp/jawata-28a-test-store \
         -jar build/dist/target/dist/jawata.jar -runTests

    SPIKE-TESTS total=1782 succeeded=1780 failed=0 aborted=2 skipped=0 unloadable=0
    exit=0

### The aborted tests, named — and one is a finding

Captured from a full run (`-runTests` with output kept rather than tailed). Two runs gave
**2** and **3** aborted from the same 1782, so one of them is intermittent.

| Aborted test | Its own reason |
|---|---|
| `criterion_c_the_paraphrase_case_returns_the_ratchet_lesson()` | "[CRITERION C] NOT RUN — no corpus at `-Djawata.embed.corpus`; D1's third measure is unverified in this run." |
| `the_calibration_cues_are_answered_from_the_real_corpus()` | "[E2 GATE] NOT RUN — no corpus at `-Djawata.embed.corpus`. **This is the sprint's headline gate; it is unverified in this run.**" |
| `wall dimension: a method that BLOCKS ranks above one that only burns CPU` | "CPU profile too thin to rank … the top row carries 1 sample(s), under the 3 needed … the recording never caught the thread often enough." *(This is the intermittent one — present in run 2, absent in run 1.)* |

**FINDING — Sprint 27's headline gate does not run in CI, and never has.**
`grep -rn "embed.corpus" .github/workflows/` returns **nothing**: no workflow sets
`-Djawata.embed.corpus`, so both corpus-dependent tests abort on every CI run. The E2
calibration gate describes itself, in its own abort message, as "the sprint's headline gate"
and as "unverified in this run."

This is the same defect class as the never-executed matrix, one level in: a gate that exists,
is well-written, is honest about not running — **and nothing runs it.** Credit where due: the
abort messages are exemplary. They say precisely what did not happen and why, which is the
cure for "an empty result on failure is a lie" working as designed. The gap is that nobody
supplies the corpus.

---

## Stage 1 — the three-OS matrix, first run ever · 2026-08-12

PR [#13](https://github.com/haraldwegner/jawata-mcp/pull/13), run `31591325046`, fired by the
pre-existing `pull_request` trigger — so the matrix ran without depending on the new
push-trigger being correct.

| Job | Result |
|---|---|
| `gradle-cell` (Linux-only) | ✓ 1m25s |
| `build (ubuntu-latest)` | 1782 total, **1779 succeeded, 0 failed**, 3 aborted — identical to the local run |
| `build (macos-latest)` | ✗ **1775 succeeded, 5 failed**, 2 aborted, 15m47s |
| `build (windows-latest)` | still running |

The workflow's own comment states "Exit code = failed-test count"; macOS exited **5**.

### The five macOS failures

Four of the five share one shape, and **in every one the expected path IS present in the
returned list** — spelled `/private/var/...`:

| Test | What it got |
|---|---|
| `ProjectImporterGradleToolingTest#gradle_customSrcDir` | `[/private/var/folders/…/custom-src, …/custom-test]` — "Custom src dir should be reported" |
| `ProjectImporterGradleToolingTest#gradle_returnsActualSourceSets` | `[/private/var/folders/…/src/main/java, …/src/test/java]` — "Standard src/main/java should be reported" |
| `ProjectImporterGradleToolingTest#gradle_returnsActualDependencies` | `[/private/var/folders/…/libs/dummy-1.0.0.jar]` — "Declared file-based dependency should appear" |
| `RunTestsShapesTest#gradleProject_runsThroughSpine` | compile error: "GradleShapeTest.java:6 The type GradleShapeTest is already defined" |

On macOS `/var` is a symlink to `/private/var`. The Gradle Tooling API returns canonicalised
paths while the test's temp directory is the uncanonical spelling, so a `contains()` check
fails on a path that is in fact correct. The fourth — a duplicate-class compile error — is
**plausibly downstream** of the source-set misdetection (the same file reached the compiler
twice), but that is inference, not measurement, and it is not yet confirmed.

**What is NOT yet established: whether this is a test defect or a product defect.** If jawata
should canonicalise paths before comparing, the tests are right and the product is wrong on
macOS; if the tests should compare canonicalised forms, the reverse. Determinable, not
determined. It matters: a user on macOS who asks about a file by its `/var/...` path hits the
same mismatch.

The fifth is separate and genuinely platform-specific:

- `NativeTriageTest#correlationMatchesAcrossTheOffsetSuffix` — the crash-symbol correlation.
  The macOS stack carries `_pthread_start+0x88`, `ThreadJavaMain+0xc`, `JavaMain+0x910`;
  the expectation is Linux-shaped.

### Deprecations surfaced by the first run

`actions/checkout@v4`, `actions/setup-java@v4`, `actions/upload-artifact@v4` and
`gradle/actions/setup-gradle@v4` are being force-migrated off Node 20; `setup-java@v4` is
end-of-life. Not failures, but on a clock — recorded rather than fixed mid-stage.

### Studio suite baseline — `cargo test --release` in `src-tauri`

| Binary | Passed | Ignored |
|---|---|---|
| `jawata_hook` lib | 128 | 0 |
| hook integration tests (11 files: dependency_edges, explain_runs_the_real_path, fail_safe_boundary, no_panics_at_fire_time, role_events_match_the_deploy, role_generations_match_the_dispatch, runtime_states, sibling_channels, silence_log_contract_matches_the_deploy, stop_rule_parity, transport) | 42 | 0 |
| `jawata_studio_lib` | 285 | 6 |

`fail_safe_boundary` is the slow one at 11.3 s — it spawns the real binary per case.
The **285** figure is now measured rather than counted from `#[test]` attributes; an earlier
audit had cited an unsourced "246".

### ⚠ An unidentified intermittent in `jawata_studio_lib` — recorded, not diagnosed

**Observed twice**, both `test result: FAILED. 284 passed; 1 failed; 6 ignored`. **Then five
consecutive isolated runs green.** The failing test's name was not captured either time —
my error: the first run was piped through `tail -40`, and the second correlated across two
separate `cargo test` invocations in one shell command.

What is factual: both failures occurred in shell commands where **another cargo invocation
had just completed or was running in the same command**; five runs with a single invocation
each are green.

What is **not** established: the cause. A contention story fits the shape, and this project
bans exactly that reflex — five green runs are not evidence of a mechanism, and the standing
rule is that a re-run is how a lying test stays hidden.

*Carried as a watch item.* It is not blocking Stage 0. If it recurs, capture the FULL output
of a single invocation — the name is the whole finding. It matters most at Stages 7/9/11,
where a false red during a sweep costs a debugging cycle and trust in the gate.

## Watch item: ExperienceToolHygieneTest#prune — Windows intermittent, name CAPTURED

First failure in 8 Windows matrix runs, on a release-notes-only commit:
run 31814248338 (fail 15:16) vs run 31808099077 (pass, same code, 40 min
earlier) vs the rerun of 31814248338 itself (pass 15:49, identical commit).
Same bytes, fail-then-pass — non-determinism proven, not inferred.

The test: `prune_removes_only_aged_rejected_or_superseded` — age-based
pruning, i.e. the timing-sensitivity family (fixed clocks/sleeps racing a
slower runner) whose cure is already twice-applied in this sprint:
wait-for-condition with a deadline, or an injected clock. Unlike the studio's
old unnamed 284/1 intermittent, this one has its name and both run ids.

Home: the 1b hardening batch (alongside the abort-budget gate). Not a
release blocker — v3.7.2 shipped green and the flake predates nothing in it.
