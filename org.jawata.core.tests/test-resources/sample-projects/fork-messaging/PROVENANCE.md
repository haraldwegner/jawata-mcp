# fork-messaging — a verbatim slice, not a fixture

| Module | Path in the fork |
|---|---|
| `microservices-messaging` | `microservices-messaging/src/main/java` |

Copied byte-for-byte from https://github.com/iluwatar/java-design-patterns at pin `22a34127d0b08449c24cf7e230c04a097deca2f3`.
MIT licensed (Copyright © 2014-2022 Ilkka Seppälä); the headers are retained.

## Why this module

Row 49 (Replace Inline Code with Function Call) needs duplicated statements in ONE type —
JDT's occurrence matcher works within the type it extracts from. `find_duplicate_code` over
the whole fork reports 258 clone groups; `InventoryService.updateInventory` and
`restoreInventory` are 71 normalized tokens each, byte-identical in shape, in the same
class, in MAIN sources. Nothing was written for us: they are two inventory operations that
happen to log, sleep and log again.

One of this module's eight main files imports Lombok. It is not touched by these tests, and
the compile gate compares errors only on the files a change MODIFIES, so it cannot mask a
defect here.

Nothing is edited. If it stops carrying upstream's licence header, it has stopped being
evidence about anybody's code but our own — the test asserts that.
