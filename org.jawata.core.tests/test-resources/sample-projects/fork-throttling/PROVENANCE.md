# fork-throttling — a verbatim slice of upstream, and row 46's SUCCESS demonstration

- **Pinned commit:** `22a34127d0b08449c24cf7e230c04a097deca2f3`
- **Path in the fork:** `throttling/src/main/java/com/iluwatar/throttling/`
- **Files:** all six of that package tree, copied unmodified; `diff -r` against the pinned tree
  reports no difference, and every one carries upstream's own MIT header.

Sprint 28d-rescue, Stage 4, row 46 (Replace Error Code with Exception).

## The census, and why this is the corpus's ONE genuine error code

Row 46 fires on a value returned to mean failure. Over the fork's **1354 main sources** the
sentinel-shaped returns are **43 `return null`**, **38 `return false`** and **3 `return -1`**.
Almost every one is ordinary control flow — which is exactly why the row REQUIRES the caller to
name the error value rather than inferring it.

Reading the three `return -1`: two are `CountrySchemaSql`'s catch blocks, where the sentinel is
the tail of a swallowed `SQLException` and the refactoring would be undoing a decision rather than
making one. The third is `Bartender.orderDrink`, and it is Fowler's example almost word for word:

```java
public int orderDrink(BarCustomer barCustomer) {
  ...
  if (count >= barCustomer.getAllowedCallsPerSecond()) {
    LOGGER.error("I'm sorry {}, you've had enough for today!");
    return -1;                      // the failure
  }
  ...
  return getRandomCustomerId();     // the answer
}
```

**Its one caller DISCARDS the value** — `App.java:79` reads `service.orderDrink(barCustomer);` as
a statement. The throttling decision is thrown away at the only place that could act on it. That
is the bug this refactoring exists for, written by somebody else.

## What this slice pins, and the honest caveat about which exception

The row PERFORMS here, which makes this the stage's rarer kind of fork clause — most of its slices
pin a refusal. The test uses an UNCHECKED exception, and that is a deliberate limitation rather
than a convenience: with a CHECKED one, upstream's `App` would not compile, and this row's own
compile gate would refuse the whole change.

**That is the row's own warning demonstrated rather than asserted.** A checked exception is what
makes the compiler demand each caller answer for the failure; an unchecked one lets upstream's
`App` go on compiling while the failure now propagates at run time. The test pins both the change
AND the response's statement that behaviour changed, because on this input the second is the only
thing telling a reader what they just did.

Upstream's lombok and slf4j imports do not resolve here. That is accepted precedent, and for this
slice it is stated rather than waved past: the row REWRITES this file, so the compile gate does run
— it compares errors before and after, and unresolved imports are equally unresolved on both
sides.
