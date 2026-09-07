# The degradation stamp — Sprint 28e, Stage 2a Deliverable 2

**Status: published for Stages 4, 6 and 9 to build against.** This is the RULE half of
mcp#12. The list of which existing shapes carry it is Stage 2b's; this document says what
carrying it means, so the three lanes that publish new response shapes do not each invent
an answer.

## The question every response shape must answer

> Can a caller tell a degraded answer from a complete one, without already knowing which
> it is?

"Degraded" is not "failed". A failure is loud and needs no stamp. The dangerous answer is
the one that looks ordinary.

## Four states, and the fourth is the one we keep getting wrong

Every answer is exactly one of these, and the response must say which.

| state | means | what the response carries |
|---|---|---|
| **COMPLETE** | the whole population was examined; an absence is real | the population size actually examined |
| **BOUNDED** | a page or a sample; more exists | the bound, the true total, and how to ask for the rest |
| **DEGRADED** | something could not be examined | WHAT could not be, and why |
| **NOT HANDLED** | the input was outside what this operation acts on | which part of the input, and what does act on it |

**COMPLETE and NOT HANDLED produce identical-looking output and mean opposite things.**
That is the defect behind jawata-mcp#76: `apply_cleanup kind=remove_dead_code` accepts an
address, changes nothing, and reports

> "No code to clean up — and the scan was COMPLETE (1 file(s) examined), so this is a real
> absence, not a failure to look."

for a member it does not act on at all. The sentence exists to separate COMPLETE from
DEGRADED — a good distinction — and it is applied to a third case that is neither. It is
worse than a refusal, because a refusal tells the caller something was declined while this
actively rules out the true explanation.

## The rule, stated so a new shape can be built against it

1. **A count is never bare.** Any number describing a population states what was examined
   to produce it. `find_string_literals` does this correctly: *"0 matches over 1071 files
   examined, scan COMPLETE"* — the zero is trustworthy because the denominator is present.
2. **A truncated answer says so in the payload, not only in prose.** `returnedCount`
   beside `totalCount`, plus the cursor or offset to continue. A consumer reading only the
   array must be unable to mistake a page for the whole.
3. **An absence claim is a claim.** "Nothing found" is only sayable in the COMPLETE state.
   In BOUNDED or DEGRADED it is "nothing found in what was examined", and the difference
   is load-bearing.
4. **NOT HANDLED never borrows COMPLETE's wording.** If the operation did not act because
   the input is outside its scope, it names the input and points at what does act. Silence
   plus a success flag is forbidden here.
5. **The stamp is on the response, not in the log.** A caller sees the response.

## What this does NOT require

A shape that cannot degrade needs no stamp, and adding one is noise. `health_check` either
answers or the transport failed. Stage 2b marks each existing shape as *carries the stamp*
or *cannot degrade*, and the second is a legitimate and common answer.

## Where each lane meets it

- **Stage 4 (cure machinery)** — a cure that renders but whose door cannot act on the
  address is NOT HANDLED, not COMPLETE. jawata-mcp#76 and #77 are both this.
- **Stage 6 (tool surface, cross-workspace peek)** — the peek answer states how many
  siblings were consulted, which is rule 1: the denominator makes "not here" trustworthy.
  A dead sibling skipped is DEGRADED and says so.
- **Stage 9 (studio hooks)** — a hook that fired over partial input reports the partiality;
  the store's recall answering from an empty or unavailable index is DEGRADED, which is
  jawata-mcp#73 and #74.

## Two measurements from this session, kept because they show the failure mode

- **A confident false COMPLETE.** `remove_dead_code` on a field the compiler itself flags
  unused: `hasChanges: false` with the real-absence sentence. The address was accepted, so
  the caller has no signal at all.
- **A silent BOUNDED.** `find_references(kind=implementations)` on the tool base answers
  **206** — 103 types listed twice, once at their source path and once under a workspace
  cache path. Nothing in the response says the set is doubled, and 206 is plausible enough
  that nothing invites a second look. This one is not even in the four states: it is a
  wrong COMPLETE.
