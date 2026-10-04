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

### What was asked of the pattern catalogue, and what was selected (D4)

**This section was MISSING and no audit round found it.** The plan's per-checkpoint gate requires
the architect's watch to *"name what it asked the pattern catalogue about this checkpoint's
changes and what it selected, or state that it selected none (D4)"*. Two adversarial rounds read
this file and neither raised it, because both audited the findings rather than the checkpoint's
own contract. It is performed here rather than claimed retroactively.

**ASKED** (`experience(kind=nominate)`): *a shared catalogue table lives in its own module that
references no language engine; each engine declares per row whether it detects and whether it
performs, and publishes that declaration into a registry at boot so the product can answer what
it does rather than only a test being able to derive it — what pattern governs that declaration
seam, and what governs keeping the shared table free of any one engine's vocabulary?*

**NOMINATED: 8. SELECTED: 1.** Ranking is not an answer, so each was judged on its own situation:

| Candidate | Judgement |
|---|---|
| **the `OperationRegistry` lesson** (`worked`) — *check the named tables before designing their replacement; the cure table, architect gate and coverage advisory already resolve operations through `OperationRegistry`, populated at tool registration* | **SELECTED, and its justification is now PAST TENSE — a round-4 audit caught this row asserting a thing that no longer exists.** What it said: *"`publishInto` indexes into the EXISTING registry at boot instead of standing up a second one."* That WAS true when written and was falsified by F1's own withdrawal one commit later: `publishInto` is gone, nothing indexes anything, and the registry argument went with the index. What the lesson actually bought survives the withdrawal and is why it stays selected: it is the reason the first attempt reached for the EXISTING registry rather than standing up a second one, so when the index turned out to have no reader there was one map to delete and no parallel registry to unpick. Had it been read as licence to invent a registry, the withdrawal would have been a migration. |
| Table Module | REJECTED. Its situation presumes logic that applies uniformly to all rows; these rows carry no logic at all, so the premise does not hold and its consequence — one module owning the table's logic — is not what was built. |
| Metadata Mapping · Table Inheritance · Single Table Inheritance | REJECTED. All three are object-to-table persistence mappings. There is no persistence here. |
| Monostate | REJECTED. It needs instances that share state; `JavaEngineCoverage` has a private constructor and no instances. |
| Multiton | REJECTED. One instance per key is a lifecycle; the row index is a map. |
| the re-seed domain fact | REJECTED. Its subject is the experience store, not the catalogue module. |

Seven rejected for situation mismatch rather than for ranking low — four of them scored above
0.38 — which is the whole reason the verb hands back candidates instead of a match.

---

## 7. DISPOSITION — the implementer's, written after the seat reported

