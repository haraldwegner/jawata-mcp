# fork-lockable-object — a verbatim slice of somebody else's module

**Upstream:** `java-design-patterns`, module `lockable-object`, pinned at commit
`22a34127d`. Copied from `lockable-object/src/main/java/com/iluwatar/lockableobject`
with no edit of any kind; `diff -r` against the pinned tree reports no difference.

**Licence:** MIT. Every file carries upstream's own MIT header, verbatim — verified by
`ForkSliceSupport.load`, whose provenance check IS that header. (The `fork-rate-limiting`
slice cannot use that check because upstream's files there carry no header; these do.)

**Why this module.** Sprint 28d-rescue row 53 (Replace Parameter with Query) needs a
method whose callers all derive one argument the same way from another argument of the
same call. Over the fork's 1354 main sources there are 41 such call sites and 39 of them
are `map.put(x.getId(), x)` — a JDK method with no source to change. The two that are
not are both calls to `Feind.fightForTheSword(reacher, sword.getLocker(), sword)`, one of
them the method's own recursive call, and they agree on the query and on which argument
is its receiver. It is the corpus's only instance of the shape.

**What it pins: a REFUSAL, and the refusal is a product finding rather than a limitation.**
`holder` is read five times, four of them inside a `while` loop whose body attacks the
holder and can release the sword. Substituting `sword.getLocker()` for the parameter turns
one evaluation at the call site into one per read, so the reads can answer differently and
the method computes something upstream never asked for. It compiles, so no gate below the
row could catch it. This is Fowler's own precondition — the query must not depend on state
the method modifies — and the row did not have it until this slice was read.

**Lombok and slf4j do not resolve here**, because the slice declares no dependencies. That
does not weaken what it pins: the row refuses before building a change, so nothing in this
slice reaches the compile gate, and the refusal is decided from the AST and the parameter's
own binding, both of which resolve.
