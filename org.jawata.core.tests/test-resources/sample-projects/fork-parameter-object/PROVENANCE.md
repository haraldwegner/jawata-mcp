# fork-parameter-object — a verbatim slice of upstream, for row 28's measured absence

- **Pinned commit:** `22a34127d0b08449c24cf7e230c04a097deca2f3`
- **Path in the fork:** `parameter-object/src/main/java/com/iluwatar/parameter/object/`
- **Files:** `App.java`, `ParameterObject.java`, `SearchService.java`, `SortOrder.java` — copied
  unmodified; `diff -r` against the pinned tree reports no difference, and all four carry
  upstream's own MIT header.

Sprint 28d-rescue, Stage 4, row 28 (Preserve Whole Object).

## The census: the corpus has NO success candidate, and three separate walls explain it

Row 28 fires where one call passes two or more no-argument accessors on the SAME receiver. Over
the fork's **1354 main sources** there are **2683 multi-argument call sites**, and **39** have that
shape. Every one of the 39 hits one of three walls:

| wall | what it is | examples |
|---|---|---|
| **no source to change** | the called method is a JDK or library one — a logger, `String.format`, `DatagramChannel.send`. The row rewrites the DECLARATION, and there is none to rewrite | the `LOGGER.info("…", x.getA(), x.getB())` shape, which is most of the 39 |
| **a constructor, which this row does not reach** | `new UserDto(user.firstName(), user.lastName(), …)`. The row resolves a call site to a `MethodInvocation`; a `ClassInstanceCreation` is a different node and a different refactoring | `converter`, `layered-architecture`, `application`, `serialized-entity` |
| **Lombok-generated accessors** | JDT does not run Lombok's processor, so `x.getMoney()` does not resolve and the compile gate cannot verify the rewritten body | `table-module`'s `login` (three agreeing callers — otherwise the corpus's best candidate) and `event-sourcing`'s `handleWithdrawal` (one caller, agreeing) |

The third wall is the one worth naming: **two genuine successes exist and both are unreachable for
the same reason row 54 recorded.** A demonstration on accessors the compiler cannot see is not a
demonstration.

## What this slice pins, and why it is a REFUSAL rather than a demonstration

Stage 6 recorded the distinction and it holds: a refusal on foreign code is evidence, not a
demonstration. `SearchService.getQuerySummary(String, String, SortOrder)` has THREE call sites and
only ONE unpacks a `ParameterObject`; the other two are the overloads that supply defaults and pass
plain values. So the callers disagree and the row declines.

**The irony is the point rather than a joke:** this is the module that DEMONSTRATES the Parameter
Object pattern, and it still contains a method whose callers cannot be folded — because two of them
exist precisely so a caller need not build the object. That is the case this row's unanimity rule
is for, written by somebody else.

**The refusal fires from the CALL SITES, before any type resolves**, so upstream's unresolved
Lombok and slf4j imports cannot be what declined it. The test asserts the reason CODE rather than a
substring, so a refusal arriving from anywhere else would fail it.
