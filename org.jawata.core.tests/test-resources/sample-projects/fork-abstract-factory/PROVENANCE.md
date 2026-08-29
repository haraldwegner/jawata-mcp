# Provenance — `fork-abstract-factory`

A **verbatim** slice of the upstream fork, kept so Sprint 28d Stage 8 can show
Replace Constructor with Factory Method working on code we did not write.

## Source

| | |
|---|---|
| Repository | `java-design-patterns` (the pinned fork checkout) |
| Commit | `22a34127d0b08449c24cf7e230c04a097deca2f3` |
| Module | `abstract-factory` |
| Package | `com.iluwatar.abstractfactory` |
| Licence | MIT — headers retained verbatim, as its terms require |

## Why this module

`abstract-factory` is one of the four patterns D1 measured as blocked without a
constructor call-site rewrite, so the before-case is the operation's own target
rather than a convenient stand-in. `ElfKingdomFactory` and `OrcKingdomFactory`
construct their products internally — **real call sites**, written by someone
solving a different problem and with no knowledge of this operation.

## What is copied, and what is NOT

**12 files**, verified byte-identical against the pin with `cmp`:
`Army`, `Castle`, `King`, `KingdomFactory`, `ElfArmy`, `ElfCastle`, `ElfKing`,
`ElfKingdomFactory`, `OrcArmy`, `OrcCastle`, `OrcKing`, `OrcKingdomFactory`.

**Two files omitted, and the reason is the same one that shaped Stage 7's slice:**

- `App.java` — the demo entry point; uses Lombok **and** an SLF4J logger.
- `Kingdom.java` — uses Lombok.

**JDT runs no annotation processor in this workspace**, so a file depending on
Lombok-generated members fails to compile for a reason that has nothing to do with
the refactoring — and a compile error that means something else is worse than no
test at all. The same fact ruled out five candidate types in Stage 7 (`Stew`,
`Star`, `Card`, `MmaFighter`, `Country`) and it ruled out this sprint's first
choice too: the fork's own `factory-method` module looked ideal until measurement
showed `WeaponType` — the enum every weapon and blacksmith references — carries
`lombok.RequiredArgsConstructor`.

Dropping the logger with `App.java` also lets this fixture declare **no
dependencies**, so nothing in it can fail to resolve and be misread as a
refactoring defect.

## What this slice can and cannot demonstrate

**It has real cross-file call sites**, which is what the operation exists to
rewrite.

**Its products declare no explicit constructors.** `ElfArmy`, `ElfCastle`,
`ElfKing` and their Orc counterparts carry only the implicit default constructor,
so the caret must sit on a constructor CALL rather than a declaration. That is not
a shortcoming of the slice — it is what a great deal of real code looks like, and
whether JDT's engine handles it is measured by the test rather than assumed.

## Re-pinning

Re-copy the twelve files from the module above at the recorded commit and re-run
`cmp` against each. The test asserts provenance itself — the upstream licence
header and the pattern's own class comments must still be present — so a fixture
quietly edited into a shape that suits the operation turns the test red rather
than passing on code that is no longer upstream's.
