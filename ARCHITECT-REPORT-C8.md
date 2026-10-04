# ARCHITECT REPORT — C8, Sprint 28g Stage 8

Architect seat, WATCH MODE, run read-only over the five Stage 8 commits. Governing picture:
`ARCHITECTURE-principal-and-verticals.md` — §3 the fence and its mechanism, §4-B the
provider/coverage shape, §6 P2 step 3.

**Provenance of this file, so a reader knows whose words are whose.** Sections 1–6 are the
architect seat's own report, as it produced it. Section 7 is the implementer's disposition of
each finding, written afterwards and marked as such. Nothing in 1–6 was edited to agree with
what was then done about it.

**Abbreviations, defined once.** *Lane* = one table inside the shared catalogue (refactorings,
smells, patterns). *Row key* = a catalogue identifier namespaced by its origin, e.g.
`fowler:23`. *DETECT / PERFORM* = the two independent axes the architecture requires per row
(can this engine find it / can it do it). *Constant pool* = the region of a compiled `.class`
file that names every type a method body touches, including types no `import` line mentions.

---

## 1. VERDICT

**MIXED.** Four of the five commits move toward the declared picture and one of them
(`152eb583`) is the architecture's step 3 executed as written. But the stage ends with the
shared table and its one coverage declaration **readable only from the test tree** — the same
defect the stage's own javadoc indicts, moved one level out — and the purity fence was built
with the mechanism §3 rejects by name while the prescribed precedent sits 120 lines away in the
same test bundle.

---

## 2. FINDINGS, RANKED — incomplete delegation first

| # | Finding | Kind | Fix size | Must this stage do it? |
|---|---|---|---|---|
| 1 | The catalogue and its coverage declaration have no production reader | Incomplete delegation | One registration call + one index | **No — home it** |
| 2 | The coverage object makes callers fetch its own input; the catalogue's key→name rendering lives in the engine behind a `null` branch | Incomplete delegation + Rule 2 | Two no-arg overloads, one `Optional` | Optional now; cheapest before a second engine |
| 3 | The purity fence reads source imports and two build-file texts, where §3 prescribes the compiled artifact | Rule 13 / architecture deviation | One test class rewritten | Could land in S8 |

### Finding 1 — the module left the test tree; its readership did not

**Measured.** `find_references` on `JavaEngineCoverage` → **9 references, every one inside its
own test** (lines 4, 5, 38, 70, 90, 99, 100, 118, 187). Zero production callers. And exactly
**one** production file in the repository imports `org.jawata.catalogue` at all —
`JavaEngineCoverage` itself, which is that unread object. The two form a closed loop reachable
only from the suite.

`JavaEngineCoverage`'s own javadoc states the principle it violates: *"A coverage declaration
no production code can read is not a declaration, it is an assertion about one."* True of
`PerformedRefactoringCountTest`; now true of its replacement. The table is production **code**;
it is not production **behaviour**.

The architecture names the missing half, §4-B: *"At registration the declaration is published
into the existing `OperationRegistry`, which keeps its job unchanged — 'what can be called, and
how do I spell it' — and gains the row key as an index."* Not done. §2 records the seam already
exists: `OperationSurface.publish(tool)` runs from `ToolRegistry.register`, and `CureCatalog`,
`CureLookup`, `CureTier`, `ArchitectGate` and `MechanicalChangeJournal` already resolve through
`OperationRegistry` — 89 references.

**Why ranked first.** This repository shipped exactly this shape before: v3.4.0 released
semantic recall **inert**, and the recorded lesson is that *a test suite cannot catch
unwired-ness, because the tests do the wiring*. Here the tests do the wiring, so all five gates
stay green whether or not anything else can ask the question. C8's clause is satisfied in form,
not in function, and the difference is invisible to every gate this stage added.

### Finding 2 — give the object its behaviour

**(a)** `performs(Set)` and `claimsNotShipped(Set)` demand the caller supply the shipped set
while `shippedOperations()` sits on the same class and computes it. Every caller must
fetch-then-pass, and may pass something else — not hypothetical: the javadoc records that a
doors-only set once made rows 39/40 join against a set missing `rename_symbol`, *"the assertion
stayed green while the operation shipped to nobody."* Precedent for the fix is in the same
package: `CureTier.derive(String)` / `derive(String, List<String>)`.