| Finding | Disposition |
|---|---|
| **3** purity mechanism | **DONE**, `57cb98dc`. Rebuilt on the constant pool as a **whitelist**, with the precedent's positive control carried. Mutation: emptying the list turns it **red**, where the same mutation left the import scan **all green** — the asymmetry the seat asked for, measured. The pom text check is retired as the seat ruled; `theManifestImportsNothing` kept for the reason the seat gave. |
| **the pattern control's home** (audit N1, seat's §6 row) | **DONE**, `79c9f77f`. Moved to the permanent class; the move surfaced a fourth copy of one repo-root walk, now owned once. Mutation-proved from its new home. |
| **the boot guard** (found by the suite, not by either review) | **DONE**, `be94ff97`. `EveryCrossBundlePackageIsImportedTest` reported a package no bundle exports; the product was right and the guard's hand-typed two-bundle list was wrong. Derived now, with a floor so an empty derivation cannot pass vacuously. Mutation returns the original boot failure exactly. |
| **1** no production reader | **PARTLY DISCHARGED, and my first disposition said DISCHARGED outright — an architect watch measured that as an over-claim and it was right.** What IS closed: the declaration is READ by production at boot, so the catalogue stops being code only its own tests touch. **FOUR of its five types are read in production bodies — not five, and the correction is mine to make twice over.** Measured after the fold: `Catalogue` once (`byKey`, JavaEngineCoverage:163), `CatalogueRow` once, `Refactorings` twice, `Patterns` twice (286-287, not the 248-249 this row first gave), and **`Smells` ZERO** — because deleting the Java-engine prefix filter removed `Smells.SMELL`'s only production reader. So the one type that fell out is the one the same commit stopped reading, and a round-4 audit caught the row still claiming five. Stated plainly rather than buried: the smell lane is now shared-module code with no production reader, which is the same shape as the two baselined members and would widen again if F2(b) moved the pattern check into `Patterns`' own loader. What was NOT: §4-B's row-key index. It was written and **nothing read it** — both accessors at zero references, production and test, which the watch measured three ways. So "a row key is answerable from production" was false as shipped. **AND THE GATE I CITED AS PROOF COULD NOT HAVE SEEN IT:** `called_only_by_tests` reports members with ≥1 TEST caller and no production caller, so a member with no callers at all is invisible to it — "seven hollow became PASS" is true of what it covers and silent about the two the same commit added. That is this project's own recorded lesson about a gate that cannot see zero, walked into again. The index is **withdrawn** rather than kept as dead state behind exported API, which also reverts an exported record's arity 5 → 4; §4-B's index is owed, with its consumer named on `OperationRegistry`. |
| **2** delegation + the `null` rendering | **ACCEPTED, homed** with Finding 1, since the production reader is what forces the API. The `null`-as-no-row contract is now pinned by a test, which raises the price — noted. |
| **(a)** the cures | **the seat's reading is accepted and my characterisation was wrong.** "Cannot move as it stands" overstated a one-parameter change. Deferral stands on §4-B's second half, not on the obstacle I named. |
| **(b)** DETECT and PERFORM | **ACCEPTED as owed work, not a design question.** D39's own measure names both axes, so recording it as the architect's call was me narrowing a signed measure. Owed; not in this stage's commits. |
| **64628c24's bandage** — no generator committed | **ACCEPTED, open.** `patterns.tsv` instructs a reader not to hand-edit it and nothing in the tree regenerates it. The permanent snapshot control catches drift but cannot repair it. |
| **Rule 12's caveat** — four pom facts re-derived by hand per new module | **ACCEPTED, homed** to the stage that adds the next bundle. |
| `theSmellRowCarriesNoKind` fires Rule 13 | **ACCEPTED, open.** The type already forbids a third field and the transcription control already catches a mistyped name, so the 22-item list guards a fact the object owns. Not removed in this stage: it is a deletion inside a checkpoint under audit. |

### Round 2 of the adversarial audit — `REFUSE`, and all three blockers were controls that could not fail

Not one was in the module or in the wiring. All three were assertions of mine that read as
coverage, which is the shape every checkpoint of this sprint has produced.

