# Sprint 28f D9 — closing comments for mcp#67 and mcp#57

**DRAFTS. NOTHING IS POSTED.** Posting to a public tracker is outward-facing and is
Harald's to do or to authorise, so this file is the review screen. Each claim below is
marked **[measured]** where I ran it in this session, or **[from the spec]** where it
rests on the signed sprint doc and I did not verify it independently.

---

## mcp#57 — "Ingested markdown is stamped language=java regardless of content"

> Closing on Sprint 28f D5.
>
> Markdown that declares no language is no longer stamped `java` at insert. Measured
> through the product's own front door against the built 4.3 artifact, on a scratch
> store: `stats` reports `by_language: {"(none)": 189, "java": 1}` — 189 rows carrying
> no language, and the single `java` row is one this probe recorded with a Java symbol
> anchor.
>
> That matters for the reason the issue gives rather than for tidiness: `language` gates
> JDT staleness, so a non-Java entry stamped `java` was eligible to be staled by a
> compiler that could never resolve it. Rows outside `java` are now left alone by the
> staleness sweep.
>
> Proof: `build/28f-deliverables.sh`, row D5, against the dist.

**[measured]** the `by_language` figure and the D5 PASS — `28f-deliverables.sh`, this session.
**[from the spec]** that the staleness sweep leaves non-java rows alone; I did not drive
a sweep to observe it.

---

## mcp#67 — "the cure drift check re-resolves keys, not addresses"

> Closing on Sprint 28f.
>
> Two of the four gaps this issue names are shipped. The remaining two need no
> machinery: the catalogue pin moves only in a commit we review, so a pin move cannot
> arrive unobserved — the case the audit was being asked to catch automatically is one a
> human already sees.
>
> This issue also carried a smaller decision about the manifest fields nothing reads.
> Taken, and implemented: the extraction now drops `type`, `category` and `tags`. The
> artifact is regenerated against the fork at its documented pin `22a34127d0b0` — 187
> rows before and after, the three fields on all 187 before and on none after, every
> other field byte-unchanged.
>
> Verified before removing rather than assumed: no reader for any of the three in
> `CatalogueSeeder`, `CatalogueManifest` or `CatalogueSources`. The catalogue row's
> `reference` type is set in code and was only being echoed into the manifest. What
> survives is the PARSE — `categoryOf` and `tagsOf` still read both spellings, because
> the measurement behind them is expensive and still true (185 of 187 READMEs write
> `category:`, 182 write `tag:`). The finding is kept; its unread output is not.

**[measured]** the field removal, the regeneration, the 187/187 counts, and the
no-reader check — all this session.
**[measured]** that `audit` re-resolves the ADDRESS rather than the key — the issue's own
headline gap. `CureLookup.audit` (`CureLookup.java:398-423`) calls
`addresses.address(operation)` per declared operation and records `address.sourceRef()`,
with a comment naming the issue: *"the ADDRESS, not merely the fact that some row carries
the key. `resolves(operation)` answered the weaker question, so a pin that renamed a path
while keeping the key left every affected cure pointing at a dead address and the sweep
reporting clean."* It also reports MOVES against a baseline — a key present on both sides
whose value differs — which is the one case resolution alone cannot see. This was the
caveat an earlier version of this file carried; it is closed by reading, not by assuming.

**[from the spec]** that two of the four gaps are shipped and that the other two need no
machinery, beyond the address half measured above.

---

## What I did not do, and why

The plan assigns step 4 to me and the release ask to you. A comment on a public tracker
sits between the two: it is not the release, and it is still outward-facing and hard to
retract. So the code half is done and committed, and the posting waits for your word.
