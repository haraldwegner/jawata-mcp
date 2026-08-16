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

---

# Stage 2 — four tool adapters and the deploy-path proof (D1, R13a)

Repo: **jawata-studio**. Date: 2026-08-15.

## What the roster gained

Three new clients — **Codex**, **Copilot CLI**, **VS Code** — bringing the deploy roster
from five to eight. IntelliJ was already on it. Antigravity stays on it, unsupported.

## Every client fact here was measured, not recalled

The failure this project records most often is a fact produced from memory and reported in
the language of measurement. Client config paths and schemas are exactly that hazard, so
each one was obtained by **making the client's own tooling write a config in a sandboxed
HOME**, then reading the file back:

| client | command run | what it wrote |
|---|---|---|
| Codex | `codex mcp add jawata-probe --url http://…` | `~/.codex/config.toml`, `[mcp_servers.<id>]` with `url` |
| Codex | `codex mcp get jawata-javata-dev` | echoed `http_headers: Authorization=*****` — confirming the header key |
| Codex | wrote `enabled = false`, re-read | reported `jawata-probe (disabled)` |
| Copilot CLI | `copilot mcp add --transport http …` | `~/.copilot/mcp-config.json`, root `mcpServers`, entry `{tools:["*"], type:"http", url, headers}` |
| Copilot CLI | `copilot mcp --help` | names `~/.copilot/mcp-config.json` as the User source |
| VS Code | `code --user-data-dir=… --add-mcp '{…}'` | `<user-data>/User/mcp.json`, root **`servers`**, plus a sibling `inputs: []` |

Two of those measurements overturned what a reasonable guess would have produced:

1. **VS Code's root key is `servers`, not `mcpServers`.** Every other client on the roster
   uses `mcpServers`. Writing there would have produced a perfectly-merged file under a key
   VS Code never reads — a deploy reporting success and doing nothing, which is this
   project's recorded deepest bug class.
2. **Codex spells "off" as `enabled = false`, not `disabled = true`** — the opposite
   polarity from every JSON client. `disabled` is a key Codex has no concept of, so it
   would have been ignored and the server left running while the deploy reported success.

## The design change: the dialect became a value

Before this stage the per-client differences lived as bare string literals scattered across
the writer, the remover and the validator — `"mcpServers"` appeared in three places and
`client == "antigravity"` in two. Adding a client meant finding all of them, and missing one
is silent.

`src-tauri/src/client_dialect.rs` (new, leaf module) now answers, per client: the file
format, the server-map key, the URL field name, and which entry extras it carries. Nothing
downstream branches on a client name. This is the same move `org.jawata.core.host` made for
the operating system in 1b — **the varying knowledge becomes a value with one owner**.

Codex forced a second writer: TOML. `serde_json` round-trips JSON losing nothing a client
cares about, but a plain TOML serializer destroys every comment and hand-chosen layout in
the user's file. Codex's own `codex mcp add` preserves them, so a jawata deploy that did not
would be visibly worse than the tool it sits beside. `toml_edit` (the format-preserving
editor cargo itself uses) is a new dependency for that reason, and the merge test asserts a
user's comment survives.

## A product defect the work surfaced

**The gateway's consolidated entry could not be removed.** With `gateway_enabled` on,
`gateway_entry` writes ONE server keyed `jawata` — no hyphen, no workspace suffix — while
every branch of `is_managed_mcp_key` required `jawata-`. So the gateway entry was not
recognised as ours: an undeploy reported "nothing to remove" and left it pointing at a
gateway no longer running, and `path_has_managed_entries` could not see a gateway-deployed
client at all.

Found because the Codex removal test used the bare id and failed for the same reason the
product did. Fixed here; pinned by a test that asserts against the **production
constructor** (`gateway_entry`) rather than a literal, so renaming the entry cannot quietly
re-open it. Gated behind a setting that is off by default, which is why it survived.

## The gap the no-clobber tests cannot close, and what closes it

