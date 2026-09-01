# Sprint 28d — findings carried out of C7 into Stage 8

Two items surfaced at the Stage 7 checkpoint that are **not C7 deliverables** and were
deliberately not fixed there. They are recorded here because the C7 auditor flagged the
first one as *a deferral with no named home* — it existed only in a message and in a
plan file outside this repository, which is not somewhere the next executor will look.

Neither blocks C7. Both are Stage 8 work.

---

## 1. The published-schema defect is LIVE in `generate`

**Status: ☑ RESOLVED in Stage 8 (`bbd12a5`), and resolved in the order this dossier
asked for — the guard generalised FIRST, the instance repaired second.**

> **What happened.** The guard was written before Stage 8 added any kind, applied to
> all three parametric front doors, and it went **RED on this defect**: *"generate
> kind=getters_setters accepts 'getterStyle' and the front door does not declare
> it"*. Suite 2188 succeeded / **1 failed** before the repair, 2189 / **0 failed**
> after, with production code the only change between the runs — so the flip is
> attributable, and the guard is an instrument rather than decoration.
>
> `generate` got the same `putIfAbsent` delegate overlay `extract` received.
> `RefactorToPatternTool` got it too **although it passed**: it was complete when the
> guard was written, and so were the other two right up until their next kind was
> added. Being complete today is not a property that survives the next addition
> unless something derives it.
>
> The guard lives in `DeclaredShapeHonestyTest` beside the enum-axis instruments, as
> recommended below, and each row asserts its own delegate list matches the published
> enum first — so a new kind makes it go red rather than letting it check n−1 of n.

**Original finding, kept for the record: unfixed, reproducible, shipping in the
released dist.**

### What it is

`GenerateGettersSettersTool.getInputSchema()` declares three parameters:

- `getterStyle` (`classic` | `record`)
- `setterStyle` (`classic` | `fluent`)
- `generateJavadoc` (boolean)

`GenerateTool.getInputSchema()` — the front door, and the only schema that reaches a
client, since `ToolRegistry` registers the front door and not the delegate — declares
**none of the three**.

### Why it is worse than the `extract` case it was found from

`generate`'s own prose description **advertises all three**. So the two halves of the
published contract contradict each other:

- a client reading the description sends a parameter the schema does not declare;
- a client trusting the schema never learns the parameters exist.

The `extract` instance (fixed in Stage 7 as S7.7) at least failed silently in one
direction only.

### How to reproduce without reading any code

List the tools over the wire and read `generate`'s input schema. Its properties are
`accessorKind, auto_apply, callSuper, column, fields, filePath, framework,
includePrivateMethods, indentChar, kind, line, methods, newTypeName, projectKey, style,
typeName, visibility`. The three named above are absent, while the description text
beside them names all three.

### The cause, which is not "someone forgot"

A hand-written schema sitting beside a dispatch switch is a **copy** of the delegates'
contracts, and a copy of a changing surface is wrong from the first unmirrored change,
with no moment at which it announces itself.

### What already exists, and where its gap is

`DeclaredShapeHonestyTest` is a test class whose entire thesis is this defect family —
*"a declared shape that lies about the real one."* Its four instruments all guard the
**action/kind enum** axis: the declared action set must equal the routed action set.

**The parameter axis is unguarded, and both known defects landed there.** The kind was
declared correctly in each case; its parameters were not.

### Recommended fix, in order of value

1. **Generalise the guard.** Stage 7 added `ExtractToolTest#schema_publishes_every_delegate_parameter`,
   which asserts every parameter every delegate declares is published, and first asserts
   its own delegate list matches the published enum so it cannot silently under-cover.
   That guard is specific to one tool. The parametric front doors share a shape; the
   guard should too. This is the fix that removes the defect class rather than the
   instance, and it belongs in `DeclaredShapeHonestyTest` beside the enum-axis checks.
2. **Then repair whatever it finds.** `generate` is one known instance; the sweep that
   found it was not exhaustive, and other parametric front doors were not checked.

Do **not** hand-copy the three parameters into `GenerateTool`. That fixes one instance
and leaves the next kind to repeat it — the same reasoning that made S7.7 a map rather
than five more hand-written entries.

---

## 2. Cross-file reference migration is demonstrated nowhere

**Status: ☑ RESOLVED in Stage 8 (`11057fc`), and the boundary is closed by PROOF
rather than by documentation — the operation does follow its readers across a file
boundary.**

> **Harald's ruling, 2026-08-29**, which chose between the two options this item
> offered: *"Sure we do this. Within the same file would make this refactoring only
> halfway."* A field move that does not follow its readers is not a move.
>
> **The omission was STRUCTURAL, not an oversight, and that is the part worth
> keeping.** Both fixtures named below declare the moved fields `private`. A private
> field cannot be read from another file at all, so no test built on either could
> have covered this case. It was not untested; it was **untestable** — which is why
> it survived a checkpoint and a fresh-context audit. Nothing available to either
> could have caught it.
>
> `ExtractClassAcrossFilesTest` drives a new fixture, `extract-crossfile`, whose
> moved fields are **package-private** — the narrowest visibility a second file can
> reach, therefore the narrowest shape that can ask the question. `Report.java`
> carries three shapes the rewrite must follow: a read through a parameter, a read
> through a local, and a **write** from outside, which a read-only rewrite would
> miss.
>
> What decides it is a compile **with bindings**, not a text match: if the fields
> move and their readers do not follow, every reference resolves to nothing. A
> textual assertion could not take its place, because the correct rewritten form is
> the engine's to choose and asserting one spelling would fail a correct result.
>
> Undo restores BOTH files byte-identically. Purity holds with its boundary redrawn:
> the operation is *expected* to modify a file the caller never named, so the claim
> asserted is that it touched only what it had a reason to touch.