| Finding | Disposition |
|---|---|
| **NEW-1** the purity fence's positive control was satisfied by its own subject's class name | **DONE.** `JavaEngineCoverage`'s `this_class` entry is `org/jawata/mcp/catalogue/JavaEngineCoverage`, present in every copy of its own class file and covered by no allowed prefix — so both controls passed on the subject's own name and would have passed with every engine reference deleted. `foreignReferencesOf` drops the subject's name and its nested types. **The control is a PAIR, because the audit's point was that the old mutation could not discriminate:** whitelist the four engine prefixes the subject actually reaches — measured, not assumed, `org/jawata/mcp/{refactoring,tools,knowledge}/` and `org/jawata/core/` — but NOT its own package, then run both forms. Fixed: **RED**, *"otherwise the purity assertion above is vacuous"*. Pre-repair: **GREEN, 7/7**. And the first attempt at that mutation stayed green because it whitelisted two of the four prefixes — an incomplete mutation, whose false negative is indistinguishable from a real one, which is this sprint's own recorded trap and the reason the surviving set was then measured instead of guessed. |
| **NEW-2** the derived bundle floor checked the COUNT while its message claimed the IDENTITY | **DONE.** The gap was load-bearing: this file's only cross-bundle proof of life is keyed to the literal `org.jawata.mcp`, so losing that one bundle left the floor satisfied at two, the host's manifest unopened, and the check green having never examined its own subject. `REQUIRED` names the two it cannot do without; the derivation still finds them. Pair, with the host skipped: fixed **RED**, naming the loss and printing the derived set as `[org.jawata.catalogue, org.jawata.core]` — two, exactly what the old floor accepted; pre-repair **GREEN, 7/7**. |
| **NEW-3** the purity population counted FILES while its message claimed TYPES | **DONE.** The population is every class file the module compiles to, `$` names included, resolved through `RepoFiles` like every other artifact this package has to find. This pair needed the case CREATED, because the hole was latent — five classes against five sources, so no edit to the test alone can turn it red: a nested type added to a catalogue source, fixed **RED** and NAMING `Smells$MutationCNested` in the population, so it is scanned and not merely counted; pre-repair **GREEN, 7/7** with the type compiled and invisible. Both files restored byte-identical and `git status` verified before each revert. |
| **non-blocking 2** a fourth copy of the repo walk survived in the same package | **DONE.** `TheEngineDeclaresWhatItPerformsTest` held a byte-identical `sourceOf` one file from `RepoFiles`, callable unqualified. `RepoFiles`' own javadoc claimed to have caught *"a fourth copy one move away"*; it had not. That sentence is corrected in place rather than quietly replaced — it was a consolidation of three sold as a consolidation of all, which is the same over-claim as the three above. |
| **the purity row's own claim, two rows up** | **CORRECTED.** *"With the precedent's positive control carried"* was true in form only, and the mutation cited beside it is the one NEW-1 proved blind. The row stands as written and this line supersedes it. |

### The vacuous positive control, closed as a CLASS — the record a reader of this checkpoint meets

Round 3 was right that this existed only in a commit body: the per-checkpoint gate asks for *the
query that enumerates the population* and *fixed equals population*, and neither was here.

**THE QUERY.** It is an assertion-SHAPE question — a test whose body reads a constant pool and
asserts a positive control on a known-impure subject — so it is not a symbol relation and carries
a logged `jawata-fallback:`:

```
grep -rlnE 'disallowedReferences|constant pool|PurityTest|purity CONTROL' \
    --include='*Test.java' org.jawata.mcp.tests/src org.jawata.core.tests/src
```

**`-E` IS LOAD-BEARING AND THE COMMIT BODY OMITTED IT**, which round 3 found by running the
command as recorded: without it the `|` are literal in a basic regex and the query returns
**zero files** — a recorded population query that enumerates nothing. The commit also attributed
`AnalogyPolicyTest` to `org.jawata.core.tests`; it is at
`org.jawata.mcp.tests/src/org/jawata/mcp/knowledge/AnalogyPolicyTest.java`, and that second root
contributes none of the three.

**POPULATION 3 · FIXED 3.**

| Fence | State |
|---|---|
| `TheCatalogueCannotReachAnEngineTest` | fixed in `fdbd08a1` — the subject's own name and nested types filtered out |
| `EmbedPackagePurityTest` | fixed in `163e20e6` — its rule reports every `org/jawata/` name outside `embed/`, and the control subject is itself outside `embed/` |
| `AnalogyPolicyTest` | fixed in `163e20e6` — its helper is NAMED `jawataReferencesOtherThanItself` and excluded the MAIN assertion's subject, not the CONTROL's |

Round 3 verified the population independently by its own shape query and found the three extra
hits use `ISO_8859_1` only to snapshot SOURCE files for parity, carrying no pool-scan control.

### Round 3's four blockers — every one class B, a claim false about the code beside it

