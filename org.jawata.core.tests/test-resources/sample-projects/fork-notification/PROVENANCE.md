# fork-notification — a verbatim slice, not a fixture

| Module | Path in the fork |
|---|---|
| `notification` | `notification/src/main/java` |

Copied byte-for-byte from https://github.com/iluwatar/java-design-patterns at pin
`22a34127d0b08449c24cf7e230c04a097deca2f3`. MIT licensed (Copyright © 2014-2022
Ilkka Seppälä); the headers are retained. All nine main files, unmodified.

## Why this module

Row 38 (Remove Subclass) needs a CONCRETE class extending a CONCRETE class and overriding
nothing — the case where the subclass carries no distinction and folding it away is a pure
simplification. In a corpus of design-pattern demonstrations that shape is rare, because
subclasses there mostly exist in order to override.

`find_quality_issue(kind=composition_over_inheritance)` over all 1336 files returns NINE
findings; SEVEN name a subclass that overrides NONE of its inherited members. (The other
two, `FlamingAsteroid` and `SpaceStationIss`, are flagged on the touch-ratio clause instead
and were never candidates.) Of those seven, SIX are unusable for this row and each for its
own reason, which is worth writing down because it is the measurement, not a guess:

| candidate | why not |
|---|---|
| `CustomerRole extends CustomerCore` | `CustomerRole` is itself abstract and has subtypes — that is Collapse Hierarchy |
| `SimpleProbableThreat extends SimpleThreat` | it does override (`probability`, `toString`); the detector counts only INHERITED members |
| `UserConverter extends Converter<UserDto, User>` | generic parent, and the constructor passes method references rather than forwarding its own parameters |
| the three `Mma*Fighter extends MmaFighter<Self>` | a self-referential generic parent (CRTP) |

`RegisterWorker extends ServerCommand` is the one that fits: concrete parent, no subtypes,
no overrides, and a constructor that forwards its single parameter through unchanged.

## Lombok

`ServerCommand` is annotated `@AllArgsConstructor` and `RegisterWorker` `@Slf4j`. JDT does
not run Lombok's annotation processor, so those generated members are invisible to it —
which is exactly why this slice is worth having. The row runs against a parent whose
constructor the compiler cannot see, and the apply gate compares errors before and after on
the files a change modifies, so a pre-existing unresolved reference does not mask a new one.

Nothing is edited. If a file stops carrying upstream's licence header it has stopped being
evidence about anybody's code but our own — the test asserts that before anything else.