**(b)** `JavaEngineCoverage` formats a row key by calling `Catalogue.byKey` and branching on
`null`. Turning a key into something readable is the catalogue's job, and every engine that ever
formats one writes that branch again. **The module whose entire thesis is "`NOT_APPLICABLE` is
not `NONE`" returns `null` for "no such row"** — and a test now pins that null as the contract.

### Finding 3 — the fence was built with the mechanism the architecture rejected by name

§3: *"a purity test per fenced bundle, each a copy of `EmbedPackagePurityTest`'s mechanism —
read the compiled **class files'** constant pool rather than the imports, because a constant
pool names every type a method body touches, which an import scan misses — and each carrying
the same positive control."*

Three reasons it matters beyond tidiness: the escape is the author's own idiom one file over
(`JavaEngineCoverage` writes a fully-qualified call); the compensating pom check reads one
file's text and is blind to Maven inheritance (latent today — `build/pom.xml` has zero
`<dependencies>` — but invisible to the check if it opens); and `ENGINE_PACKAGES` is a
five-item deny-list, which §2 quotes `ToolRegistry`'s own weakness about.

**Smallest fix:** one constant-pool scan over the built classes, **inverted to a whitelist** —
allow `java/*` and `org/jawata/catalogue/*`, deny everything else — with the precedent's
positive control. A whitelist cannot fail open.

---

## 3. DISPATCHES

| Finding | Goes to |
|---|---|
| 1 — no production reader | the 28g stage that wires the principal to coverage; architect re-review at that checkpoint |
| 2 — delegation + rendering | same stage, or a follow-up commit before the `null` contract hardens further |
| 3 — purity mechanism | can land in S8; one test class, no production change |
| the cures | the stage that introduces row-key-shaped recipes; first move is `validateAgainst(List<String>)` |
| the DETECT axis | same stage — completion of the coverage object, not a new feature |
| JDT blindness to a new Maven module | the platform round of the Sprint 28 family; not a stage defect |

---

## 4. REVIEWED DIFFS — design fix or bandage

| Commit | Verdict | One line |
|---|---|---|
| `152eb583` | **DESIGN FIX** | the table leaves the test tree, the engine-spelling column goes, keys namespaced from the first commit, counts scoped to the `fowler:` block — step 3 as written |
| `497261a2` | **DESIGN FIX** | `withOrigin`/`byKey` move to `Catalogue` *before* the second copy exists, at the cheapest moment |
| `64628c24` | **DESIGN FIX, one bandage inside** | seeding from the pinned snapshot is right and throwing on a missing resource is right; the bandage is that `patterns.tsv` says "do not hand-edit" and **no generator is committed** |
| `5b2902c1` | **BANDAGE** | the property is real; the instrument is three text descriptions of the artifact where §3 named the artifact itself |
| `d6f080c8` | **DESIGN FIX in shape, INERT in effect** | right object, right bundle, constructor refusals that make a contentless claim impossible, a count genuinely computed — and nothing in production reads any of it |

---

## 5. RULE 12 — the second different breakage

**DOES NOT FIRE.** The seven failures the commits describe cluster into two, not seven
singletons: *the module's contents are not where the runtime or tooling looks* (4 instances) and
*an assertion taken against the wrong set, or in an order that hid half of it* (3). The decisive
check: **no fix in the run is the cause of the next commit's failure** — zero of seven trace to
a preceding fix.

**The caveat stated anyway:** cluster A has four instances, and this project's own rule is that
the second occurrence of one shape is the alarm even when nothing regressed. Adding a module
here means re-deriving four non-obvious facts by hand in its pom — source root, resource root,
manifest path, dist inclusion — with nothing checking the derivation, and two of the four were
got wrong on the first attempt. Home that to whichever stage adds the next bundle.

---

## 6. RULE 13 — who owns the fact each new guard reads