**Original finding, kept for the record: a disclosed coverage boundary, not a
defect.**

Extract Class is proven to move a field cluster and rewrite the accesses **within the
declaring file**. No test demonstrates it rewriting a reference from a *different* file,
because neither fixture has one:

- `ExtractClassForkSliceTest` states this explicitly in its javadoc — no other file in
  the fork slice touches `failureCount`, `lastFailureTime` or `lastFailureResponse`.
- The `simple-maven` fixture used by `ExtractClassToolTest` is the same shape.

C7 does not require it, and the omission is disclosed in the tests rather than concealed.
But an operation whose contract includes *migrating the references* has not been shown
doing so across a file boundary, which is the case most likely to break in real code.

Stage 8 should either add a before-case where the moved state is read from a second file,
or record explicitly that the operation's guarantee stops at the file boundary.

---

## 3. Rank 3's parity is proven on the authored fixture only

**Status: ☑ RESOLVED at C9, and the C9 auditor is why.** Stage 9 was named as this
item's home and ran on this very fixture *without closing it* — the auditor caught
that, correctly, as a finding homed and then passed over.
`ReplaceConstructorWithFactoryForkSliceTest#theOperationMeetsCodeWeDidNotAuthor` now
parses BOTH touched fork files with bindings and asserts zero errors: the factory
file whose call site moved, and the product file the factory was generated onto. The
asymmetry with rank 2 — whose fork slice always compiled its rewrite — is gone.

**Original finding, kept for the record.**

`ReplaceConstructorWithFactoryForkSliceTest#theOperationMeetsCodeWeDidNotAuthor`
asserts the file changed and that it contains `newArmy`. It never COMPILES the result.
So for rank 3, "the code we did not author" clause proves the operation runs on
unfamiliar code, and parity on unfamiliar code is proven nowhere — parity lives on the
authored fixture.

Rank 2 does not have this gap: `ReplaceConditionalForkSliceTest#collapsesTheDispatchInForkCode`
parses the rewritten fork file with bindings and asserts zero errors.

**The fix is one assertion** — the same `compileErrors(...)` helper the sibling test
already uses. It is recorded rather than done because the C8 auditor ranked it
non-blocking and the round cap is explicit: at the cap, remaining findings are named
open items, never another round. Stage 9 exercises both operations again on
human-authored originals and is where this is cheapest to close.

## 4. A rank-3 test's javadoc promises more than its body asserts

**Status: ☑ RESOLVED at C9, beside item 3 and for the same reason.**
`theOldPathCanBeLeftOpenOnPurpose` now asserts BOTH halves its javadoc promises: the
constructor stayed reachable AND the factory was added. Without the second, an
operation that added nothing while leaving the constructor alone would have passed —
the flag was pinned in one direction only.

**Original finding, kept for the record.**

