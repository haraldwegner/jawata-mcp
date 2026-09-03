# This code is not ours

Every `.java` file under `src/main/java` is a **byte-identical copy** from the pinned
upstream fork. Nothing here was written, shaped, simplified or annotated by us.

| | |
|---|---|
| Source | `iluwatar/java-design-patterns` (fork: `haraldwegner`) |
| Pinned commit | `22a34127d0b08449c24cf7e230c04a097deca2f3` |
| Path in the fork | `serialized-lob/src/main/java/com/iluwatar/slob/` |
| Licence | MIT — the per-file headers are retained verbatim, as it requires |
| Copied | 2026-09-03, Sprint 28d-rescue Stage 3 |

## Why THIS module, and it was the only answer

Row 60's shape — a local that exists only to carry the return value, assigned on every
branch and handed back at the end — was searched for across the WHOLE fork, not a
sample of it. Every Lombok-free module was aggregated and probed (116 files, main and
test), then every Lombok-using module (1210 files). The shape occurs in exactly one
place: `App.createLobSerializer` here.

`App.java` is itself free of Lombok. Four of its siblings are not, and JDT does not run
Lombok's annotation processor in this workspace, so those four carry unresolved
references to generated accessors. The operation still applies and the pipeline reports
the change compile-verified.

## If you are updating the fork pin

Re-copy these files from the new commit and update the table above. Do **not** edit
them in place — a slice that has drifted from its source is no longer evidence about
anybody's code but our own.
