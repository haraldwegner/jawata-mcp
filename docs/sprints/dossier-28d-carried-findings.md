# Sprint 28d — findings carried out of C7 into Stage 8

Two items surfaced at the Stage 7 checkpoint that are **not C7 deliverables** and were
deliberately not fixed there. They are recorded here because the C7 auditor flagged the
first one as *a deferral with no named home* — it existed only in a message and in a
plan file outside this repository, which is not somewhere the next executor will look.

Neither blocks C7. Both are Stage 8 work.

---

## 1. The published-schema defect is LIVE in `generate`

**Status: unfixed, reproducible today, ships in the released dist.**

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

**Status: a disclosed coverage boundary, not a defect.**

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

## Provenance

Both found at the Sprint 28d C7 checkpoint (Stage 7, Extract Class). Item 1 was found by
sweeping for the *cause* of a defect the architect seat raised, after fixing its instance;
item 2 was raised by the C7 fresh-context auditor. Neither was fixed in C7, because a
checkpoint is not the place to widen scope.