D1 requires each new adapter to ship the four no-clobber shapes, and they do — 12 tests
across the three clients, plus 4 more for the TOML-specific hazards. The spec states two
limits on what they prove and they are worth repeating: they run on **Linux only**, and they
take the settings-file path as a **parameter**. So they prove the merge and remove logic and
say nothing about *which directory a deploy resolves to* — the only platform-varying part,
and exactly where a clobber bug lives.

R13a's answer: a `deploy_resolves_here` module that runs the real resolver and asserts the
shape for the operating system it is running on, wired into `release.yml` as a
**name-filtered step with no platform condition**. It runs on all five release targets. This
is the first time any of these paths is checked anywhere but Linux.

The specific hazard it is aimed at: `dirs::config_dir()` returns `~/.config` on Linux,
`~/Library/Application Support` on macOS and `%APPDATA%` on Windows. VS Code and Claude
Desktop key off it; Codex and Copilot key off the home dir. A client wired to the wrong one
passes on Linux and is silently wrong on the other two forever. The path comparison joins
segments with `MAIN_SEPARATOR_STR` rather than `/`, because a hardcoded slash would pass on
two platforms and never match on the third — the one the check exists for.

**Not yet answered:** the step has not run. Answering it requires a push, which is Harald's.

## Antigravity: unsupported, explained, not vanished

D1, on Harald's 2026-08-11 design: marked unsupported rather than deleted, because a client
that explains its own absence beats one that silently vanishes. Its command-line tool has no
mechanism to connect jawata at all, so the workflow files studio wrote for it steered an
agent toward tools that client can never call.

A deploy now writes none, and **removes any a previous version left behind** — utilities
first, because the seat-command removal prunes `.agent/workflows` when it empties and a
utility file removed after it would be stranded inside a directory no mapping knows.
`derive_seat_commands_dir` deliberately still answers for Antigravity: dropping the mapping
would strand those files forever.

The user-facing half — greyed in the list, controls disabled, a mouseover saying why — is
Stage 2b, which owns the shared roster.

## Deliberately deferred, with homes

* ~~**The frontend roster stays hardcoded**~~ — **WITHDRAWN, it was wrong.** The paragraph
  that stood here said the new clients "deploy through the backend defaults, not yet through
  the dashboard picker", and the C2 audit proved that false. The default is never consulted:
  `runDeployWithTargets` in `ProjectList.svelte` ALWAYS sends an explicit target list built
  from a hardcoded array, and `deploy_to_agents` treats a present list as authoritative. So
  all three new clients took the "Skipped: not selected in this deploy run" branch on every
  run — three fully-tested adapters no user could reach, with every backend test green.

  The sentence written specifically to stop a reader mistaking this for a gap is the sentence
  that concealed it. That is the failure worth keeping: a mitigation stated from inference
  instead of traced through the call chain reads exactly like a mitigation that was checked.

  Fixed: the three clients are in the picker, in `RuntimeSettings.svelte`, and in the TS
  types. And the loop is closed — `the_ui_picker_can_reach_every_client_the_backend_knows`
  reads `ProjectList.svelte` **at runtime** and asserts every `KNOWN_DEPLOY_CLIENT_IDS` entry
  appears in it. Red-proven by removing one. (It reads at runtime rather than via
  `include_str!` because that was measured too: with `include_str!`, cargo did not rebuild
  when only the `.svelte` changed, so editing the picker and running `cargo test` returned a
  stale green — a gate blind to the file it guards.)
* **Steering files for the new clients** — the deploy writes the inert
  `jawata-studio-rules.md` sibling the `_` arm has always written. Copilot reads `AGENTS.md`
  but at **repository** scope, and the spec is explicit that guidance which would land in the
  user's own repository is a choice they make, never automatic. D10 / Stage 6 owns it.

## Watch item CLOSED: the studio suite's intermittent, now named

The Stage-0 entry above recorded two unexplained failures, refused to name a cause, and
asked for one thing: *"capture the FULL output of a single invocation — the name is the
whole finding."*

