# Detector calibration — Sprint 28d, Stage 11 (D9)

**What this measures:** what our detectors find, beside what two established
third-party Java analysers find, on a corpus where part of the right answer is
known. **What it cannot measure is stated first**, because the honest scope is
the most useful thing in the report.

---

## THE SCOPE, MEASURED BEFORE ANYTHING WAS DESIGNED AGAINST IT

D9 says the right answer is *"known from the folder name"*. The corpus has 188
top-level folders, one per design pattern. **Exactly ONE of our 41 detector
kinds shares a name with a folder: `singleton`.** So strict precision/recall
over folder-name labels covers **one detector, not 41**.

A second axis was DERIVED — never authored, because a corpus we label measures
us — from `CureCatalog`, a table that predates this stage: each smell kind
declares the design that cures it, and 11 of its 12 declared designs are fork
folders. The hypothesis was *"a pattern's own folder is the worked example of
that cure, so the smell it cures should be rarer there."*

**That hypothesis is wrong, and finding out is one of this stage's two real
results.** See §3.

---

## 1. THE PINS

| What | Pin |
|---|---|
| Corpus | `java-design-patterns` @ `22a34127d0b08449c24cf7e230c04a097deca2f3` — 188 folders, **186** loadable as projects, **1,957** main source files |
| Ours | the built dist at mcp `591f155`; **41** kinds registered, **38** swept (3 excluded: `throws`/`catches` need an exception name, `forbidden_edge` needs a layer pair — they answer a question the caller asks, not one the corpus poses) |
| PMD | **7.27.0**, `sha256 4ae396ffaf2b0d3ef0b73a10b2925e77066f73d57a4ce9078c60e7302bcddec9`, ruleset `rulesets/java/quickstart.xml` |
| Error Prone | **2.50.0** (`error_prone_core`, with-dependencies), `sha256 8ec037a6d57c0d880ed78c6a67445e5018a17a89b42cb6847ddef9081c504378` |
| Error Prone's required extra | `dataflow-errorprone` **3.41.0-eisop1**, `sha256 10434fba4e53f55fa9c76904cde414b918932548c9dfc4e2d634ac05ff7a7d10` — NOT bundled by the with-dependencies jar; without it a nullness check dies mid-analysis |
| JDK | 21.0.10 |

**Nothing was installed.** Both tools are downloaded jars in a scratch
directory; the corpus was compiled into an isolated local repository. Neither
our build nor the developer's `~/.m2` was touched.

**The store was never written.** 189 rows before and 189 after, asserted by the
script on both runs — D9's own measure.

---

## 2. RESULT ONE — `singleton`, the one strictly labelled detector

**Precision: 5 of 5, adjudicated over all 186 modules.**

Our detector fired 5 times corpus-wide: 3 inside `singleton/`, plus
`PrinterQueue` (in `collecting-parameter`) and `NullNode` (in `null-object`).
Both out-of-folder findings were adjudicated by reading the classes, and **both
are true singletons** — `PrinterQueue` has a private constructor, a static self
field, `getInstance()`, and a javadoc line reading *"This class is a
singleton"*; `NullNode` has `private static final NullNode instance` with a
private constructor and `getInstance()`.

> **The methodological finding, which outlives the number.** Scoring precision
> by the folder name would have called both of those errors and published
> 3/5 = 60%. The folder label says *"this folder demonstrates pattern X"*, never
> *"X appears nowhere else"* — so it is **sound for recall and unsound for
> precision**. Any future run must adjudicate out-of-folder findings rather than
> counting them as false.

**Recall: 3 of 5** against the detector's own declared predicate (private
constructor + static self-holder + static accessor), or 3 of 6 counting the
enum idiom it does not claim.

| Class in `singleton/` | Found? | Why |
|---|---|---|
| `IvoryTower` | ✅ | classic form |
| `ThreadSafeLazyLoadedIvoryTower` | ✅ | classic form |
| `ThreadSafeDoubleCheckLocking` | ✅ | classic form |
| `BillPughImplementation` | ❌ | **holder idiom** — the instance lives in a nested `InstanceHolder` |
| `InitializingOnDemandHolderIdiom` | ❌ | **holder idiom** — the instance lives in a nested `HelperHolder` |
| `EnumIvoryTower` | — | enum idiom, outside the detector's declared predicate |

**The blind spot is named and fixable:** the predicate looks for a static
self-holder **on the class**, and the initialization-on-demand holder idiom puts
it in a private static nested class. Two of the corpus's six singletons use it.

---

## 3. RESULT TWO — the derived axis, and why the hypothesis failed

Corpus rate per source file gives each cure folder a PREDICTED count. Observing
zero where 0.02 was predicted shows nothing; the prediction column is what makes
that visible.

