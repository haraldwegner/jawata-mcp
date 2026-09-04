# fork-map-reduce-mr — a verbatim slice, not a fixture

| Module | Path in the fork |
|---|---|
| `map-reduce` | `map-reduce/src/main/java` |

Copied byte-for-byte from https://github.com/iluwatar/java-design-patterns at pin `22a34127d0b08449c24cf7e230c04a097deca2f3`.
MIT licensed (Copyright © 2014-2022 Ilkka Seppälä); the headers are retained.

The directory is NOT named for the module — `fork-map-reduce` already exists here from an
earlier sprint and holds a different module. That is exactly why this file's table is the
answer and the directory name is not.

## Why this module carries FOUR rows

`MapReduce.mapReduce` is nine lines that do three things in sequence: map every input,
shuffle the results, reduce them. Lombok-free, so every member JDT must see is visible.

- **row 64 Split Phase** — the seam between mapping and shuffling is real, and `mapped` is
  the only local that crosses it. A tool that carried every phase-one local would put more
  in the record than belongs there, and this method shows the difference.
- **row 58 Replace Temp with Query** — `grouped` holds one value and is read once.
- **row 24 Move Function** — `Shuffler.shuffleAndSort` is static with a real caller.
- **row 48 Replace Function with Command** — `Mapper.map` is static with one parameter.

`MapReduce` itself is a utility class whose private constructor THROWS, and which two
classes reference. Both are cases row 17 must refuse, on code nobody wrote for us.

Nothing here is edited. If it stops carrying upstream's licence header, it has stopped
being evidence about anybody's code but our own — the tests assert that.
