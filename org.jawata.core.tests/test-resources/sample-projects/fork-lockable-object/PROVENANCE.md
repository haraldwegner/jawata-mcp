# fork-lockable-object — a verbatim slice of upstream, for row 53's one candidate

- **Pinned commit:** `22a34127d0b08449c24cf7e230c04a097deca2f3`
- **Path in the fork:** `lockable-object/src/main/java/com/iluwatar/lockableobject/`
- **Files:** all eleven of that package tree, copied unmodified — `diff -r` against the pinned
  tree reports no difference, and every one carries upstream's own MIT header, so this slice
  uses `ForkSliceSupport.load`, whose provenance check IS that header.

Sprint 28d-rescue, Stage 4, row 53 (Replace Parameter with Query).

## Why this module — it is the corpus's ONLY candidate, and that is a census

Row 53 fires where a method's callers all pass `x.something()` for one parameter and the bare
`x` for another. Over the fork's **1354 main sources** there are **2683 multi-argument call
sites**, and **41** have that shape. **Thirty-nine are `map.put(thing.getId(), thing)` or
`new SomeException(e.getMessage(), e)`** — a JDK or JDK-shaped method with no source to change.

The two that remain are both calls to `Feind.fightForTheSword(reacher, sword.getLocker(),
sword)`, one of them the method's own recursive call. They agree on the query and on which
argument is its receiver, so unanimity — this row's headline safety argument — holds.

## What it pins: a REFUSAL, and the refusal is a product finding

Every condition the row had when it was written passed on upstream's method, so it would have
performed the change. It must not. `holder` is read **five times, four of them inside a `while`
loop** whose body attacks the holder and can release the sword, so substituting
`sword.getLocker()` turns one evaluation at the call site into one per read and the reads can
answer differently.

**Measured, not argued:** with the precondition disabled the row SUCCEEDS here and rewrites
upstream's file — the compile gate passes, because the result is valid Java. That is why no gate
below the row could have caught it. Fowler states the same caveat: not when the query depends on
state the function modifies. The precondition exists because this slice was read.

The row's own fixtures could not have found it. Each reads its parameter once, in a straight
line, in a method that mutates nothing — the shape a fixture takes when its author also wrote
the rule.

## Known unresolved references, kept deliberately

Upstream's sources import `lombok` and `org.slf4j`; the slice declares no dependencies on
purpose, so those do not resolve — the same condition `fork-step-builder` and `fork-notification`
record, and for the same reason: this is upstream's real code and JDT does not run Lombok's
processor.

It does not weaken what this slice pins, and the reason is measured rather than assumed: **the
row refuses before building any change**, so nothing here ever reaches the compile gate, and the
refusal is decided from the AST and the parameter's own binding, both of which resolve. The test
asserts the REASON CODE rather than a substring, so a refusal arriving from an unresolved import
would fail it rather than pass for the wrong reason.

## Separate project, deliberately

Not merged into `simple-maven`. That fixture feeds roughly forty smell detectors whose counts are
asserted elsewhere, and foreign code dropped into it shifts those baselines for reasons unrelated
to what they measure — proven three times in this sprint, on clone groups, on a naming
population, and on a findings page.
