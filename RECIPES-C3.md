# RECIPES — C3, Sprint 28g Stage 3: the eight engine defects

**What this file is, and the clause it answers.** C3's exit criterion opens with *"eight recipes,
each with its before/after captured to a file"*. This is that file. Each row below carries the call
that reproduces the defect, what the product answered with the defect live, what it answers now,
and the named test that holds the after-state so it cannot drift back.

**The range:** `c7febfe9..HEAD`, ten commits. **Read the row for D7a before reading the table as a
score: seven of the eight are fixed, D7a is reproduced and isolated with its root cause open**, and
nothing here claims otherwise.

**Where the before-states come from, stated because it decides what they are worth.** Six were
measured by reproducing the defect against the tree at the time, and the error strings below are
transcribed from those runs rather than reconstructed. Two — D7a and D7d — were reproduced against
a foreign project (`strategies_orb`) because that is where the field report found them; their
before-states are single measurements, not repeatable gates, and the row says so. **An after-state
pinned only by a measurement is weaker than one pinned by a test**, so every row names its test and
the two rows that have no reachable test say which half is covered instead.

---

## D7b — a nested target call lost to the call it sits inside

**Commit:** `b37f12da` · **Test:** `ANestedTargetCallDoesNotEatItsEnclosingCallTest`
**Fixture:** `NestedCallTargets.java`, four sites, matching the report's four

**Recipe**

```
change_method_signature kind=change_signature
  symbol=com.example.NestedCallTargets.Inner#Inner
  newParameters=[{name:seed, type:int, defaultValue:0}]
  auto_apply=false
```

**BEFORE** — at every one of the four sites, the diff rewrote the *enclosing* call:

```
-        return new Outer(new Inner(1, 2), "siteOneLabel", 11);
+        return new Outer(0);
```

Five arguments of a constructor that was never the target, gone — and reported `success=true`,
`applied=false`, *"5 edits in 1 files"*. Caught only because the change was staged.

**AFTER** — the target `ClassInstanceCreation` alone is rewritten; all four enclosing calls keep
their own arguments. The cause was a **dropped** discriminator, not a missing one:
`ChangeSignatureTool.updateCallSite`'s `MethodInvocation` branch required containment AND a name
match, while the three CONSTRUCTOR branches required containment alone — and an `ASTVisitor` walks
top-down, so the enclosing node matched first and `return false` stopped the descent before the
inner node was visited. The restored clause is stated over the ARGUMENTS rather than the type,
because `this(..)` and `super(..)` have no type node at all — so `super(new Inner(1, 2))` carried
the same defect unexercised.

**Control:** the reproduction test was RED against the tree before the change and GREEN after — a
before/after on the real defect rather than a mutation of the fix. The parity golden passing is the
other half: the ordinary non-nested case is unchanged.

---

## D7c — a text default was spliced everywhere and the response named nowhere

**Commit:** `cc3b678d` · **Test:** `ADefaultValueSaysWhereItWasInsertedTest`
**Fixture:** `DefaultValueTargets.java`, two sites holding the same value under different names

**Recipe** — a signature change adding a parameter whose `defaultValue` names a local that exists
at one site and not the other.

**BEFORE**

```
+ return new DefaultValueTargets(roster, 10_000, roster[0]);
+ return new DefaultValueTargets(teamRoster, 100_000, roster[0]);
```

`roster` does not exist at the second site. Reported as a clean staged success.

**AFTER** — the response carries `defaultsInserted`: file, line, and which default each site
received, plus one sentence saying what it cannot promise.

**The fix is deliberately NOT a resolver, and the report's second case is why.** One of its two
cases is a default naming a local present at one site and absent at another — both compile-fail,
and the compile gate does report those on apply. The other is a default of `25_000` inserted at
sites built with `10_000` and `100_000`: it COMPILES and is simply not what those sites meant.
**Nothing can catch the second** — a single text default cannot know what each site intended. So
the deliverable is naming the sites, because those are exactly the lines a caller must read before
applying.

**Control:** RED before, GREEN after, with both sites asserted by line and by the default they
received. An earlier version of the assertion accepted the word `defaultValue` appearing anywhere
in the response, which the schema echo alone would have satisfied.

---

## D7e — a member type moved to its own package was refused

**Commit:** `6f790ae0` · **Test:** `AMemberTypeMovedToItsOwnPackageIsPromotedTest`
**Fixture:** `NestedPromotionTargets.java`

**Recipe**

```
move kind=class
  symbol=com.example.NestedPromotionTargets.NestMate
  targetPackage=com.example          # its own package
```

