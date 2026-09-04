# fork-leader-followers — a verbatim slice, not a fixture

Copied byte-for-byte from `leader-followers/src/main/java` of
https://github.com/iluwatar/java-design-patterns at pin `22a34127d0b08449c24cf7e230c04a097deca2f3`.
MIT licensed (Copyright © 2014-2022 Ilkka Seppälä); the headers are retained.

## Why this module, and why not the first one tried

Sprint 28d-rescue row 36 (Remove Middle Man) owes a demonstration on code we did not
author. `find_quality_issue kind=middle_man` over the whole fork reports 28 candidates.

`GiantController` in `model-view-controller` was the first choice — it delegates 7 of 7 —
and it was REJECTED for a measured reason: its delegate `GiantModel` is a Lombok class, so
the accessors the rewrite must call are generated at compile time and JDT never sees them.
The rewrite is correct and the compile gate refuses it, which is the gate behaving exactly
as designed. That is a fact about Lombok, not about row 36, and a demonstration whose only
outcome is an environmental refusal proves less than one that completes.

`TaskSet` delegates 3 of 3 to a `BlockingQueue` from the JDK. Every method the rewrite
must reach resolves, so the operation can be shown ending where it should.

## What this slice changed about the operation

`this.giant.setHealth(h)` and `giant.getHealth()` are the same receiver in two spellings,
and the first version of the tool recognised only the bare one — found on the module above
before it was set aside, and fixed. `TaskSet` uses the bare spelling throughout, which is
exactly why one module could not have surfaced it.

Nothing in this directory is edited. If it stops carrying upstream's licence header, the
slice has stopped being evidence about anybody's code but our own — the test asserts that.
