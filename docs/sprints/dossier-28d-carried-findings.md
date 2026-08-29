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

**Status: a declared deviation from D3's wording, and the more valuable half of the
check is the half that is missing. HOME: Sprint 28e**, the issues sprint — to be
filed there as an issue against the creational operations, not carried as prose.

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
