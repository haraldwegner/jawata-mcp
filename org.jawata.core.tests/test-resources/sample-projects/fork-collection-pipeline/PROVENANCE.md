# fork-collection-pipeline — a verbatim slice, not a fixture

| Module | Path in the fork |
|---|---|
| `collection-pipeline` | `collection-pipeline/src/main/java` |

Copied byte-for-byte from https://github.com/iluwatar/java-design-patterns at pin
`22a34127d0b08449c24cf7e230c04a097deca2f3`. MIT licensed (Copyright © 2014-2022
Ilkka Seppälä); the headers are retained. All seven main files, unmodified.

## Why this module

Row 5 (Combine Functions into Class) needs several `static` functions on one class that
share their first parameter's type. `FunctionalProgramming` has exactly three, and the
distribution is what makes it worth more than a fixture:

| function | first parameter |
|---|---|
| `getModelsAfter2000` | `List<Car>` |
| `getGroupingOfCarsByCategory` | `List<Car>` |
| `getSedanCarsOwnedSortedByDate` | `List<Person>` |

Two share a type and one does not. A fixture written beside this row would have had every
function agree — that is the shape you write when you are demonstrating that something
works — and it could not have shown the operation GROUPING rather than sweeping the class.
Upstream's third function is the control, and nobody put it there for us.

Nothing is edited. If a file stops carrying upstream's licence header it has stopped being
evidence about anybody's code but our own — the test asserts that before anything else.