**BEFORE** — `INVALID_PARAMETER: "Target package equals source package in the source project;
nothing to move"`. **The refusal was correct for a top-level type and wrong for a member one:** for
a member type, becoming its own compilation unit in that same package is Fowler's Move Inner To Top
Level, and it is the whole change.

**AFTER** — the member type is promoted by JDT's own `MoveInnerToTopRefactoring`, wrapped rather
than hand-rolled, through the existing `runPreCheckedRefactoring` seam so the compile gate, the
staging path and the undo handle are unchanged. **The refusal survives for a top-level type**, and
a third test is the control for exactly that — a fix that simply deleted the refusal would have
passed the other two.

The response names what reading the created file revealed and the old response would not have said:
the enclosing-instance field, that every construction site gained an argument, and that **the
engine WIDENED a `private` field** the promoted type reads, because a separate top-level class
cannot see a private member. Without that widening the created file would not compile — and row 59
measured that the compile gate PARSES a created file without RESOLVING it, so nothing downstream
would have said so.

**Control:** both promotion tests RED before, each failing on the refusal verbatim, while the
top-level control was green. After: 15/15 over the three new tests plus the four pre-existing
`move`-class classes.

---

## D7f — a response the client could not display

**Commits:** `7eeaaca0`, `d32c90f4`, `4e2b4dc4` · **Test:** `ADiffIsSummarisedAndCappedTest`

**Recipes** — two from the field report, one measured here:

```
rename_symbol RankedEntry -> Signal        # 144 references
apply_cleanup kind=add_final               # no filePath: a whole-project sweep
```

**BEFORE** — the rename answered **167,785 characters** and the client spilled it to a file,
losing the success it was reporting. A findings page answered 86,584. **And this product's own
worst case is bigger, measured here rather than quoted:** the whole-project `add_final` sweep over
the 188-file fixture produces a **141,246-character** diff. That number came out of a mutation's
failure message, not from an estimate.

**AFTER** — `diffSummary` on every apply-shaped response (one row per file with its hunk and line
counts); the diff text capped at the house figure of **24,000** characters with `truncated:true`
and `diffLength` when cut; `fullDiff:true` as the escape. Declared once on `withAutoApply`, the
shared schema decorator. **The cap destroys nothing** — for a staged change the cache still holds
the whole diff, which `refactoring action=inspect` serves.

**24,000 is the house figure rather than a new one:** it is what `inspect kind=source` already
uses. A second number for the same question is how two answers start drifting apart.

**Summarising is unconditional and capping is not**, which is the design: capping alone would leave
a caller holding a cut diff with no account of what was removed. The end-to-end test asserts the
INEQUALITY — the summary covers strictly more files than the truncated text mentions — rather than
that a summary exists.

**All four diff-producing sites were enumerated through `find_references` before a line was
written** and all four converted: both bases' staged, applied and compile-refused payloads (a
refusal is as undisplayable at 167k as a success), `copy_class` where an uncapped diff made the new
file arrive twice in one answer, and `inspect_refactoring`.

**Control:** three mutations, and the valuable one stayed green twice — see the mutation ledger in
the plan's S3 record. No parity golden moved, measured before writing: the 65 goldens record the
resulting SOURCE FILES, not the response map.

---

## D7g — 78 call sites rewritten when the ask wanted one overload

**Commit:** `225d8b02` · **Test:** `AnOverloadLeavesEveryCallSiteAloneTest`

**Recipe**

```
data kind=add_record_component
  componentName=<name>  componentType=double  defaultValue=Double.NaN
```

**BEFORE** — correct and unwanted: **78 construction sites in 24 files**, three times, all three
undone. The wanted shape was one four-argument secondary constructor in one file. The tool did what
it says; what it did not do is notice that the ask carried the answer — a default of `Double.NaN`
is a value no site wanted to pass.

**AFTER** — `viaSecondaryConstructor:true` adds the component to the header AND an overload taking
the OLD component list, delegating to the canonical constructor with the default, so **not one
construction site is rewritten**. And for the run that did *not* ask: a sentinel default (`null`,
`-1`, `NaN`, `MIN_VALUE`, `Optional.empty()`) now comes back with `overloadAlternative` — the
runnable call, its arguments, and the reason.

Three details are decisions rather than mechanics: the parameter list is copied from the
declaration's own component NODES rather than rebuilt from names, so `List<String> names` and
`int[] slots` arrive exactly as the source spells them; the overload is generated `public`
deliberately, because a record's canonical constructor is as visible as the record and a less
visible overload would be unreachable from the very call sites this mode exists to leave alone; and
`defaultValue` stays REQUIRED in this mode, because the overload's whole body is a delegation
passing it.

