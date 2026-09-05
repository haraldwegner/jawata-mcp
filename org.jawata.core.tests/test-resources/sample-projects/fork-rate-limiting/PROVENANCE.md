# This code is not ours

Every source file under `src/main/java` is a **byte-identical copy** from the pinned
upstream fork. Nothing here was written, shaped, simplified or annotated by us.

| | |
|---|---|
| Source | `iluwatar/java-design-patterns` (fork: `haraldwegner`) |
| Pinned commit | `22a34127d0b08449c24cf7e230c04a097deca2f3` |
| Module | `rate-limiting-pattern` |
| Path in the fork | `rate-limiting-pattern/src/main/java/com/iluwatar/rate/limiting/pattern/` |
| Licence | MIT (the repository's) — **these files carry no per-file header, because upstream's do not** |
| Copied | 2026-09-03, Sprint 28d-rescue Stage 3 |

**The module is `rate-limiting-pattern`, not `rate-limiting`.** The fixture name drops the
suffix and the fork's does not, so anyone re-pinning by matching the fixture name against
the fork will find nothing. That mismatch is why this file matters: it was written after a
review looked for the source directory, failed, and reported all ten files as missing from
upstream.

## Why THIS module

Row 62 (Slide Statements) needed a demonstration on code we did not author, and this is
where the shape occurs. `AdaptiveRateLimiter.check` declares `key`, computes an unrelated
`current`, then finally uses `key` — the declaration and its use separated by a statement
belonging to neither.

Chosen by measurement rather than browsing: every Lombok-free module in the fork was
aggregated into one project and every Stage 3 kind run against it. This is the module
where `slide_declaration` fired.

## The dependency, which the JDK-only fixtures do not have

This slice logs, so it needs slf4j on the classpath. Every substantial Lombok-free module
in the fork logs; refusing the dependency would have left only modules too small to
contain any of Stage 3's shapes. The version tracks jawata's own `v.slf4j`, so the jar
already resolves.

## If you are updating the fork pin

Re-copy these files from the new commit and update the table above. Do **not** edit them
in place — a slice that has drifted from its source is no longer evidence about anybody's
code but our own.
