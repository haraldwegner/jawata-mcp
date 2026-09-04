# fork-intercepting-filter — a verbatim slice, not a fixture

| Module | Path in the fork |
|---|---|
| `intercepting-filter` | `intercepting-filter/src/main/java` |

Copied byte-for-byte from https://github.com/iluwatar/java-design-patterns at pin
`22a34127d0b08449c24cf7e230c04a097deca2f3`. MIT licensed (Copyright © 2014-2022
Ilkka Seppälä); the headers are retained.

## Why this module

Row 17 (Inline Class) needs a class that ONE other class uses and holds in ONE field —
and finding one is the whole difficulty, because a class worth writing usually has more
than one client. `find_references` on `FilterChain` over the fork as a whole returns
**three references, all inside `FilterManager.java`**, and `FilterManager` holds it as
`private final FilterChain filterChain` and forwards both of its methods to it.

Nothing about that arrangement was arranged for us. It is upstream's Intercepting Filter
demonstration, where the manager is the pattern's public face and the chain is the
mechanism behind it.

## Which files, and which are missing

Ten of the module's thirteen main files. The three omitted are `Client`, `Target` and
`App`. `Client` and `Target` extend `JFrame` (and `TargetListener` is nested inside
`Target`), so they need Swing, which is not on this test project's classpath; `App`
constructs both of them and cannot compile without them. None of these rows touches any of
the three. Their absence changes
nothing about `FilterChain`'s reference count, which was measured over the fork in place
rather than over this slice.

Nothing is edited. If a file stops carrying upstream's licence header it has stopped being
evidence about anybody's code but our own — the test asserts that before anything else.