**A declared deviation from the report's own wording**, which says to suggest the overload when the
default is a sentinel *"and the site count is large"*. **The sentinel ALONE is the signal**, because
the argument is semantic rather than economic: at two sites the overload is still the shape that
says nobody passes this. A threshold would be a number invented here, so the count travels IN the
suggestion and the caller weighs it.

---

## D7h — a lane filter that could not hold a pattern said nothing

**Commits:** `e57782ed`, `7070d08e` · **Test:** `ALaneFilterThatExcludesThePatternsSaysSoTest`

**Recipe**

```
experience kind=recall  symbol=<any>  lane=domain
```

**BEFORE** — eight lookups filtered `lane=domain` and came back with the project's own domain facts
and no patterns whatever. **Nothing was broken** — a catalogue `reference` is a borrowed EXPERIENCE
by `KnowledgeLane.of`'s own ruling — **and nothing said so**, so the caller could not tell *no
pattern fits* from *patterns were never searched*. Those two answers want opposite next moves, and
the store's own steering tells a caller to match one of the rows it returned.

**AFTER** — the answer carries `catalogueExcluded` and `catalogueExclusionNote`, which states
`NOT SEARCHED` and names the lane that WOULD reach them, **derived by asking `KnowledgeLane.of`
rather than restating its answer** — so a ruling that moves the catalogue moves this with it.

**It is a property of the FILTER, not of the result, and that choice is what makes it exact.**
Counting the catalogue rows a query dropped would UNDERCOUNT: the keyword source filters by lane in
SQL, so those rows never reach the pool to be counted. Asking whether the lane CAN hold a pattern
needs no rows at all and cannot be wrong.

**Placed beside the echoed cue rather than beside the hits**, which is the design decision rather
than a detail: an empty answer under a lane that excludes the catalogue is EXACTLY the answer a
caller reads as *no pattern fits*, so a note appearing only on hits would be missing in the one
case it exists for.

**Control:** four mutations; the one worth keeping stayed green and found that **there are two
absence paths and the test covered one** — a no-MATCH absence carries a real cue and goes through
the store, while a no-CUE absence returns from its own branch earlier. `7070d08e` closes that.

---

## D7d — a discovery failure answered as a successful empty run

**Commit:** `230ed0d9` · **Test:** `RunTestsToolTest`

**Recipes** — measured twice through the built artifact against `strategies_orb`:

```
run_tests scope=class typeName=pipeline.SignalFactorsCharacterizationTest   # 5 JUnit5 methods
run_tests scope=class typeName=pipeline.HealthCheckerTest                   # does not exist
```

**BEFORE** — both answered `success: true`, `total: 0, passed: 0, failed: 0`, `evidenceFinalized:
true`. **So three different states — the class has no tests, the class could not be found, and the
run worked — were one response, and the one it chose was the green.** `evidenceFinalized: true`
asserts the evidence is complete about a run that discovered nothing. It is also why the sprint
being reported on made ZERO `run_tests` calls and blamed a stale note: the tool could not tell its
caller that it found nothing.

**THIS IS NOT THE DEFECT THE FIELD REPORT DESCRIBES.** The report's fix line sends the reader at
the fork's command line and Byte Buddy reading `java.class.version` / `java.version`. Both halves
are wrong: jawata sets neither property (`find_string_literals` over 1163 files, both scans
COMPLETE — `java.class.version` zero occurrences, `java.version` three, all reads), so the launch
cannot be leaving them unreadable. **The recorded symptom's cause is on the CLASSPATH:**
`inspect kind=classpath` over `strategies-orb` lists together `byte-buddy 1.12.21`,
`byte-buddy-agent 1.17.7` and `mockito-core 5.21.0`. `ClassFileVersion.ofThisVm()` lives in
byte-buddy CORE, and 1.12.21 predates Java 21 — which is exactly *"Unknown Java version: 0"*.

**AFTER** — `RunTestsTool.appendResult` gains a `discoveryNote` when a FINALIZED, un-cancelled,
un-timed-out run reports `total == 0`: it states the fact, denies the reading it exists to prevent
(*"this is NOT the same answer as a passing run"*), and names the two causes a caller can check.
Written in the method's own `evidenceNote` idiom rather than as a new mechanism beside it.

**The precedent was already in this codebase**, which says the runner was the outlier rather than
this being a new idea: `ReDerivedJobDetector#report` already adds how many methods and files were
examined *"so a zero result can be told apart from a blind scan"*. The detector family had the
distinction; the runner never got it.

**Control — and the mutation was mandatory rather than diligence**, because the test's own shape
invited the defect it was written to catch: the zero-discovery assertions sit inside
`if (r.isSuccess())`, so had the tool REFUSED the nonexistent class the branch would never have run
and the test would have passed asserting nothing. Short-circuiting the `discoveryNote` branch with
`false &&` went RED on exactly the aimed test at 12/13, and its failure message is the defect
printed verbatim. The test carries the control in the same method: a real run of
`SampleTest#testAddition` must NOT carry the note.

