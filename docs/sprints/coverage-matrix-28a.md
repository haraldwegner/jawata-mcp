# Coverage matrix — Sprint 28a (D3)

What was **actually driven**, per client, per channel, per operating system —
and what was not. A cell claims nothing that was not exercised; "deploys,
never driven" is a permitted, honest state. A tier describes what a client
*can carry*, not what a weak model will do with it.

Versions of record: **jawata-studio v3.9.2 · jawata-mcp 3.9.0** (both released
2026-08-16). Cells stamped with the version they were driven on; the staleness
rules (bottom) say which cells a change re-opens.

Channel definitions — TOOLS: the client's agent answers through jawata MCP
calls · STEERING: jawata's guidance demonstrably shapes the agent (in-band via
MCP instructions everywhere; rule files only where a client reads one) ·
GUARD: a shell/edit that violates the workflow is DENIED · PRIMER: domain
knowledge injected at session start unprompted · RECALL: prior knowledge
surfaces at prompts/edits unprompted · STORE: the agent can record and recall
markers in the shared experience store.

## 1 · Clients × channels — Linux (driven 2026-08-16, v3.9.2/3.9.0)

| Client | Tools | Steering | Guard | Primer | Recall | Store |
|---|---|---|---|---|---|---|
| Claude Code | ✅ driven (this session, all day incl. D11 texts) | ✅ in-band + CLAUDE.md rule block | ✅ deny observed (java-grep + workspace blocks fired live) | ✅ auto (SessionStart) | ✅ auto (per-prompt nominees observed) | ✅ records + recalls (many today) |
| Cursor | ✅ driven (7/7 probe run, keys exact) | ✅ in-band + .mdc rules | ✅ deny observed (both layers, verbatim) | ✅ best-effort (sessionStart; no per-prompt injection — platform limit) | ⚠️ side-effect only (platform limit, recorded) | ✅ marker 98d29a36, dedup-linked |
| Codex | ✅ driven (7/7, keys exact) | ✅ in-band (per-answer carry; no file — by design, D10) | — none (no hook surface on this route; enforcement via principal, later) · grep ran, honest | pull-only | pull-only | ✅ marker 0dc1ee50, dedup-linked |
| Copilot CLI | ✅ driven (7/7, keys exact) | ✅ in-band | — none (same ruling) · grep ran, honest | pull-only | pull-only | ✅ marker d098f917, recall count=4 |
| VS Code (Copilot agent) | ✅ driven (engine key count exact: 11; one client-side invoke error on a single health_check while the same server answered P3/P4 — VS Code-side transient; agent misreported the file list, caught by the pre-measured key) | ✅ in-band observed (D11 texts verbatim) | — none (no hook surface) · grep ran, honest | pull-only | pull-only | ✅ marker 11974f12, recall count=5 |
| Grok | ✅ driven (P1–P4, P7 exact; P5 surfaced mcp#26) | ✅ in-band (tools-not-guard ruling) | — none (platform has no hook surface) · grep ran, honest | pull-only | pull-only | ✅ marker a4ac4783 |

**The D11 behavioral cell, all five driven clients:** given only the
not-found/empty-search redirect, every agent (Cursor, Codex, Copilot CLI,
VS Code's Copilot agent, Grok) navigated to the other workspace's server and
resolved the symbol — unprompted. The feature does not merely render; it
steers.

## 2 · Clients × channels — Windows *(session 2026-08-17; after the orphan kill (studio#13) the cells below are stamped **3.9.0**)*

| Client | Tools | Steering | Guard | Primer | Recall | Store |
|---|---|---|---|---|---|---|
| Claude Code | ✅ driven @3.9.0 (21-project jats workspace) | ✅ D11 live: empty-search steering names 'jats' + its 21 projects (cap working); not-found hint redirects (mcp#32: failed-load workspace misnamed as present) | ✅ deny observed @3.9.0 (deleted-binary check still owed) | ☐ | ☐ | ✅ round-trip works; #33 closed — dedup proven working by the Codex run |
| Cursor | ✅ driven @3.9.0 (agent: Grok 4.6; engine + D11 texts verbatim) | ✅ D11 live (empty-search + not-found redirects; #32 phantom project again) | ✅ **deny observed @3.9.0 — the historic hooks-in-visible-bash hazard is CLEARED by the binary hook** | ☐ | ☐ | ✅ marker ce7edf02; #33 closed — dedup proven working by the Codex run |
| Codex | ✅ driven @3.9.0 (gpt-5.6-terra, default sandbox; declined the impossible P4 inversion — correct strictness) | ✅ D11 empty-steering verbatim | — none by platform (Windows: no hooks at all — published limit) · rg ran; exit-1 was a PowerShell pipe artifact, not a block; sandbox permitted project reads | pull-only | pull-only | ✅ marker 1fcd6dc4 **with duplicate_of link — dedup proven on Windows, closed #33** |
| Copilot CLI (Git Bash) | ✅ driven @3.9.0 (AbstractMultiplier 19 refs, matching two independent Claude runs; #28 NPE reproduced — third client) | ⚠️ D11 claimed, not verbatim — run driven by Haiku + gpt-5-mini: the model tier degraded the reporting (sub-agent detour, summaries), while jawata's computed answers stayed exact | — none (no hook surface, per ruling) · grep ran via PowerShell, honest | pull-only | pull-only | ✅ marker 25bfe0ed round-tripped |
| VS Code (Copilot agent) | ✅ driven @3.9.0 (19/9 key exact, full verbatim refs; P1 pasted the embedder block — vector-api available on BOTH servers, confirming #33's closure) | ✅ D11 live, both texts verbatim (#32 fifth sighting) | — none (no hook surface) · Select-String ran (its ** glob matched nothing — PowerShell, not a block) | pull-only | pull-only | ✅ marker ebd72ef0, dedup-linked to Codex's; recall nuance fed #34 |
| Grok (CLI) | ✅ driven @3.9.0 (Grok 4.6; 19/9 key exact; #26 fifth repro, #31 second client, #32 fourth sighting) | ✅ D11 live, both texts verbatim | — none (no hook surface, per ruling) · built-in grep + Select-String ran; shell rg/grep were OS-missing, correctly not attributed to jawata | pull-only | pull-only | ✅ marker 48b087cb, dedup-linked; ⚠️ found **mcp#34**: capped recall serves oldest-first, own marker invisible |

Windows extras: **Scoop ✅ driven 2026-08-17** — `scoop install` from
[scoop-jawata](https://github.com/haraldwegner/scoop-jawata) verified the
manifest sha256 end to end, and the Scoop-delivered binary started correctly
on the shared state (v3.9.2/3.9.0 header, both workspaces and services
visible; the day's tool calls ran against these services). Found during the
probe: **studio#13 generalized** — 'Stop workspace' on Windows leaves java
hanging and the next start cannot own a fresh process ('PID not
discoverable'); the Windows face of studio#1. Still owed: the deleted-binary
fail-open check · settings/cache dirs in Windows locations.

## 3 · Clients × channels — macOS *(session 2026-08-17, all cells @3.9.0, aarch64, embedder vector-api(4 lanes))*

| Client | Tools | Steering | Guard | Store | Falcon (P8) |
|---|---|---|---|---|---|
| Claude Code | ✅ 19/9 key exact, full verbatims; v3.6.4 cache-path defect confirmed NOT reproducing | ✅ D11 verbatim | ✅ deny observed | ✅ marker a5f61d3c | ✅ errorCount 0 |
| Cursor | ✅ 19/9 exact | ✅ D11 verbatim | ✅ deny observed | ✅ dedup-linked | ✅ 0 |
| VS Code (Copilot agent, Haiku via auto) | ✅ clean run, 1m07s | ✅ D11 verbatim | — none · ran | ✅ marker 4f5595aa | ✅ 0 |
| Codex | ✅ clean | ✅ D11 verbatim | — none · ran | ✅ dedup-linked | ✅ 0 |
| Copilot CLI | ❌ **not drivable this run — client-side**: tool search found the jawata tools but the CLI never loaded them into the callable schema; the agent improvised (shell-invoked tool names, ps-harvested resident tokens → studio#14, curled wrong endpoints) and never issued one MCP call. Same client drove Linux directly and Windows only via its internal task agent — a Copilot CLI deferred-MCP defect × weak auto-model | — | — | — | — |
| Grok | ✅ 19/9 exact | ✅ D11 verbatim | — none · ran | ✅ marker recorded; **#34 reproduced** (own marker absent from capped recall) | ✅ 0 |

**Falcon closes on origin soil**: errorCount 0 on the machine where v3.6.4
measured 77 wrong-level errors — the D9b defect verified fixed on both OSes
that matter. P4/P5 legitimately skipped everywhere (javata-dev workspace
unloaded — current checkout, needs only the one-time local build). #29
reproduced (third OS). macOS extras still open: delete-everything
confirmation · deleted-binary check · D9a items (download freeze,
window/Dock behavior).

## 4 · Engine — project types × OS (the CI matrix, run 2026-08-16 on 3.9.0)

| Project type | Linux | Windows | macOS |
|---|---|---|---|
| Maven | ✅ green | ✅ green | ✅ green |
| Gradle (source roots etc.) | ✅ green | ✅ green | ✅ green |
| Gradle **jar** cell | ✅ (separate Linux job) | — skip (no Gradle distribution in matrix job) — recorded as skip, never a pass | — skip (same) |
| Eclipse PDE | ✅ green | ✅ green | ✅ green |
| Eclipse-plugin **jar** cell | ✅ (Linux bundle-pool job) | — skip (no bundle pool) | — skip (same) |
| Plain Java | ✅ green | ✅ green | ✅ green |
| RCP launch shape | ✅ green | ✅ green | ✅ green |

Loading, source roots, output exclusion and language level are exercised for
all five types on all three OSes; the two jar cells stay Linux-only and this
matrix says so (guard-versus-verified marks carried unchanged). Windows argv
handling is an assertion (TestHost, M11), not a hope. One Windows
nondeterminism on the suite is filed: mcp#24 (duplicate-scan fixture race).

## 5 · Stale-cells rules (D12) — what a change re-opens

| Change kind | Cells invalidated |
|---|---|
| Hook binary (jawata-hook) changes | Every guard/primer/recall cell on every client, per OS where deployed |
| A client adapter / deploy writer changes | That client's full row (tools + steering + deploy-dependent cells), all OSes |
| Resident (jawata-mcp runtime) changes | ALL cells, all clients, all OSes (tools, D11 texts, store — everything transits it) |
| Studio UI only (no writer, no hook, no resident) | Nothing in this matrix |
| Rule-block/steering content changes | Steering cells only, clients that receive that channel |

Applied history: 3.9.1→3.9.2/3.9.0 changed deploy writers **and** the
resident → all Linux cells re-driven above (done); hook binaries unchanged →
the 3.9.1 guard observations would have carried, and were re-confirmed anyway
on Cursor. **No cell is driven twice outside these rules.**

## 6 · Open issues feeding later sprints

mcp#3/#11 (PDE dependency resolution — the diagnosed 1229-error mass) ·
mcp#23 (source size) · mcp#24 (Windows suite flake) · mcp#25 (generic-name
noise without redirect) · mcp#26 (source provenance + missing filePath) ·
studio#6/#7 (Windows payload captures, owed to the Windows session).
