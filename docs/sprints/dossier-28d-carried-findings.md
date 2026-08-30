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
