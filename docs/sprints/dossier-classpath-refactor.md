# Dossier — the workspace resolve phase

*(Sprint-29 proof material. Written at the plan's C13.2, 2026-08-19.)*

## The customer-terms story

A jawata workspace is a container for build units grouped by business domain — an
RCP product, its Maven-built broker gateways, and two fully-working reference
projects, all in one place because that is where a developer's comprehension lives.
On the reference workspace (29 projects, JATS), jawata reported **1229 compile
errors**. The code was fine — his Tycho build was green. jawata was resolving each
project *during its own import* against whatever happened to be registered so far,
the thing the OSGi runtime itself never does.

The fix is one architecture change, not 1229 patches: **Inventory → Resolve →
Apply**. Every bundle's manifest is read once into facts; a pure resolver decides
the whole workspace's wiring in one pass over the complete graph — workspace
projects, the user's own Tycho-downloaded platform, fragments for the running OS —
and a delta apply touches only the entries jawata owns. Loading order stopped
mattering, because there is no "so far" any more.

## The discriminator

Before: `com.jats2.clicktrader` carried **431 errors, every one of them
`import com.jats2.model cannot be resolved` — while com.jats2.model was loaded.**
The provider existed; it had simply loaded *later* than its consumer. No amount of
per-project fixing removes that class of defect; only a resolve *phase* does.

## Before / after (measured, same 29 projects, same machine)

| Measure | Before (v3.11.1) | After (HEAD) |
|---|---|---|
| Workspace compile errors | **1229** | **58** |
| com.jats2.clicktrader | 431 (all sibling-wiring) | **0** |
| org.eclipse.e4.ui.workbench.commands.swt | 613 (platform unindexed) | **0** |
| com.jats2 | 157 | **0** |
| com.jats2.e4fixes | 14 | **0** |
| Projects with any error | many | **1** (com.jats2.model) |
| `#11` sentinel (`Composite … FXCanvas`) | present | **gone** |
| 29-project load wall-clock | ~89 s (live fleet, v3.11.1) | **75 s** |
| Platform source | none found (headless dist only) | Tycho p2 cache, **673 jars**: first index **243 ms**, cached **16 ms** |

## The honest residual

The 58 remaining errors are all in `com.jats2.model`, and they are **newly visible
truth, not regression**: before the fix, that project's build *aborted* on an
incomplete build path (a missing indirect log4j reference), so most of its files
were never compiled at all — it reported 2 errors while hiding 58. With the build
path complete, its real state surfaced:

- **48 × language level** — a source level below Java 14 is applied while the code
  uses switch expressions and type patterns. jawata derives PDE compliance from the
  manifest's `Bundle-RequiredExecutionEnvironment`; the Tycho pom (deliberately
  unread, by ruling) compiles at a newer level.
- **10 × JUnit vintage** — `assertNotEquals` needs junit ≥ 4.11; the pool's wired
  junit is older.

Both classes are named, mapped, and stopped on for a ruling — never passed
silently.

## The marketing story (ruled in, 2026-08-19)

The residual is itself the sales pitch. On a mature production codebase, the IDE
had been compiling `com.jats2.model` for years while its manifest declared a Java
level far below what the code uses — Eclipse's PDE tolerated it, the Tycho build
papered over it from the pom, and the project's error view showed 2 errors while
48 language-level violations and a stale JUnit constraint sat invisible behind an
aborted build. jawata's resolve phase did not just fix jawata's own numbers: **it
found real, actionable defects in the customer's own project metadata that no
other tool on the machine reported.** The fix it pointed to is one line in one
manifest — in the customer's repo, named precisely. That is the product claim in
one sentence: *compiler-accurate honesty surfaces what your IDE has learned to
ignore.*

## The transferable lesson

Resolution is a *workspace* phase, never an *import* step. Any system that lets a
member's answer depend on registration order has no resolve phase — and the tell is
an error message naming a thing that is demonstrably present. Corollaries proven on
the way: a dedupe must decide which entry's *semantics* survive, not just which
path (the unexported-survivor defect); a build that aborts on an incomplete path
undercounts, so falling error totals must be checked against *builds that now
complete*; and test goldens must never depend on the machine's own caches.