Captured 2026-08-15. The two tests are
`a_java_hand_edit_is_denied_by_the_real_binary` and
`a_declared_authoring_window_lets_the_next_java_edit_through`, both in
`jawata-hook/tests/edit_gate_runs_the_real_binary.rs`. They **fail together under the full
workspace run and pass in isolation**, which is a test-isolation defect and not the
contention story the shape invited: the pair shares the persisted `jawata-author:` session
window, and one test's window is visible to the other.

Two subsequent full runs were green, so it is intermittent, and the earlier entry's own rule
applies — green runs are not evidence. What changed is that it now has names and a
mechanism, so it is filed as an issue rather than carried as a watch item.

## Gates

| gate | result |
|---|---|
| `cargo test` (workspace) | **314 passed, 0 failed, 6 ignored** — 294 at Stage-2 baseline, +20 |
| `svelte-check` | **0 errors**, 3 pre-existing a11y warnings |
| `deploy_resolves_here` filter selects | 5 tests, all pass on Linux |
| `release.yml` parses; new step carries NO `if:` | verified — it is therefore `success()`, NOT `always()`: if an earlier step fails on a runner this one is SKIPPED there. An earlier version of this row said `always()`, which claimed a stronger guarantee than exists (C2 audit F2) |
| the filtered step on Windows/macOS | **NOT RUN** — needs a push |
| a live deploy to the new clients | **NOT RUN** — needs a running studio (dogfood) |

## The adapters, proven against the real client binaries

Not a dogfood and not a claim — the writers' actual output, handed to the actual tools.
Our `write_managed_toml_block` / `write_managed_json_block` produced the file (via the
`stage2_live_probe` dump helper, `#[ignore]`d so it never gates anything), into a sandboxed
HOME that already contained a user's own server and, for Codex, a user's comment. Then the
client's own binary was asked what it saw.

**Codex** — `codex mcp get jawata-javata-dev`:

```
jawata-javata-dev
  enabled: true
  transport: streamable_http
  url: http://127.0.0.1:8800/mcp
  http_headers: Authorization=*****
```

`codex mcp list` also showed the user's `users-own` server still there, and the file still
opened with `# the user was here first`.

**Copilot CLI** — `copilot mcp get jawata-javata-dev`:

```
jawata-javata-dev
  Status: Enabled
  Type: http
  URL: http://127.0.0.1:8800/mcp
  Headers:
    Authorization: ***
  Tools: * (all)
  Source: User
```

`copilot mcp list` showed `jawata-javata-dev (http)` and `users-own (local)` side by side.

**What this proves and what it does not.** It proves the bytes we write are bytes these two
clients parse into an enabled, authenticated jawata server — which is the half a unit test
cannot reach, because a unit test only ever compares our output to our own expectation. It
does **not** prove the agent calls jawata and gets an answer; that needs a running resident
and a real session, and it is C2's live probe.

**VS Code is not covered by this.** It has no CLI that lists configured MCP servers, so
there is no equivalent read-back. Its schema is measured (`code --add-mcp` wrote the file we
match, `servers` root and all), but the parse is unverified and stays that way until the
live probe.

## C2 adversarial audit — verdict REFUSE, and the disposition

A fresh-context auditor was given the commit, the C2 exit criteria and the spec, and told to
assume the implementer overclaimed. It ran the suite in a clean worktree, mutation-tested
three specific claims, and read git history. **Verdict: REFUSE.** It was right, and the lead
finding was a wiring failure the implementer's own mitigation sentence denied.