| kind | total | in its cure folder | cure files | predicted | verdict |
|---|---|---|---|---|---|
| `divergent_change` | 338 | 0 | 25 | 4.37 | **CONSISTENT** — 0 against 4.4 predicted |
| `encapsulation` | 181 | 4 | 8 | 0.73 | **CONTRADICTED** — 5.5× the corpus rate |
| `coupling` | 44 | 0 | 30 | 0.69 | underpowered |
| `shotgun_surgery` | 14 | 0 | 25 | 0.18 | underpowered |
| `composition_over_inheritance` | 9 | 0 | 18 | 0.08 | underpowered |
| `ocp` | 7 | 0 | 25 | 0.09 | underpowered |
| `cqs` | 8 | 0 | 12 | 0.05 | underpowered |
| `type_code` | 4 | 0 | 9 | 0.02 | underpowered |
| `switch_statements` | 3 | 0 | 7 | 0.01 | underpowered |
| `singleton` | 5 | 3 | 16 | 0.02 | **not a cure pair** — the detector's subject IS this folder's pattern |

**Only two of ten rows carry signal.** Seven are underpowered: the corpus
predicts under one finding in the cure folder, so their zeros are silence, not
evidence. This is the *gate that cannot see zero*, quantified.

**The contradiction, adjudicated — and it exonerates the detector.** All four
`encapsulation` findings in `private-class-data` are on **`Stew`**, and none on
`ImmutableStew`. The module contains BOTH states by design: `Stew` is the
deliberate un-encapsulated before-example, `ImmutableStew`/`StewData` the cured
after. Our detector fired on the before and stayed silent on the after, in the
same module, which is **stronger evidence of correct discrimination than the
density test could ever have produced**.

> **So the hypothesis was wrong for a structural reason: a teaching corpus
> contains the disease next to the cure.** "Rarer in the cure's folder" is
> unsound wherever the folder exists to show the problem too. The sound form is
> *"fires on the before-class, not the after-class, inside the same module"* —
> which is what was observed, but which the folder name alone cannot address.

---

## 4. RESULT THREE — the third-party baselines

No correctness claim attaches to these numbers. They say what a mature tool
reports on the same bytes, which is the only thing they can say.

| Tool | The 11 labelled modules | Whole corpus |
|---|---|---|
| **ours** (38 kinds) | 156 | 3,339 (31 kinds fired) |
| **PMD** 7.27.0 | 33 | 736 main-source (1,142 including tests) |
| **Error Prone** 2.50.0 | 18 | not run — needs a compiling build per module |

Error Prone's 18, by check: `ObjectToString` 6, `BadImport` 4, `JdkObsolete` 3,
`MissingOverride` 2, `InvalidLink` 1, `ImmutableEnumChecker` 1,
`DefaultCharset` 1. PMD's corpus-wide top rule is `GuardLogStatement` (464 of
736) — a logging-idiom rule with no counterpart in our set.

**The three tools barely overlap by construction.** PMD is dominated by a
logging idiom, Error Prone by JDK-API correctness, ours by documentation and
design-structure kinds (`javadoc_lack` 852, `divergent_change` 338,
`feature_envy` 258). Agreement was never the right expectation; the useful read
is that the three occupy different ground, so none substitutes for another.

---

## 5. WHAT WENT WRONG WHILE MEASURING (each cost a re-run)

1. **The corpus build wedged.** Its repository tries `jitpack.io` before Maven
   Central for every artifact, and this sandbox's network policy does not allow
   that host, so each attempt hung rather than failing. Mirroring `*` to Central
   took the same build from wedged to **BUILD SUCCESS in 12 seconds**.
2. **Error Prone reported 11 clean modules that were not clean.** Setting
   `-processorpath` overrides annotation-processor discovery, so Lombok never
   ran, every `@Slf4j`-generated `LOGGER` became "cannot find symbol", and the
   run exited 1 with **zero warnings** — a failure wearing a clean result's
   clothes. Lombok belongs on the processorpath beside Error Prone.
3. **The analysis script nearly published a wrong verdict.** Its first cut
   tested underpower BEFORE excess, so the one row carrying a finding
   (`encapsulation`, 4 observed against 0.73 predicted) was labelled
   "underpowered — an observed 0 shows nothing" while showing 4. An excess is
   informative against a small prediction; only a zero is not.

---

## 6. WHAT WOULD STRENGTHEN THIS

- **Fix the holder-idiom blind spot** in the singleton detector — a named,
  reproducible miss with two test cases already identified.
- **Error Prone corpus-wide** needs all 186 modules compiled; only the 11 were
  built here.
- **The seven underpowered pairs** need either a larger corpus or a labelling
  axis that does not depend on a rate.
- **The before/after axis inside one module** is the sound version of §3 and is
  worth building deliberately, with the after-class identified by something
  better than a naming convention.
