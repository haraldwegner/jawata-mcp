# fork-step-builder — a verbatim slice of upstream, for row 16's measured absence

- **Pinned commit:** `22a34127d0b08449c24cf7e230c04a097deca2f3`
- **Path in the fork:** `step-builder/src/main/java/com/iluwatar/stepbuilder/`
- **Files:** `App.java`, `Character.java`, `CharacterStepBuilder.java` — copied unmodified

Sprint 28d-rescue, Stage 5, row 16 (Hide Delegate).

Both facts above are stated because `ForkFixtureProvenanceTest` requires them, and it refused
this file twice before they were. The first draft described the origin in prose with neither;
the second named the module under a heading of my own invention rather than one of the two
spellings the guard accepts. Its own message records why the directory name is no substitute
— `fork-rate-limiting` comes from `rate-limiting-pattern`, and a review that guessed reported
every file as missing upstream.

## Why this module

Row 16's per-row contract asks for a demonstration on code we did not author. **There is no
such demonstration to give, and that is measured rather than assumed.**

All 84 `find_quality_issue(kind=message_chains)` findings in the fork were read at their own
`file:line` — a census, not a sample. Every one is either a JDK pipeline (41: `stream`,
`map`, `filter`, `collect`, `Optional`, `sorted`, `findFirst`, `flatMap`) or a fluent /
self-returning chain (43: `Product.builder().name(...)`, `HttpRequest.newBuilder().GET()`,
`Health.down().withDetails(...)`, `Saga.create().chapter(...)`,
`StringBuilder.append().append()`).

**Not one is the shape Hide Delegate exists for** — a client reaching through one domain
object to a second, `john.getDepartment().getManager()`. The corpus is a design-patterns
teaching repository; idiomatic pipelines and builders are exactly what it would contain.

## Why THIS slice pins that

The module carries both shapes, so the operation's two governing refusals can be exercised
on foreign code rather than on a fixture written to produce them:

| Chain | Shape | Refusal |
|---|---|---|
| `App.java` — `CharacterStepBuilder.newBuilder().name("Amberjill")` ×3 | fluent builder, the 43 | the intermediate call is STATIC: no receiver to hide a delegate behind |
| `Character.java` — `new StringBuilder().append(...).append(...)` | JDK-typed server, the 41 | `java.lang.StringBuilder` has no source in this workspace to add a forwarder to |

A refusal on foreign code is EVIDENCE, and Stage 6 recorded that it is not a demonstration.
What it buys is that the census cannot drift silently: if upstream grows a chain this row
could actually perform, or if a change to the row starts accepting these shapes, the test
moves.

## Known unresolved reference, kept deliberately

`App.java` imports `lombok.extern.slf4j.Slf4j` and uses the `LOGGER` it generates. The slice
declares no dependencies on purpose, so that reference does not resolve — the same condition
`fork-notification` records and for the same reason: it is upstream's real code, and JDT does
not run Lombok's processor. It does not affect what this slice pins, because both cases
REFUSE before any change is prepared.

## Separate project, deliberately

Not merged into `simple-maven`. That fixture feeds roughly forty smell detectors whose counts
are asserted elsewhere, and foreign code dropped into it shifts those baselines for reasons
unrelated to what they measure. Proven twice this sprint: a seven-token helper moved four
clone-detection tests in Stage 6, and in Stage 5 a single new PACKAGE took the project over
the minimum sample `AnalyzeNamingToolTest` relies on and turned it red.