| # | finding | disposition |
|---|---|---|
| F1 | **The three new clients were unreachable from the product.** The only deploy call site always sends an explicit target list from a hardcoded five-client array; the backend treats a present list as authoritative. "They deploy through backend defaults" was false — the default is never consulted. | **FIXED.** Added to the picker, settings and TS types. Loop closed by a runtime-reading test, red-proven. |
| F2 | The CI step has never run (self-disclosed). Two precision defects around it: the dossier called a missing `if:` "`always()`" when it is `success()`; and `cargo test <filter>` **exits 0 when the filter matches nothing**, so a rename would leave the step green while testing zero paths. | **Row corrected. Count assertion added** (`EXPECTED=5`), verified to fail against a filter that selects nothing. The run itself still needs a push. |
| F3 | `deploy_resolves_here` gave 4 of 8 clients no path coverage — **mutation-proven**: IntelliJ's candidates replaced with `.totally-wrong-dir` left 314 passing. The "anti-vacuity clause" checked only `is_absolute()`. | **FIXED.** Per-client expected-suffix table for all eight; the same mutation now fails it. Surfaced a probable real defect — see below. |
| F4 | `config.rs` asserted in the present indicative that "the roster marks it unsupported and the UI greys it out". Nothing does; that half is Stage 2b. | **FIXED.** Comment is future-tense and names what shipped versus what did not. |
| F5 | `path_has_managed_entries` blind to Codex/VS Code. | Already fixed mid-audit; the auditor noted the fix commit landing as corroboration. |
| F6 | "Nothing downstream branches on a client name" is false — 13 sites still do, and this commit **added** one. | **Restated** to the true, narrower claim: nothing in the *MCP-entry write path* branches on a client name. |
| F7 | `is_managed_mcp_key` was widened to bare `jl` and `javalens`. **`git log -S` shows neither was ever a gateway id** — speculative. A bare managed name is deleted on deploy AND undeploy, so a user's server keyed `jl` would vanish silently. | **FIXED.** Narrowed to `jawata \| goja`, the two names history confirms. Test now asserts `jl`/`javalens` are NOT claimed. |
| F8 | The JSON writer clobbers an unparseable config while the TOML writer refuses one — opposite policies, same commit, and two new clients routed down the clobbering path. | **FIXED.** JSON refuses too; an empty file is still started (VS Code ships `mcp.json` at 0 bytes — measured). |
| F9 | Calibration note: with VS Code's root key mutated, only 1 of the 4 no-clobber cells caught it. | Accepted as stated. The grid is real; its depth per cell is one test. |

### What F3 surfaced: IntelliJ probably writes where nothing reads

Closing the blind spot immediately produced a finding. Measured on this machine:
`~/.config/JetBrains/` holds **versioned** product directories (`IntelliJIdea2024.3`, …),
while the unversioned `IntelliJIdea/` that studio's candidate names contains nothing but the
`mcp.json` studio itself wrote. Consistent with Harald's report that MCP does not work in
IntelliJ.

Two possible defects, one of them cross-platform: the unversioned directory, and the fact
that the candidate is built home-relative (`~/.config/...`) rather than from
`dirs::config_dir()` — which coincide on Linux and diverge on macOS
(`~/Library/Application Support`) and Windows (`%APPDATA%`).

**No fix proposed**, because where IntelliJ actually reads cannot be determined headlessly
and guessing at the very thing that is wrong is how this gets fixed twice. Filed as
`jawata-studio#9`, homed in Stage 7's Linux sweep. The pinned test row encodes what we
currently write and says in the comment that it is unverified.

### C2 status after the fixes

| C2 exit clause | status |
|---|---|
| `cargo test` green | **MET** — 318 passed, 0 failed, 7 ignored |
| Each client answers a jawata-only question on Linux | **NOT MET** — now *reachable* (F1 fixed) but needs a running resident and a live session |
| Filtered step green on all five targets in a linked run | **NOT MET** — needs a push |

Both remaining clauses are gated on Harald, not on engineering.

## The C2 live clause: MET — the five-client sweep, 2026-08-16, v3.9.1

The clause that stayed open through two releases — "each client answers a question it
could only answer by calling jawata" — closed today, driven by Harald interactively on
each client against pre-measured keys, judged from the Claude Code session.

The question: every workspace reference to
`Contract#getPipsFromDoubleAmount`, grouped by project. The key: 12 references across
`com.jats2.model` (10) and `com.jats2.portfolio.ui` (2) — a cross-project answer no
single-project index can produce.

