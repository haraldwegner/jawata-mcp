# fork-leader-election — a verbatim slice of upstream, and it is what added a precondition

- **Pinned commit:** `22a34127d0b08449c24cf7e230c04a097deca2f3`
- **Path in the fork:** `leader-election/src/main/java/com/iluwatar/leaderelection/`
- **Files:** all twelve of that package tree, copied unmodified; `diff -r` against the pinned tree
  reports no difference, and every one carries upstream's own MIT header.

Sprint 28d-rescue, Stage 4, row 35 (Remove Flag Argument).

## The census, and it produced a MISSING PRECONDITION rather than a demonstration

Row 35 fires on a method with a boolean parameter its callers pass as a literal. Over the fork's
**1354 main sources**, exactly **14 methods declare a boolean parameter** and **7 are called with a
boolean literal at least once**.

**Every one of the seven passes ONE literal value and only one.** Three are setters
(`AbstractInstance.setAlive` ×2 `false`, `MaintenanceLock.setLock` `false`, `Queen.setFlirtiness`
`true`), two are constructors this row does not reach, one is `Future.cancel(boolean)` whose
signature the JDK fixes, and one is a guard helper (`RegisterWorker.fail`) whose other four callers
pass expressions.

**So the row would have generated DEAD CODE on every candidate the corpus offers** — a second named
method with no caller — and it had no rule against that. The refusal `FLAG_IS_ONE_SIDED` exists
because of this census. Where every caller agrees, the parameter is a constant rather than a
choice, and the operation that case wants is removing it: `change_method_signature
kind=change_signature`.

## What this slice pins

`AbstractInstance.setAlive(boolean)` is called from `RingApp` and `BullyApp`, both passing `false`.
Two callers, both literals, both the same — the exact shape the census found, on upstream's own
code rather than on a fixture written to produce it.

The refusal is decided from the CALL SITES, so upstream's unresolved lombok and slf4j imports
cannot be what declined it, and the test asserts the reason CODE rather than a substring.
