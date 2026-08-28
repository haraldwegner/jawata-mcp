# This code is not ours

Every `.java` file under `src/main/java` is a **byte-identical copy** from the pinned
upstream fork. Nothing here was written, shaped, simplified or annotated by us, and
that is the entire point of the fixture.

| | |
|---|---|
| Source | `iluwatar/java-design-patterns` (fork: `haraldwegner`) |
| Pinned commit | `22a34127d0b08449c24cf7e230c04a097deca2f3` |
| Path in the fork | `circuit-breaker/src/main/java/com/iluwatar/circuitbreaker/` |
| Licence | MIT — the per-file headers are retained verbatim, as it requires |
| Copied | 2026-08-29, Sprint 28d Stage 7 (S7.4) |

## Why it exists

Sprint 28d's C7 exit criterion says an operation must be demonstrated **on code we
did not author**, and rules out the easy substitute in terms:

> each op's before-case is pre-existing code (a fork slice or equivalent), named per
> op — *a fixture written for the test does not satisfy the clause*

The reason is worth stating, because it is not pedantry. A fixture written to
exercise a refactoring is written, consciously or not, in the shape that refactoring
handles. It cannot fail in the ways real code fails: mixed field visibility,
package-private access from a sibling class, state whose grouping was decided by
someone solving a different problem. This slice was written to demonstrate the
Circuit Breaker pattern, by someone who had never heard of our Extract Class
operation, and it is used unmodified.

## What is deliberately absent

`App.java` — the pattern's demo entry point, and the only file in that directory
using Lombok. JDT does not run Lombok's annotation processor in this workspace (the
`simple-maven` fixture records the same finding in its own comment), so a file
referencing Lombok-generated members would fail to compile for a reason with nothing
to do with the refactoring under test. Omitting it keeps a compile error meaningful.

The eight files kept are self-contained: they reference only each other and the JDK.

## The Extract Class target

`DefaultCircuitBreaker` carries a real field cluster that travels together —
`failureCount`, `lastFailureTime`, `lastFailureResponse`. `recordFailure` mutates all
three; `recordSuccess` resets two. Their visibility is mixed (two package-private,
one private), which is exactly the sort of thing a hand-written fixture would have
tidied away.

Sprint 28d's own operation survey reached the same place independently: it recorded
circuit-breaker's wrapping step as an Extract Class path, while noting that the
CLOSED→OPEN→HALF_OPEN machine itself is authored behaviour no refactoring derives.
The mechanical half is what this fixture exercises.

## If you are updating the fork pin

Re-copy these files from the new commit and update the table above. Do **not** edit
them in place — a slice that has drifted from its source is no longer evidence about
anybody's code but our own.