| Client | Tools | Guard | Store round-trip |
|---|---|---|---|
| Claude Code | ✅ (session itself) | ✅ denied a live probe | ✅ |
| Cursor | ✅ exact + per-file | ✅ both layers, fallback signpost | ✅ |
| Codex | ✅ exact | — ran, expected (no hook surface) | ✅ |
| Copilot CLI | ✅ **per-line exact** (471; 463/613; 255/320/339/393/396; 86/95; 1001/1135) | — ran, expected | ✅ |
| Grok | ✅ exact + per-file | — ran, expected | ✅ |

The guard rows are recorded as the platform facts they are: denial where a hook surface
exists, unimpeded execution where none does — the tools-only cell is expected, not a gap.

One store observation worth keeping: the Copilot and Grok prompts carried the Codex
marker text unchanged, so both wrote "Codex dogfood" summaries — and the store's dedup
linked both to the real Codex marker. Correct behaviour, three clients, near-identical
text. (Reading the store: 584f627d is Copilot's, 1fafddfc is Grok's, despite the text.)

Companion install dogfood the same day (nine probes, one finding → jawata-studio#12) is
in the store under `dogfood:v3.9.1`.

## Stage 5 — the three D9b engine defects, closed with what was observed (2026-08-16, Linux)

Order note: Stage 4 (matrix skeleton) was resequenced AFTER 5+6 and the mcp push on
Harald's question — the skeleton's only binding constraint is "before the first sweep
probe", and building it before the engine work would void its pre-filled cells under
its own stale-cells rules.

**1. Wrong Java language level (falcon) — ALREADY FIXED, Sprint 28 C1.**
- The fix: `ProjectImporter` derives JDT compliance per build system
  (`readMavenCompliance` — `maven.compiler.release` wins over `source`;
  `readEclipseCompliance` reads `.settings`; `usableLevel` validates). Landed in
  commit `22476b8` ("Sprint 28 C1: every declared build system LOADS"), first
  release tag **v3.7.0** — after the macOS v3.6.4 finding that flagged falcon.
  Test-held: `BuildSystemLoadTest` asserts `compiler.compliance=17 was not read
  from .settings` (org.jawata.core.tests/.../BuildSystemLoadTest.java:316).
- The observation: `compile_workspace` over the 29-project orb workspace —
  **falcon: 0 errors** (absent from byProject) where macOS v3.6.4 reported 77
  wrong-level errors (`var`, switch arrows vs pom source/target 15). The compile
  is the level-sensitive instrument.
- Discarded evidence, recorded honestly: a `validate_syntax` arrow-switch probe
  returned valid against BOTH falcon and the old com-jats2 RCP project — the
  syntax check does not apply per-project source levels, so it discriminates
  nothing and is not part of the evidence.
- Residual: source parity between the Linux and macOS falcon clones was not
  verified; the macOS cell re-drives in the Stage 11 sweep.

**2. 1139 errors across 20 projects — DIAGNOSED; decomposes into filed issues #3 + #11.**
- Linux baseline: `compile_workspace(summary=true)` → **1229 errors / 29 projects
  compiled / 11 with errors**. Deterministic across OSes: top offender identical
  to the digit (org-eclipse-e4-ui-workbench-commands-swt = 613 on both), e4fixes
  = 14 on both, the documented-2 projects = 2 on both; clicktrader 431 vs 362 and
  com-jats2 157 vs 125 differ in magnitude only. Not an OS effect.
- Root cause observed: `inspect(kind=classpath)` on com-jats2-clicktrader → **3
  entries** (own src, target/classes, JRE) — every PDE dependency dropped
  (= mcp#3). Its errors are uniformly "cannot be resolved" for types that exist
  in com-jats2-model (Order verified indexed there). com-jats2 and the e4-swt
  bundle are the same family; com-jats2-model resolves 36 entries and shows only
  the documented 2 (= mcp#11 re-export depth). 100-error samples per project show
  no third cause. Full observation filed as a comment on **mcp#3**.
- The fix is #3/#11 engine work — homed to Sprint 28c per the family's homing rule.

**3. inspect(kind=source) size — STILL BROKEN, reproduced on Linux, filed as mcp#23.**
- `inspect(kind=source, typeName=java.util.stream.Collectors)` → 98,065 chars in
  ONE line, rejected by the calling client (Claude Code), exactly the macOS 82 KB
  shape. Both are under `LibrarySource.MAX_SOURCE_CHARS = 120_000` — the bound
  exists, is not caller-controllable, and defaults far above transport reality.
  Filed as **mcp#23** with the single-caller fix direction
  (`InspectTool#libSource` → `LibrarySource#sourceOf`).

**C5 verdict:** each defect closed with what was observed — one "already fixed,
here is the evidence", one "diagnosed to filed issues", one "still broken, filed".
Zero fixes applied in this stage; all fix work homes to the issues (28c).

## Stage 6 — D7/D6/D10/D11, each measured per its deliverable (2026-08-16)

**D11 — a wrong-workspace question answers itself. SHIPPED (mcp commit `4a70aca`).**
`WorkspaceIdentity` (org.jawata.mcp.models), installed at boot from workspace.json
before the message loop (no race with initialize), live loaded keys preferred once
present. Three surfaces: initialize instructions carry workspace name + roster;
every SYMBOL_NOT_FOUND hint says where it looked and redirects; empty search
steers instead of reading as nonexistence. Gate: 8 new in-framework tests
(WorkspaceIdentityTest 8/8) + neighbors McpProtocolHandler 21/21, SearchSymbols
15/15, ToolResponse 12/12. LIVE measure (spec: wrong workspace answers naming the
right place, both workspaces connected) re-drives when the v3.9.2 resident runs —
today's residents are v3.9.1.

**D10 — steering reaches the clients that have no hooks. SHIPPED (studio `de47a37`, help `b76bb69`).**
Mechanism measured: guidance travels IN-BAND — the MCP initialize instructions
(all clients; since D11 they also name the workspace) and Codex's per-answer
carry; the v3.9.1 five-client sweep already demonstrated every hookless client
holding jawata's guidance (all chose jawata tools unprompted). The deploy's old
`_`-arm rule file (`jawata-studio-rules.md`) was litter no client reads (Stage-2
architect finding): derive_rule_path is now an Option — files only for cursor
(.mdc) and claude (CLAUDE.md); deploy AND delete remove the inert file older
versions wrote (backup first). Nothing is written into a user's repository; help
states repo-level files (AGENTS.md etc.) are the user's own choice. Gate: 515/0
across studio targets incl. 2 new tests; unwired gate PASS.

**D7 — the product explains itself. VERIFIED (2b work + today's truth fix).**
Settings show the six-client roster with the Antigravity tombstone; help.md
carries the per-client setup table (held by the_help_file_names_every_supported_
client, green in the 515). Stale claim "guard identical on every client" fixed to
the two-client enforcement truth (`b76bb69`). R13b (a real JetBrains newcomer
reads it) is the recorded deferral to Sprint 29.

**D6 — Windows no-friction install route. BUILT; verification is Stage 9 by design.**
Release workflow ships jawata-studio-portable-windows-{x64,arm64}.zip (studio
`3e83a02`): exe + jawata-hook.exe side by side (the current_exe() adjacency law),
content asserted via 7z listing. Bucket repo LIVE:
https://github.com/haraldwegner/scoop-jawata (manifest for both arches, checkver/
autoupdate, WebView2 note, AGPL-3.0). OPEN ITEM FOR THE v3.9.2 RELEASE FLOW: the
manifest's two hash fields read FILL-ON-v3.9.2-RELEASE — fill them from the
published zips (sha256) right after the release, before any scoop install.
`scoop install` on real Windows = Stage 9's cell, as the plan states.

**C6 verdict:** D7/D10/D11 measured green; D6 built with its live half homed to
Stage 9 and one named release-flow step outstanding (the manifest hashes).

## v3.9.0 dogfood — the shipped artifact, through the front door (2026-08-16, Linux)

Ran against the RELEASED `jawata-v3.9.0-linux-x64.tar.gz` (downloaded from the
GitHub release, not a local build), launched with a manager-shaped
`workspace.json` naming the workspace `dogfood-390` and serving one fixture
project (javadoc-seat). All probes over live JSON-RPC to the resident.

| Probe | Result |
|---|---|
| P1 initialize | ✅ serverInfo.version = 3.9.0; instructions end with "THIS SERVER'S WORKSPACE ('dogfood-390'): 1 project(s): javadoc-seat …" — D11 surface 1 live |
| P3 FQN not-found (`inspect kind=source` on com.jats2…Order) | ✅ SYMBOL_NOT_FOUND, hint: "This is the 'dogfood-390' workspace (…) — a symbol that lives in another project tree is served by that tree's own jawata server" — D11 surface 2 live |
| P4 empty search (`MatchingOrderForwarder`) | ✅ 0 results + steering "No match for '…' HERE. This is the 'dogfood-390' workspace (…)" — D11 surface 3 live |
| P5 right-workspace sanity | ✅ fixture's own classes (Account, Ledger, Statement) resolve normally |
| P2 generic-name search (`Order`) | ⚠️ 32 JDK binary hits via the substring retry — non-empty, so no redirect fires; plausible noise instead of "not here". **Filed as mcp#25** (append the redirect when a bare-name page is binary-only) |

**D11's live measure is MET on the shipped artifact** — a wrong-workspace
question answers itself on all three designed surfaces; #25 names the one
query shape (generic simple names) where the answer is noise rather than a
redirect. The studio-managed residents on this machine pick up v3.9.0 via the
studio's own update pull; these cells re-drive trivially then (stale-cells:
resident change re-opens all).

**Managed residents re-driven same evening:** studio auto-updated to v3.9.2 /
runtime 3.9.0 and reloaded both workspaces (Harald's dashboard: 6/6 clients
deployed, 0 failed — the 2b status line on a real deploy). Against the LIVE
managed javata-dev server: FQN not-found and empty search both name 'javata-dev'
with its real 4-project roster and redirect; the orb-strategy server in the same
session resolves the very symbol (com.jats2.model.platform.position.Order), so
following the redirect succeeds. D11's two-workspace measure is MET on the
managed deployment, not only the sandbox.

**Cursor re-driven on v3.9.2/3.9.0 (2026-08-16, Harald pasting):** 7/7 PASS
against pre-measured keys — versions 3.9.0 both servers (4+29 projects); the
OrderSplitterStrategy key exact (11 refs / 2 projects / Cross+Fade); D11's
three surfaces verbatim, and P5 shows the loop CLOSING: the agent followed the
redirect unprompted and resolved Order on orb-strategy (origin=workspace-
source). Guard denied via both layers (workspace + java-search), hooks
unchanged since 3.9.1 — carry cell reconfirmed. Store round-trip: marker
98d29a36 recallable, dedup linked duplicate_of the 3.9.1 marker (45161c64).

**Codex re-driven on v3.9.2/3.9.0 (2026-08-16, Harald pasting):** 7/7 against
the same keys. P6 ran (exit 0) — the honest tools-only cell for a hookless
client per the four-client ruling; Codex labeled it UNEXPECTED only because
the prompt framed Cursor's expectation. D11 loop closed here too (redirect
followed; note it searched without projectKey and correctly navigated past
the EXECSIM Order — the mcp#25 generic-name shape in the right-workspace
direction). Store marker 0dc1ee50, duplicate_of the Cursor marker — the
cross-client dedup chain now three links deep.

**Copilot CLI re-driven on v3.9.2/3.9.0 (2026-08-16, Harald pasting):** 7/7
against the same keys — engine key exact (11/2), both D11 texts verbatim, the
redirect followed via inspect(kind=source) on orb (origin=workspace-source).
P6 ran (hookless honest cell, as Codex). Store marker d098f917; recall
count=4 — the cross-client marker chain now spans agent, Cursor, Codex,
Copilot on one operation key. Three of three re-driven clients closed the
D11 loop unprompted.