`ReplaceConstructorWithFactoryToolTest#theOldPathCanBeLeftOpenOnPurpose` says in its
javadoc that "the factory is added and the constructor stays reachable". The body
asserts only the second half. It still discriminates on the `protectConstructor` flag,
which is what the test is for, so nothing is unguarded — but the javadoc describes an
assertion that is not there, which is the family this sprint has now paid for three
times (S7.7, S7.8, C8's B1).

## 5. The round trip cannot check that our factory looks like a human's

**Status: RESOLVED AT S9a.1 (2026-08-30) — MEASURED, and the recorded cause was
wrong.** This item said the blocker was the CORPUS: no fork site could serve as an
AWAY-first original. S9a re-measured and found the blocker is the OPERATIONS, which is
a different finding with a different home. **The deviation is no longer a deferral**;
what remains for Sprint 28e is the two operation-level findings at the end of this
item, filed as issues against the creational operations.

> **What the corpus argument got right and wrong.** Right: zero of the nine sites can
> run the trip today. Wrong: the reason. Accepting Lombok in one vendored fixture —
> this item's own first way back — would have unblocked `ChapterResult` on the
> dependency axis and changed nothing, because the AWAY leg refuses it for a reason
> the survey never looked at. **The cost of finding out was one authored fixture and
> two builds**, against a vendoring exercise that would have ended at the same wall.

> **The earlier status line said "whichever sprint next touches the creational
> operations", which the C9 auditor correctly refused as a rule for finding a home
> rather than a home.** It is now named.

D3 says: take a canonical implementation, run the AWAY direction, then TOWARD, and
compare with the human original. Stage 9 runs TOWARD then AWAY instead, from
unpatterned human code.

**Why, measured — and the first two measurements were WRONG.** The literal direction
needs a human-written SELF-RETURNING STATIC FACTORY as its starting point, because
that is the only factory shape our TOWARD direction produces.

> **CORRECTED at C9.** This paragraph said *"six sites, in four classes, every one
> carries Lombok"*. The auditor re-ran the survey independently and found more sites
> and a dependency-free one, which falsified the only stated reason for the deviation.
> A second attempt was also wrong — it counted an interface's lambda and anonymous-class
> factories by searching a fixed character window that ran out of one method and into
> the next. **The method is now a file**, `build/survey-self-returning-factories.py`,
> which brace-matches the method body, refuses anonymous instantiation and non-class
> types, and records both earlier defects in its own header.

**What the corrected survey reports — 9 sites in 6 classes:**

| Class | Sites | Dependency | Constructor |
|---|---|---|---|
| `saga/orchestration/ChapterResult` | 2 | Lombok | package-private |
| `saga/orchestration/Saga` | 1 | Lombok | private |
| `saga/choreography/Saga` | 1 | Lombok | private |
| `component/GameObject` | 2 | Lombok + 7 others | implicit |
| `hexagonal-architecture/LotteryNumbers` | 2 | Lombok + 1 other | private |
| **`monad/Validator`** | 1 | **NONE** | **private** |

**So "every one carries Lombok" was false — and the conclusion survives on a better
reason.** Exactly one site is dependency-free, and its constructor is PRIVATE. This
trip is only defined where the old path stays open: the AWAY leg folds the factory
back into its callers, which cannot compile against a constructor they may not reach.
**Zero of the nine can serve as an AWAY-first original**, and C9 asks for three.

**And the decomposition, stated precisely — because "Lombok was never the real
blocker, constructor accessibility is" is ALSO not what the numbers say.** Of the
nine: **8 are blocked by a dependency the fixtures exclude, and 5 by a private
constructor.** Neither filter alone reaches zero — the dependency filter leaves one,
accessibility leaves four. The true and narrower claim is: *the single site the
dependency filter does not remove is removed by accessibility.* Both are load-bearing;
neither is "the real one".

The original claim reached the right conclusion by a route that does not hold — which
matters more than the conclusion, because the route is what a later reader trusts.
Raised by the C9 auditor at round 2, alongside its finding that this correction had
reached the plan's body and not its status board.

**What the reversal costs, and it is not nothing.** The literal form would test that
our TOWARD direction *reproduces the factory a human chose to write*. The reversed
form cannot, and the two shapes are known to differ: a human writes an instance
factory on a separate type; the operation writes a static method on the type itself.
That is the check most likely to catch our operations drifting away from how people
actually write the pattern — so it is the one worth getting back.

**Two ways back, neither taken here.** Accept Lombok in one vendored fixture, paying
annotation processing for that fixture only; or write the comparison against a
*shape*, not bytes — assert the human's factory and ours agree on what they do rather
than on how they are spelled, which is a different and weaker claim that should be
stated as such if it is ever adopted.

Raised by the architect seat at the C9 watch-diff (F2), which judged the handling
correct and the recording insufficient: it was declared in a commit message, a test
javadoc and the plan, and would have been re-discovered rather than remembered.

---

### S9a.1 — what the trip actually does, measured on a fixture shaped on `ChapterResult`

Fixture `partial-factory` holds two classes differing in ONE property, so the first
finding has a cause rather than a correlation: `Outcome` is `ChapterResult`'s shape
(generic, two-argument package-private constructor, two intention-named factories each
fixing one argument), and `Verdict` is `Outcome` with the type parameter removed.
Guarded by `PartialApplicationRoundTripProbeTest`, both tests green.

**FINDING A — the AWAY leg refuses a GENERIC factory, and that alone closes D3's
direction.** Inlining `static <K> Outcome<K> success(K val)` returns
`REFACTORING_BROKE_COMPILE / Cannot infer type arguments for Outcome<>`, and the engine
UNDOES the change rather than leaving the tree broken — the right behaviour, and
asserted as such. The body's diamond infers from the method's own type parameter, which
stops existing once the body is folded into a caller.

`ChapterResult.success/failure` is generic in exactly this way. So is every other
generic factory in the survey, and a generic self-returning factory is how the pattern
is normally written — the first two surveys were blind to generics precisely because
they are so common. **No corpus choice and no dependency concession reaches this.**

**FINDING B — with genericity removed the trip RUNS, and returns a different KIND of
factory than the human wrote.** On `Verdict`, AWAY succeeds (which is what attributes
Finding A to genericity), TOWARD succeeds, and the trip does not close:

| | The human wrote | After AWAY then TOWARD |
|---|---|---|
| the factory | `success(String val)` — HIDES `State.SUCCESS` behind a name that states an intention | `of(String value, State state)` — a FORWARDER exposing every constructor parameter |
| every call site | `Verdict.success(order)` | `Verdict.of(order, Outcome.State.SUCCESS)` |

**So the trip does not merely fail to reproduce the human's text: it pushes the constant
the human deliberately hid back out to every caller.** That is the opposite of what
Replace Constructor with Factory Method is for, and it is asserted as a one-to-one
correspondence — every call site that hid the constant now spells it — rather than as a
count, so it holds however many call sites the fixture has.

**This is D3's more valuable half, answered.** The question was whether our TOWARD leg
reproduces the factory a human chose to write. It does not, and the difference is
structural rather than cosmetic: one mechanical forwarder cannot be two intention-named
partial applications, whatever it is called.

**Not asserted to be a defect.** A forwarder is a defensible thing for a mechanical
refactoring to produce. What it is not is what a human wrote — and D3 exists to measure
exactly that gap, which is now measured instead of deferred.

**ONE CONFOUND, removed rather than left in.** The first run passed
`factoryMethodName: "success"` and got back `failure(v) { return success(v, FAILURE); }`
— a method named `success` constructing a failure. That absurdity was the test's own
input, not the operation's behaviour, so the run was repeated with a neutral name and
the finding above is from that run.

**FOR SPRINT 28e, as issues against the creational operations:**
1. `inline(kind=method)` cannot invert a generic factory (Finding A). Whether that is
   fixable — re-materialising the inferred type arguments at each call site — or is a
   permanent limit is the question to file, not a fix to assume.
2. `replace_constructor_with_factory` emits a forwarder where the idiomatic shape is
   often a partial application (Finding B). A `fixedArguments` parameter is the obvious
   direction and is NOT proposed here; the finding is the input to that design, not its
   conclusion.

## 6. The property is proven for ONE operation pair, not for the engine

**Status: a scope fact to keep restating. No home needed; it needs not being rounded
up.**

The round trip holds for `replace_constructor_with_factory` inverted by
`inline(kind=method)`. That is one pair out of ten pattern operations. **Nine have no
inverse and are unmeasured by this property**, including both operations Stage 8
shipped.

The reason there is no more to prove is structural rather than an omission: counted
off the front door's own description, there are **8 TOWARD operations and 2 AWAY, and
no pattern carries both halves**. Closing that would mean building an inverse
operation — a rank-2-scale build, immediately after C8 cut four operations of exactly
that cost.

The finding is recorded because "the round trip has a fixed point" reads, in any
summary, as a claim about the refactoring engine. It is a claim about one pair.

## 7. A story's own section headings become its recall cues

### R4 MEASURED 2026-08-30 — four of mcp#7's five symptoms are gone; the class is not

**Stage 10's entry gate is R4: rule on `mcp#7`.** The plan's note said the issue
"reads CLOSED, so this gate may be dischargeable". **It is OPEN** — that note is
stale. Measured against the running store (297 entries, 107 file-backed, 187
catalogue):

| mcp#7 symptom | measured | how |
|---|---|---|
| 1. every section lands as `type: note`, starving the primer | **GONE** | no `note` type in the store; primer serves domain rows at SessionStart |
| 2. backticked tokens harvested as symptoms | **GONE** | `recall(symptom="grep")` returns proper stories, not a CLAUDE.md section |
| 3. two byte-identical CLAUDE.md files double-ingested | **no duplicate rows** | no CLAUDE.md section returned by any probe. **CAVEAT: the precondition — whether both files still exist and are identical — could NOT be checked; the workspace guard blocks `/home/harald/CLAUDE.md` and needs Harald's approval.** So the OUTCOME is measured absent, the CAUSE is unverified |
| 4. marker sections (`<!-- jawata-studio:claude:start -->`) become entries | **GONE** | `recall(symptom="jawata-studio:claude:start")` returns no such entry |
| 5. `dedup` blind to byte-identical pairs | **`group_count: 0`** | and no duplicate pair surfaced by any probe. Note this is a weaker measurement than the others: zero groups is consistent with "no duplicates exist" AND with "still blind", and nothing here separates them without creating a duplicate, which would pollute the store |

**BUT THE DEFECT CLASS IS LIVE, IN A DIFFERENT MARKER.** Symptom 2 was cues
harvested from backticks. `harvestKeywords` now excludes inline code spans —
citing mcp#7 by number — and harvests **bold spans** instead. The story template
writes every section heading as a leading bold span, and `AdmissionPolicy.classify`
calls something a HEADING only when it starts with `#` or ends with `:`, so
*"The case."* classifies as PROSE and is admitted as a cue.

**The discriminating measurement, 2026-08-30:**

- `recall(symptom="The case")` → **FIVE DIRECT HITS**, whose only relationship to
  the phrase is `**The case.**` appearing as a section heading in their bodies.
- `recall(symptom="Why this correction exists")` → **zero direct hits**, semantic
  neighbours only.

The contrast is the evidence: one phrase that appears as a bold heading resolves as
a cue, one that does not appear at all resolves as nothing.

**AND THE DESIGN FAULT UNDER IT, which is why narrowing the marker is the wrong
fix.** The frontmatter parser has **no `symptoms` case at all**, and is a
single-line `key: value` parser. An author literally cannot state their own cues.
So the loader must guess, and the guessing heuristic is the symptom rather than
the disease — mcp#7's own suggested fix (c) said *"symptoms only from an explicit
frontmatter field"*, and only the first half of that clause was implemented.

**SCOPE, measured:** `harvestKeywords` has exactly two callers, `parse` and
`section`, both on the markdown load path. The catalogue lane reads symptoms from
JSON and is unaffected — so this touches the 107 file-backed entries, not the 187
catalogue rows.

**COST OF THE POLLUTION, corrected before it was put to Harald:** an earlier draft
called it "not cheaply undone". Measured: 107 of 297 entries are file-backed and
repairable by re-loading them once the parser accepts declared cues. It is not a
one-way door.

---

## 7b. The original finding, as first recorded

**Status: OPEN, and it is Stage 10's entry gate (R4). Home: this sprint, before
Stage 10 — the ruling is Harald's.**

**Measured 2026-08-29** by loading three story files into the live store and reading
one back in full through `nominate` + `decide`. Its harvested `symptoms` array carried
fourteen items, of which eleven are the story's own **bold section headings**:
*"The case."*, *"The gap."*, *"Why it survived being written down."*, *"The repair,
and it is one line."*, *"Where it does not hold."*

**This is NOT `jawata-mcp#7`, and that misattribution is worth recording.** The issue's
symptom 2 is *"every backticked token is harvested as a symptom"*, and that half is
FIXED: `ExperienceMaintenance.harvestKeywords` excludes inline code spans deliberately
and says so in its own javadoc, citing the issue by number. Not one of the fourteen
items contains a backtick. The defect was reported under a known issue's name because
the OUTPUT resembled the issue's description — the code that produced it said the
opposite, in a comment, next to the exclusion.

**What is actually happening.** `harvestKeywords` takes `**bold**` phrases as cues
(Sprint 21c item A, deliberate and reasonable). The story template writes every
section heading as a leading bold span — `**The case.** A desktop application shows…`
— so a story's headings become its symptoms. The admission filter cannot catch them:
`AdmissionPolicy.classify` calls a string a HEADING only when it starts with `#` or
ends with `:`, so *"The case."* classifies as **PROSE**, and `misplaced(PROSE)` is
false. They are grammatically prose; that is exactly why the filter passes them.

**Two conventions collide.** `unheading` already exists so that load never mints a
heading-shaped SUMMARY. Nothing does the same for a SYMPTOM.

**The fix, sized:** a bold span that BEGINS its line is a section heading in this
template, not an inline cue; mid-line emphasis stays. Roughly five lines in
`harvestKeywords`, plus a test using a real story file — and the test matters more
than the fix, because the current behaviour has no instrument at all.

**Which lane it affects, measured rather than assumed.** `harvestKeywords` has exactly
**two callers**, both inside `ExperienceMaintenance` — `parse` and `section`
(`get_call_hierarchy incoming`). Both sit on the MARKDOWN load path
(`experience(kind=load|reseed)`), which is what Stage 10's *"ingest sampled by a
person before bulk … crawl bounded"* names — "crawl bounded" is that class's own
depth/file/byte limits. **The fork CATALOGUE lane is unaffected:**
`CatalogueManifest.entryFor` reads `symptoms` straight from the manifest's JSON rows
and never calls the harvester.

**AND THE COST OF ENTERING ANYWAY IS LOWER THAN THIS ITEM FIRST CLAIMED.** The
sentence here used to end *"a polluted corpus is not cheaply undone"*. That is
**wrong for this lane**, and the correction matters because it was about to be put to
Harald as the reason to rule one way. The store is rebuilt from a FILE SUBSTRATE and
`load` is idempotent per source — re-load replaces. So entries ingested from files
get their cues **re-derived** by re-running the load after a fix; measured today, 107
of 297 entries carry a `memory:` source path and are repairable exactly that way.

**What remains true.** The defect is real and live; entries written straight to the
store rather than from a file are NOT repairable this way; and every recall made
against a polluted store in the meantime is degraded. The gate is worth keeping — but
as a *do it first, it is five lines* argument, not a *this is irreversible* one.

## Provenance

Items 1 and 2 were found at the Sprint 28d C7 checkpoint (Stage 7, Extract Class).
Item 1 was found by sweeping for the *cause* of a defect the architect seat raised,
after fixing its instance; item 2 was raised by the C7 fresh-context auditor. Neither
was fixed in C7, because a checkpoint is not the place to widen scope. **Both are now
RESOLVED in Stage 8** — item 1 at `bbd12a5`, item 2 at `11057fc`.

Items 3 and 4 were raised by the C8 fresh-context auditor as NON-BLOCKING (N4 and N1
in its report), alongside five blocking findings which were repaired inside the
checkpoint at `e04f539`. They are carried rather than fixed for the reason stated in
item 3: the round cap.

## 8. Nothing checks that a catalogue row's entry-point address opens

**Found at the Stage 10 checkpoint, 2026-08-30, by diffing the regenerated snapshot
against the committed one — a comparison that existed nowhere.**

A catalogue row carries TWO addresses and only one of them is guarded:

- `source_ref` — the README path. `CatalogueAddressesOpenTest` resolves it against real
  files. But it checks only origins where `inWorkspace()` is true, and the fork is not in
  this workspace, so **all 187 fork rows are skipped by name**.
- `entry_point_class` — the Java entry point, e.g. `com.iluwatar.builder.App`. **Checked by
  nothing at all.**

**The cost, measured:** 111 of 187 committed entry-point addresses resolve to a real source
file at the pin. The other **76 point at nothing** — they were composed from the slug
(`com.iluwatar.backendsforfrontends`) where the tree holds `com.iluwatar.bff.App`. Both
were checked; only the resolved one exists.

**Why this is carried rather than fixed.** The composer was already replaced by a resolver
at S5, so regenerating repairs all 76 — that is decision 1, and it is Harald's. What is NOT
fixed is the missing GUARD. A test that resolves entry points against the fork would be a
gate bound to someone else's checkout, which cannot run where that checkout is not; the
recorded lesson on that is explicit. The honest options are a resolver-side invariant (the
extractor only ever emits an address it read off a real file — true today by construction,
asserted nowhere) or an origin-aware address check that can see out-of-workspace origins.

**Home: Sprint 28e**, with the address-checking work.

## 9. The plan's own counts were wrong in four places, and only measuring found it

**Not a code defect — a process one, and it recurred inside a single stage.**

Stage 10's text asserted "185 of 188 READMEs declare `category:`, across 16 values" and
"three READMEs declare none". Measured at the pin: **187 READMEs, all 187 name a family,
15 values.** Nothing was missing; two spell the key `categories:` and one count was wrong.

The same numbers had been copied into two javadoc blocks, which then survived four commits
that disproved them — corrected at `df19c71`. That is the C9 finding again: *a correction
that reaches one artifact and not the others is not a correction.*

**And one verification of that correction was VACUOUS.** A `grep` over
`CatalogExtractor.java` returns NOTHING while the matched text is plainly in the file —
confirmed by reading it. So the sweep that was to prove the stale numbers were gone
reported clean while unable to see the file at all. Every later check on that file used
`Read`. **An empty result from a tool that cannot see the file is indistinguishable from an
empty result meaning nothing is there** — and this project has met that shape before.

**Home: worth a tooling issue against whatever intercepts that path**, and worth the
standing habit of never accepting a clean sweep as evidence without a positive control.

## 10. Key ORDER in the snapshot is load-bearing and nothing pinned it

**Found by the parity check at `9920abd`, not by any test.**

`CatalogueManifest.hashOf` digests `row.toString()` — the whole serialised row — and that
hash decides seeded-versus-unchanged. So moving one field from one position to another,
with every value identical, re-hashes all 187 rows and costs a full supersede-and-rewrite
for no semantic change whatsoever.

Folding three repeated presence-guards into one writer did exactly that: `cause` moved
below `source_ref`, values byte-identical, file different. **No test would have caught it,
because every test asserts on VALUES.** A regeneration compared against the previous
artifact caught it in one command.

Now pinned by `the_field_order_is_part_of_the_artifact`, with the cost in the failure
message. Carried here because the general lesson is not about this field: **wherever an
identity hash covers a serialisation, the serialisation's shape is part of the contract**,
and that is true of any other row-hashed artifact in this codebase.

**Home: no work item — recorded so the next person who reorders knows what it costs.**

---

## 11. The tiered cure renders on ONE detector, though five declare cures

**Found by the Stage 12 as-built pass (S12.1), 2026-08-31 — not by any gate.**

The as-built pass is CLEAN: all seventeen capabilities this sprint claims resolve to real
symbols, every one has a production caller, and every chain terminates at a tool
`JawataApplication` registers. Nothing is test-only and nothing is unresolved. Two of the
strongest pieces of evidence are worth keeping because they close defect classes this
sprint spent months on:

- **Registration IS publication for detectors.** `FindQualityIssueTool.getInputSchema()`
  projects its `kind` enum straight from the catalog the detectors register into, so a
  kind cannot be routed-but-unpublished. There is no second list to drift.
- **The store is threaded in PRODUCTION.** `JawataApplication` uses the two-argument
  `FindQualityIssueTool(jdt, store)` constructor; the storeless one-argument form exists
  for tests and says so. That is the exact v3.4.0 defect — a feature inert in production
  while its tests supply the wiring themselves — and on this branch it is closed.

**The finding, which is a THINNESS rather than a break.** `CureTier.derive` has exactly
one production caller (`CureLookup.Cures#hint`), and `hint()` has exactly one
(`OcpDetector`). So the address-resolved, tiered cure sentence ships on **`ocp` findings
only**.

> **SCOPE CORRECTED 2026-08-31 by the architect's as-built pass — I undercounted this
> by more than half.** It is not "the four new principle detectors". Measured over the
> whole table, there are THREE rungs:
> - **`ocp`** — resolved address AND derived tier.
> - **`divergent_change`, `shotgun_surgery`** — `CureCatalog.ocpHint()`: plan kinds, no
>   address, no tier, built from the IDENTICAL `OPEN_THE_AXIS` rows that `ocp` resolves
>   fully.
> - **EIGHT kinds render nothing** — `switch_statements`, `type_code`, `singleton`,
>   `long_method`, `cqs`, `coupling`, `composition_over_inheritance`, `encapsulation`.
>
> **And the sharpest instance is not among the new detectors at all.** `OcpDetector`
> HOLDS instances of `SwitchStatementsDetector` and `TypeCodeDetector` and relabels their
> findings — so the same measurement, at the same line, carries a resolved address plus
> `TIER: PERFORM` when swept as `ocp`, and a bare sentence when swept under its own kind.
> The drift the S7 fold was built to kill (two homes for one fact) has reappeared one
> layer up, as three renderings of one table.
>
> Cost of closing, corrected: **8 kinds gain a cure sentence and 2 change one** — not the
> 4 this item first estimated.

Those rows are still reachable through two other doors, so this is not a hollow member:
`refactoring(action="plan")` reads `CureCatalog.recipesFor`, and `experience(kind="stats")`
runs `CureLookup.audit` over **every** declared operation. But a reader of a `cqs` finding
gets less than a reader of an `ocp` finding, for no reason a user could infer.

**Home: Sprint 28e.** It is a one-line-per-detector change (call `CureLookup.forKind` and
append `hint()`, as `OcpDetector.relabel` does), but it changes user-visible finding text
on four kinds, which is a deliberate act and not a drive-by.

## 12. The running resident is not this branch — read the SOURCE, not the live schema

**Recorded at S12.1 so nobody draws the wrong conclusion in either direction.**

The live `jawata-javata-dev` server is v3.17.2, and its published `find_quality_issue`
kind list does NOT contain `cqs`, `coupling`, `composition_over_inheritance`, `ocp` or
`encapsulation`. That is a fact about a deployed binary predating this work — **not**
evidence that the branch fails to register them, which the as-built pass measured
directly and found registered.

The trap runs both ways: a live schema showing the kinds would not prove the branch
registers them either, because the resident could be newer than the checkout. Any
wiring claim about this sprint is answered from the source and the built dist, never
from whatever happens to be running.

---

## 13. Sprint 27's headline gate has no corpus and cannot run on any machine

**Found while reading the two `aborted` cells in the v4.0 release suite, 2026-09-01 —
not by any gate. The suite has reported these two as aborted on every run, which is
correct behaviour and is the only reason they were ever noticed.**

`CalibrationGateTest` is Sprint 27 C4: twelve cues, phrased as a person would ask them,
scored against accept sets frozen at C0 *before any retrieval code existed*. A cue passes
when the winning entry is in its accept set and the designated entry is inside the
twelve-wide nomination window. Bar `≥10 of 12`, against a measured keyword baseline of
`1 of 12` — that gap is the whole claim, not the absolute score.

It aborts, loudly and by design, when `-Djawata.embed.corpus` names no readable file. Its
own comment says why: *"a gate that silently skips is indistinguishable from one that
passed"*, and an earlier version returned instead of aborting, which JUnit recorded as a
PASS.

**What is actually wrong is not the missing file.** The accept sets ARE committed —
`org.jawata.mcp.tests/test-resources/embed-goldens/accept-sets.json`, in the repository
since July. The corpus they are answers *about* is not: the javadoc describes it as *"a
dump of a real 2054-entry store, which is NOT committed (it is personal knowledge, and
pinned by sha256 rather than vendored)"*. So the repository holds committed answers to
questions about absent data.

**It cannot be repaired by exporting the current store.** Measured 2026-09-01: the live
store holds **305 entries** against the 2054 the accept sets were frozen against, with 202
tombstones and a rebuild in between. The accept sets name entries by opaque id — the
paraphrase cue's designated answer is `5f7373f4`. Against a different corpus those answers
are mostly not present to be found, so a run would fail for reasons that say nothing about
retrieval, and a pass would be as meaningless as a failure.

**Nor by committing the old dump.** A dump is frozen to one schema; committing it means
migrating it on every schema change, and a migrated corpus is no longer the artefact the
answers were frozen against.

**The design fault, stated generally:** the gate's input lives outside the product. A check
whose corpus is a personal file on one machine runs only on that machine, only until the
file moves, and reports nothing when it stops — which is exactly what happened. Nothing
broke; the input left.

**The repair (Harald's, 2026-09-01).** Author the corpus as CASES, in the `.md` +
frontmatter form the store's substrate already uses, and load them with the ordinary
`experience(kind=load, path=…)` reader into a THROWAWAY store; measure; drop the store.
Three properties follow, and each answers one of the objections above:

- **Schema drift disappears** — the cases are source, not a database. Loading writes them
  through today's writer against today's schema, every run. Nothing to migrate.
- **One reader serves both** the real substrate and the benchmark, so a fix to the loader
  cannot reach one and miss the other. A bespoke fixture format would be a second
  lifecycle nothing forces to agree with the first.
- **The frozen answers become readable.** A story file is identified by its `name:` slug
  rather than `5f7373f4`, so the expected answers are names a reviewer can check — which
  matters most here, because the answers are precisely what nobody may quietly adjust.

Two constraints are load-bearing and must not be dropped in execution:

1. **The cases need deliberate near-misses or the benchmark measures nothing.** The old
   corpus was hard because 2054 real entries contain plenty of things that merely *look*
   like the answer. Thirty clean cases with one obvious answer each would score 12 of 12
   on keyword matching too, collapsing the gap against the `1 of 12` baseline that is the
   entire point. Each case needs plausible wrong answers beside the right one — same
   vocabulary, different meaning. Built that way a small corpus is *harder* than the real
   one, by design rather than by luck.
2. **Write the cases and questions, freeze the answers, THEN run it the first time.** The
   original accept sets were frozen before retrieval code existed, and that is the only
   reason they mean anything. Answers written after watching what the search returns are
   self-marking.

**One hazard to design around:** the case files must live where a reseed does NOT crawl.
`experience(kind=reseed)` sweeps the configured default roots and follows `[[wikilinks]]`
transitively, so cases under `docs/knowledge/stories` would be loaded into the real store —
including the deliberately wrong ones, which are written to be plausible and would then
answer real questions. They belong under this repository's test resources, beside the
accept sets, with no link path from the substrate.

**Home: Sprint 28e**, and it must land BEFORE Sprint 29. The reason is 29 rather than
anything about 28d: 29 launches on v4.0, and launching with retrieval quality unmeasured
is the version of this that costs something. Tonight's release does not, because no run
since the store was rebuilt could have executed this gate either.

**Scope:** a day. The cases are the work; the loading and dropping are trivial.

---

## 14. A quality benchmark is installed as a per-commit test

**The same reading, 2026-09-01. Separate finding because it has a separate repair, and
fixing 13 without fixing this one just relocates the problem.**

`CalibrationGateTest` is not a test. A test asserts behaviour that must hold on every
commit. This measures *how much semantic retrieval buys over keyword matching* on
realistic data — a number that only means something when the retrieval algorithm changes,
and that requires a corpus and answers cut together as one artefact.

Installed in the per-commit suite it takes a slot in all 2241 tests, aborts, and reports
nothing. That is the least useful of the three possible states: it neither guards anything
per commit nor gets run deliberately when retrieval changes.

**The repair is two moves, not one:**

- **Move the benchmark out of the suite.** It runs when retrieval changes, against a
  snapshot taken then, with answers frozen then. Corpus and answers are ONE artefact —
  an old corpus with new answers and a new corpus with old answers are both meaningless.
- **Put a smaller guard in its place**, if retrieval needs per-commit cover at all. That
  is a different and much cheaper thing: a handful of committed entries proving the search
  is wired, ranks, and honours the nomination window. **Mechanism, not quality.** It can
  live in the suite honestly because it asserts something that must hold on every commit.

**Home: Sprint 28e**, with finding 13 — the two share the case-authoring work.

---

## 15. Publishing a release makes every user's machine unusable for minutes

**Measured on this machine at 2026-09-01 13:59, seventeen minutes after v4.0.0
published — by the user noticing, not by any gate.**

v4.0.0 was published at 13:42. At **13:59:47** all three jawata residents
restarted together onto `jawata-v4.0.0/jawata.jar`, and each began re-importing
its workspace from cold:

```
651374  13:59:47  189%   javata-dev
651380  13:59:47  211%   orb-strategy
651414  13:59:47  133%   patterns
```

Plus their spawned children — one caught running a Maven `dependency:build-classpath`
against `java-design-patterns`, a large repository. Total draw was roughly **1000% CPU,
about ten of twenty cores**, sustained. Load average 17.07. The user's report was
"machine was stuck for minutes", and that is exactly what it was.

**The mechanism.** A release lands; studio detects it, downloads it, and restarts
the fleet. Every resident then rebuilds its workspace index from nothing, at the
same instant, because they all learned about the same release at the same time.
Nothing staggers them and nothing waits for the machine to be idle.

**Why this is a product defect and not an accident of tonight.** It is not
specific to this release, this machine, or three workspaces. Any user with more
than one workspace gets a simultaneous cold re-index the moment we publish
anything — and the more workspaces they have, the worse it scales. The moment we
ship is the moment their machine becomes unusable, which is the worst possible
pairing: our release, their outage, and no warning that the two are connected.

**The asymmetry that makes it embarrassing.** This same sprint added a guard that
refuses to start the TEST SUITE beside a competing heavy job, because contention
manufactures failures that read as regressions. So the product protects its own
suite from other people's load and imposes exactly that load on its users. The
guard proves we understand the failure mode; we simply pointed it inward.

**Directions, none of them chosen here:**

- **Stagger.** Residents restart on a spread rather than together — the cheapest
  fix and it does not reduce total work, only its concentration.
- **Defer the re-index.** Restart onto the new engine immediately (that is cheap);
  rebuild the index lazily, on first use or when the machine is idle. The
  expensive half is the import, not the restart.
- **Ask.** A release is not an emergency. Offer the update and let the user take
  it when they are not mid-task, which is what every well-behaved desktop tool does.

**Home: Sprint 28e.** Same reasoning as findings 13 and 14: Sprint 29 launches on
v4.0, and a launch is precisely when the largest number of machines take the
update simultaneously.