| Guard | Verdict |
|---|---|
| the source-import purity scan | **FIRES** — the compiled artifact owns it and can answer precisely; this is Finding 3 |
| `thePomDeclaresNoDependencies` | **FIRES** — Maven's effective model owns it; a text check on one file, blind to inheritance |
| `theManifestImportsNothing` | **LEGITIMATE** — the contract between our jar and Equinox, which ships independently: the persisted-schema exception |
| `theSmellRowCarriesNoKind` | **FIRES, clearest instance** — `CatalogueRow` is `(key, name)` and already makes a kind field impossible; the 22-item list is a third copy of the engine's kind names |
| `theRecordRefusesAnEmptyClaim` | **LEGITIMATE** — the object makes the bad state impossible and the test proves the impossibility |
| `theSixtyTwoIsRecomputedByTheEngine` | **LEGITIMATE** — a regression golden over our own output, with the discriminator declared |
| `thePatternRowsMatchTheSnapshot…` | **LEGITIMATE, best guard in the stage** — reads a frozen external corpus, refuses to run if two snapshots are present |
| "no row names a language" | **LEGITIMATE** (not-ours exception) |

### The two questions recorded for the seat

**(a) The cures — correct to defer, overstated as blocked.** C8's exit does not name them, and
§4-B asks for more than a move: *"the cure table moves with them, **and its `recipe` field stops
being a global plan kind and becomes a row key that each provider's coverage resolves**."* That
cannot precede Finding 1's wiring. But the commit's claim that `CureCatalog` *"cannot move as it
stands"* is overstated: of 13 methods **exactly one** names an engine type, and the unbinding
precedent is in the same package. **A dropped architecture clause, correctly deferred,
inaccurately characterised.**

**(b) DETECT and PERFORM — one object split in two.** The values are independent; the
*declaration* is one record per row, and §4-B states that shape. Three divergences are already
real: the key spaces have split (PERFORM uses `fowler:23`, DETECT uses Fowler's English display
name as its map key — the engine's-spelling-as-key problem this stage removed from the rows,
still live on the detect side); the shapes differ (a four-field record with a refusing
constructor, versus a declines-only `Map<String,String>` where "detects it" is inferred from
registry membership); and the completeness guarantee is one-sided. **Not a split to live with.**

---

## 7. DISPOSITION — the implementer's, written after the seat reported

| Finding | Disposition |
|---|---|
| **3** purity mechanism | **DONE**, `57cb98dc`. Rebuilt on the constant pool as a **whitelist**, with the precedent's positive control carried. Mutation: emptying the list turns it **red**, where the same mutation left the import scan **all green** — the asymmetry the seat asked for, measured. The pom text check is retired as the seat ruled; `theManifestImportsNothing` kept for the reason the seat gave. |
| **the pattern control's home** (audit N1, seat's §6 row) | **DONE**, `79c9f77f`. Moved to the permanent class; the move surfaced a fourth copy of one repo-root walk, now owned once. Mutation-proved from its new home. |
| **the boot guard** (found by the suite, not by either review) | **DONE**, `be94ff97`. `EveryCrossBundlePackageIsImportedTest` reported a package no bundle exports; the product was right and the guard's hand-typed two-bundle list was wrong. Derived now, with a floor so an empty derivation cannot pass vacuously. Mutation returns the original boot failure exactly. |
| **1** no production reader | **ACCEPTED, homed** to the stage that wires the principal to coverage. The seat's own dispatch; the v3.4.0 precedent is why it is ranked first rather than deferred quietly. Recorded as the stage's largest surviving risk. |
| **2** delegation + the `null` rendering | **ACCEPTED, homed** with Finding 1, since the production reader is what forces the API. The `null`-as-no-row contract is now pinned by a test, which raises the price — noted. |
| **(a)** the cures | **the seat's reading is accepted and my characterisation was wrong.** "Cannot move as it stands" overstated a one-parameter change. Deferral stands on §4-B's second half, not on the obstacle I named. |
| **(b)** DETECT and PERFORM | **ACCEPTED as owed work, not a design question.** D39's own measure names both axes, so recording it as the architect's call was me narrowing a signed measure. Owed; not in this stage's commits. |
| **64628c24's bandage** — no generator committed | **ACCEPTED, open.** `patterns.tsv` instructs a reader not to hand-edit it and nothing in the tree regenerates it. The permanent snapshot control catches drift but cannot repair it. |
| **Rule 12's caveat** — four pom facts re-derived by hand per new module | **ACCEPTED, homed** to the stage that adds the next bundle. |
| `theSmellRowCarriesNoKind` fires Rule 13 | **ACCEPTED, open.** The type already forbids a third field and the transcription control already catches a mistyped name, so the 22-item list guards a fact the object owns. Not removed in this stage: it is a deletion inside a checkpoint under audit. |