**Still open on D7d:** the byte-buddy version split, which is dependency RESOLUTION and wants the
architect rather than a patch here; and the zero-discovery cause on that project, whose settling
check needs a path the workspace guard holds.

---

## D7a — NOT FIXED. Reproduced, isolated, and the record's own hypothesis refuted

**Commit:** `230ed0d9` (the diagnostic only) · **Tests:** `AnEngineThrowNamesItsPhaseTest`,
`TheShapeD7aBlamedExtractsCleanlyTest` · **Fixture:** `EventProcessorShape.java`

**Recipe** — the recorded one is STALE, and distinguishing that from a repair was the first job:
the record names `startLine=506`, and `OrbEventProcessor.java`'s last method now declares at 492,
so the file SHRANK and the call answers `INVALID_PARAMETER / Invalid selection range`. **A stale
range reads exactly like a repaired defect.** Reproduced on a fresh target:

```
extract kind=method   # OrbEventProcessor.handleTick, 0-based lines 425-426
```

**BEFORE, and STILL** —
`INTERNAL_ERROR: IllegalStateException: source not specified at ASTParser.createAST(ASTParser.java:931)`,
the recorded error verbatim, and identical on a repeat after other files had become active — so it
is deterministic rather than order-dependent.

**THREE CONTROLS, which is what makes this an isolation rather than an observation:**

| probe | result | what it rules out |
|---|---|---|
| `extract kind=variable`, same file, same method, overlapping lines | staged a clean diff | the unit parses and resolves; neither the file nor the selection is the fault |
| `extract kind=method` on `SlotManager.java` — same project, same resident, same package | staged a clean diff, 5-parameter signature correctly inferred | not the project, not the resident, not the source root. **And `SlotManager` is 92,117 chars against `OrbEventProcessor`'s 23,655, so FILE SIZE is ruled out in the direction that matters** |
| `extract kind=method` on a `jawata-mcp` file | reached JDT's own precondition check (`EXTRACT_REFUSED`) | extract-method is not broken in general |

**THE DEFECT RECORD'S OWN CAUSAL HYPOTHESIS IS REFUTED, and that is this row's most useful result.**
It names what distinguishes the file: *"two nested interfaces, a switch over an enum in `onEvent`"*.
A fixture carrying BOTH — `EventProcessorShape.java` — **extracts cleanly, 2 of 2 green.** So the
named features are not the cause, and the next reader should not spend the morning I spent on them.
`TheShapeD7aBlamedExtractsCleanlyTest` began as a scratch probe and EARNED A PERMANENT PLACE by
failing to reproduce: it fixes the negative result so the refuted hypothesis cannot be re-adopted.

**AFTER, and it is a diagnostic rather than a fix** — `ExtractMethodTool.engineThrew` names the
phase, the exception type and the top four frames, and reports the selection offsets. The record's
own prescribed fix was exactly this: *"The error escapes as INTERNAL_ERROR with no phase and no
offset. Catch it at the tool and say which parse threw."*

**The coverage split is named rather than glossed: this is coverage of the FORMATTING, not proof
the branch fires.** No fixture reaches the throw — the only input known to produce it is a file this
repository does not own — so the FORMAT is pinned instead, with an anti-constant control asserting
the OTHER phase is named and the first absent, **without which a hardcoded `"checkAllConditions"`
would satisfy the whole test and the phase, the entire point of the change, would be decorative.**
The wiring is held by the compiler: `engineThrew` is called from the catch around each of the two
engine phases and from nowhere else.

**What is still open: the propagating frame.** It is inside `checkAllConditions` or `createChange`,
and nothing in the response distinguished them — which was the user-facing defect. Three candidates
were read rather than guessed at and all three closed: jawata's own parse is clean (fresh
`ASTParser`, `setSource(cu)`, then `createAST`, and the stale-range run reached
`ast.getPosition(...)` so that parse SUCCEEDS on the failing file); `FormatterOptions.forGeneratedCode`
contains no parser at all; and `CoreASTProvider.createAST` is not the propagating site, because it
wraps its `parser.createAST` in a `SafeRunner` whose `handleException` logs and swallows.

**The root cause needs the resident's stack, and that is the one thing here reserved to Harald.**
The full stack is in the resident's log under `~/.jawata` — a path the workspace guard holds, and
the guard itself says the user must approve it. The in-process alternative was REJECTED on its
merits rather than skipped: loading `strategies_orb` inside a test JVM risks unresolved
dependencies, and an engine behaving differently under a broken classpath would produce a false
lead rather than the resident's real one.