| Finding | Disposition |
|---|---|
| **B1** the "SIX genuine foreign references" was measured on a stale artifact | **DONE.** The live numbers are **31** under the embed rule and **30** under the analogy rule, re-measured by me on `bundles/org.jawata.mcp-4.3.3.jar`, 53180 bytes — the class the test loads. Six is what the same scan returns for a **16695-byte class dated 2026-07-12** in a dormant PDE output directory on no suite's classpath. Both fences carried one wrong number from one measurement against the wrong artifact, under the word MEASURED, in a commit set that twice lectures on stale jars. The repair was sound; its proof was not taken. Each fence now carries its own number and where it was read. |
| **B2** the recorded query returns zero and the population lived nowhere a reader meets it | **DONE** — the section above. |
| **B3** the new survivor list named 5 of 7 | **DONE.** All seven are now named in the comment. The two it omitted are exactly the two that make the mutation's prefix count four rather than two, so the comment reproduced as prose the same incompleteness that made the first mutation a false negative. Also noted: one of the seven is a store, not an engine, so the assertion message is narrower than the needle. |
| **B4** an orphaned javadoc claimed "DERIVED from the tree, not listed" about a hand-written list | **DONE.** `REQUIRED` and its javadoc now sit ABOVE `BUNDLES`' javadoc, so each block documents the member it describes and `BUNDLES` is no longer undocumented. |

Round 3's own closing read: the three repair MECHANISMS are correctly and discriminatingly
closed, the population is complete, the §8(b) discharge survives the inheritance attack
(`build/pom.xml` has no `<dependencies>`, no `<dependencyManagement>`, no `<profiles>`), and the
suite line is true to the shard logs — it summed them itself. What it kept finding is one shape:
**a number or a command recorded as "measured" that was not taken against the live artifact.**

### The architect watch over the three commits — `MIXED`, and its first finding is a real defect

