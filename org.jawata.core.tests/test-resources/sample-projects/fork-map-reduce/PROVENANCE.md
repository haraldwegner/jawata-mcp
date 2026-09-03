# This code is not ours

Every `.java` file under `src/main/java` is a **byte-identical copy** from the pinned
upstream fork. Nothing here was written, shaped, simplified or annotated by us, and
that is the entire point of the fixture.

| | |
|---|---|
| Source | `iluwatar/java-design-patterns` (fork: `haraldwegner`) |
| Pinned commit | `22a34127d0b08449c24cf7e230c04a097deca2f3` |
| Path in the fork | `map-reduce/src/main/java/com/iluwatar/` |
| Licence | MIT — the per-file headers are retained verbatim, as it requires |
| Copied | 2026-09-03, Sprint 28d-rescue Stage 3 |

## Why THIS module

Stage 3's rows are in-method rewrites — loops, conditionals, control flags,
statement order. Demonstrating them needs foreign code that actually computes
something, and most of the fork's modules are small pattern demonstrations whose
methods are one or two statements long.

This one is an implementation of map/reduce: four classes that iterate, accumulate,
group and sort. It was written to explain the map-reduce pattern, by someone who had
never heard of Split Loop or Replace Loop with Pipeline, and it is used unmodified.

It was chosen by MEASUREMENT rather than by browsing. jawata's own
`find_modernization(kind=loop_to_stream)` over the whole fork named four candidates
in this module, more than any other single module in the corpus.

## What is deliberately absent

`Main.java` — the demo entry point. It is not needed to exercise any row and keeping
the slice to the computational classes keeps it small.

The four files kept are self-contained: they reference only each other and the JDK
(`java.util`, `java.util.logging`).

## If you are updating the fork pin

Re-copy these files from the new commit and update the table above. Do **not** edit
them in place — a slice that has drifted from its source is no longer evidence about
anybody's code but our own.