| Finding | Disposition |
|---|---|
| **F1** the §4-B index is WRITE-ONLY — both accessors at zero references, and the gate offered as proof is structurally blind to a zero-reference member | **DONE, by withdrawal.** The map, `indexRow`, `operationForRow` and `indexedRows` are gone, which removes two unread methods from an EXPORTED package and reverts `Snapshot`'s arity 5 → 4 — also answering the watch's below-the-fold concern that an exported record's arity had changed with consumers I cannot enumerate. `publishInto(OperationRegistry)` published nothing into it, so the dead parameter went with it (`change_method_signature`) and the method is renamed `verifyAgainstShippedDoors` (`rename_symbol`), because a name that describes a thing which does not happen is the same class-B claim this round is about. The index's consumer is the one §4-B itself names — a cure resolving through a row key — recorded on `OperationRegistry` where the next reader meets it. |
| **F2(a)** two derivations of one population, one statement apart | **DONE.** The refusal walked `Catalogue.withOrigin(FOWLER)` and filtered smells out by STRING PREFIX while the caller's log used `Refactorings.all().size()` — two derivations of one fact in two files, which disagree the day the prefix convention moves. It iterates `Refactorings.all()` now; the prefix filter is deleted and a second provider does not inherit a copy. |
| **F2(c)** the fetch-then-pass hazard was widened onto a production path | **DONE.** `performs()` and `claimsNotShipped()` no-arg overloads added on the `CureTier.derive(String)` precedent; the boot path calls those, and the set-taking forms stay for the tests whose job is to vary the set. The class's own javadoc records this hazard having already shipped one false green. |
| **F2(b)** the pattern lane's integrity is enforced inside a Java-engine method | **ACCEPTED, HOMED — not done here**, and the reason is scope rather than disagreement. The watch is right that a fact wholly inside the catalogue module belongs in `Patterns`' own loader, that every future provider must otherwise copy the check, and that the current fail-stop scope is wrong — a truncated `patterns.tsv` takes down `search_symbols` and every analysis tool, none of which touch the pattern lane. It is a change to the LOAD behaviour of a purity-fenced production module, proposed after the last audit round, inside a checkpoint under audit; that is the shape this plan warns about. Homed with F2(b)'s own reasoning intact. |
| **F3** three purity fences are three copies of one mechanism, and one hole needed three commits | **ACCEPTED, HOMED.** The watch answers D-SIX directly and against the architecture: the lanes differ only in DATA (population root, policy, control subject), the mechanism is entirely ours, so *"a purity test per fenced bundle, each a copy of `EmbedPackagePurityTest`'s mechanism"* is no longer the right instruction — and the proof is `163e20e6`, which exists only to apply one filter to the two copies the previous commit did not reach, and lands it as two fresh duplicated literals. Its smallest alternative is an object for the fence concept, parameterised by those three data, and for the catalogue fence specifically a strictly smaller move: derive `ALLOWED` from the manifest's own absent `Import-Package` rather than hand-spelling the same fact. Changing the architecture's standing instruction is not a fold item. |
| **a consequence F2(a) had and the watch did not predict** | **BASELINED, with the reason here because the baseline file cannot hold one.** Deleting the Java-engine prefix filter removed the only production readers of `Catalogue#withOrigin(String)` and `CatalogueRow#id()`, so the unwired gate FAILED naming both. Measured before choosing: `Refactorings.all()` returns the lane's OWN rows, so `withOrigin` is the CROSS-LANE filter and nothing in production asks a cross-lane question any more; `id()` is a record component, not something to delete. Wiring either would mean inventing a production question, and deleting `withOrigin` would contradict a documented decision made one commit earlier — `Refactorings`' own javadoc argues it belongs on `Catalogue` precisely so each lane does not carry a copy. So the gate's third remedy, 118 → **120**. **It is speculative generality if the second provider never arrives and correct layering if it does**, and the architecture commits to the second provider. The condition that retires the baseline, so it is not permanent by default: a second provider declaring against the catalogue, or a cure resolving by row key, either of which gives both a production reader. Noted plainly: the baseline file is a bare signature list with no reason column, which is how such a list rots — that is why this row exists. |
| **Rule 12** | The watch **disagrees with §5's "does not fire"** for this commit set, on new evidence rather than a different reading: `57cb98dc` claimed the precedent's control was carried, `fdbd08a1` proved that control blind, `163e20e6` then found the identical hole in the two files `57cb98dc` was instructed to copy from — one structure, three commits, each exposing the next face, with the propagation vector being the architecture's own copy instruction. **Accepted.** It also records what the response got right: the defect was closed as a class with a population query and fixed = population, which is what the gate asks; the alarm is on the mechanism that made the population three. |

---

## 8. THE TWO CARRIED CLAUSES — settled here, because C8 is where they were carried TO

A round-2 audit found both neither discharged nor re-declared in this file, and it was right: the
trail existed in two commit bodies and nowhere a reader of the checkpoint would meet it.

### (a) `compile_workspace` cannot be clean for a stage that adds a module — RE-DECLARED

`152eb583` carried it in these words: *"compile_workspace reports 19 errors, every one of them the
same unresolved import in those two test files. refresh_workspace does not re-import the Maven
model, so the new module's source root is invisible to JDT, and this agent is instructed never to
reload a project. That gate cannot be made clean for any stage which adds a module. Maven and the
runtime are the authority here and both pass; the plan's compile clause is carried to C8 as owed,
not as met."*

MEASURED AGAIN HERE rather than restated: `compile_workspace` reports **71** errors, and every one
is `org.jawata.catalogue cannot be resolved` or a type from that package failing to resolve. The
count moved 19 → 71 because the module is now reached from production as well as from two test
files — which is this stage's own deliverable, so the number RISING is the wiring landing rather
than a regression. Over the same tree the Maven compiler reports **exit 0, zero errors**, the dist
builds, and the suite executes the code.

**It is re-declared and not discharged, and the obstacle is a standing instruction rather than a
technical one:** making JDT see the module means re-importing the Maven model, and this agent must
never load or add a project — the workspace is pre-loaded. So the authority for the per-checkpoint
compile gate in this stage is the Maven compiler plus the runtime, and substituting them is the
deviation. One word reopens it.

**AND IT IS WORSE THAN A COMPILE GATE, which the round-2 audit did not reach.** No jawata query can
see the module either: `find_references` on `org.jawata.catalogue.Smells` answers `SYMBOL_NOT_FOUND`
and asserts *"it is gone, not moved"* about a file edited minutes earlier. That is an absence
inferred from a failed lookup and stated as a fact — a product defect in its own right, filed as a
finding here rather than fixed inside a checkpoint under audit. Every readership question in this
report therefore carries a logged `jawata-fallback:` declaration, because the compiler-accurate
instrument refused and said so.

The surviving risk, plainly: a JDT-only error — one Eclipse would report and javac would not — is
invisible in this stage. Nothing in the stage's changes is JDT-specific, and the suite executes all
of it.

### (b) "green with no engine on the classpath" — DISCHARGED, by building the module alone

The architecture's P2 step 3 asks for this verify and no commit performed it. The round-2 audit read
the TEST bundle's pom, found it declares `org.jawata.mcp` and `org.jawata.core` — which pull JDT in
— and concluded the clause is now structurally unsatisfiable. That reading is correct about the test
bundle and it is not the whole clause: the property is about the MODULE, and the module builds on
its own.

Measured: `mvn -f build/catalogue/pom.xml clean package` reports **exit 0 with zero errors**, and
its resolved dependency classpath is **0 bytes — empty**. That is the clause performed rather than
approximated: there is no classpath entry that could satisfy an engine import, so the compile could
not succeed if a catalogue source needed one.

Why all three fences are kept rather than one: the standalone build proves nothing reaches an engine
at COMPILE time from the sources as they stand; the constant-pool scan proves it of what was
actually COMPILED, so an inherited dependency cannot quietly make a reference legal; the manifest
check proves OSGi would refuse the package at RESOLVE time. They fail for different reasons, so none
replaces another.

### The gates, transcribed in the action that ran them

| Gate | Result |
|---|---|
| jawata-mcp suite | `SHARDED-SUITE shards=4 wall=726s total=3168 succeeded=3164 failed=0 aborted=4 skipped=0 unloadable=0`. All four aborts are on shard 2 and were READ from THIS run's own shard log: two corpus-absence skips (`-Djawata.embed.corpus` unset), one `kind="src" path="/xref-declarer"` reference skip, and one test that reproduces an open defect and runs only under `-Djawata.probe.race=true`. The abort budget reports 4 of 5 allowed, every skip accounted for. |
| jawata-studio gates | `ALL LOCAL GATES GREEN`, exit 0 — **948** Rust tests across the workspace, the hollow-wiring gate PASS (77 in baseline, unchanged), and the frontend type pass. The plan asks for BOTH suites and only the mcp one had been run. |
| unwired members | **PASS**, 0 new, 118 in baseline unchanged. Its first attempt exited **2** — the port its own scratch resident holds was still bound — and exit 2 is not a pass, which the gate says itself; it was re-run after the port freed rather than read as green. |
| the five catalogue classes | **25/25**, against a dist whose product AND test bundles are both current. |
| Maven compile | exit 0, zero errors. `compile_workspace`: 71, all one cause — see (a). |
| the catalogue alone | exit 0, zero errors, empty dependency classpath — see (b). |

**The round-2 blocker about the earlier number is accepted outright.** "25/25 against the BUILT
DIST" was a scoped selection reported where a suite clause was owed, and this repository's own
sibling says why that is not evidence — *1812 tests passed against a product that could not start*.
Worse than the audit knew: those targeted runs used a dist whose PRODUCT bundles were three hours
stale while only its test bundles were fresh, so they ran new test code against old product jars.
The suite's own staleness guard is what caught it, by REFUSING to run — and the 25/25 was re-taken
against a current dist before the suite was launched.
