# The story template

What may enter the experience store, and in what shape.

This file is the human-readable half. The machine-readable half is
`org.jawata.mcp.knowledge.StoryTemplate`, and a test asserts that every field and
every refusal named in the code appears here — so the rule you read cannot drift
from the rule the gate applies.

## The criterion

> An entry earns storage only if a future stranger, standing in a situation they
> can recognise, would act differently for having read it.

Everything below is that one sentence made checkable. If a candidate entry fails
it, no amount of correct formatting rescues it.

## The fields

### situation — when does this apply?

The condition under which the story is true. This is the field applicability is
declared in, and the field an anchorless question is matched against hardest, so
it carries most of the weight in retrieval.

- **Self-contained.** A reader with no session context must be able to tell
  whether they are IN it. Every referent named: not "the number", not
  "leadership", not "the fix".
- **Concrete.** The observable condition, not the category it belongs to.
- **Phrased as a condition**, beginning *when …*.
- **Not an address.** A path, a symbol or a flag is an anchor, not a situation.

The sentence that fails this, and the reason it is the worked example:

> *when a number invented to diagnose a problem becomes the figure leadership
> reviews every week*

What number? Whose leadership? Which problem? Three questions a stranger cannot
answer — so they cannot tell whether the story is theirs. It reads well, which is
exactly why a shape check passes it and only a reader catches it.

**Prefer the question the reader is actually facing.** *"You already have a
partial fill and want to change the limit price — what do you put in the quantity
field?"* is recognisable. *"When amending an order that is already partially
filled"* describes circumstances and leaves the reader to work out whether their
problem is the one being answered.

**No local vocabulary.** A word that means something only inside one project
makes the situation unreadable to everyone else, and the author is the last
person who can see it — it reads perfectly to them. If a term would need the
project explained first, it does not belong in the situation. *"whether the slot
is free"* fails: a reader who does not know the portfolio is divided into ten
slots cannot tell whether the story is theirs.

### summary — what happened, or what to do?

A claim, not a topic. *"Test plan"* names a subject; *"a v9 store climbs every
remaining rung in one call"* claims something a reader can act on or dispute. A
heading is not a claim, whatever it is labelled.

**And it must be at the RIGHT WIDTH — the test the rest of this file did not
ask.** A story can be wrong about scope in two directions at once, and usually
is:

- **Too narrow.** The claim is written about the case you happened to hit, when
  it holds for a whole family. *"Wait for the cancel to be confirmed"* was one
  instance of *"know what state an order is in before you send anything else
  about it"* — which covers new orders and amends equally.
- **Too broad.** A vendor's behaviour is stated as though it were how the world
  works. *"Every broker skips a message until the previous one is performed"* is
  one broker's sequencing; other venues permit patterns it does not.

So: **name the widest form that is actually true, and label the vendor-specific
mechanism as vendor-specific inside it.** The rule of thumb transfers; the
mechanism does not, and a reader who cannot tell them apart will carry the wrong
half to the next system.

### details — why, and what would a reader do differently?

The mechanism, the evidence, the cost. **Artifacts live here** — paths, ids,
flags, commands, versions — never in the situation, which has to stay readable by
someone who was not there.

### outcome — experiences only

`worked` · `failed_avoid` · `unproven` (genuinely still open).

A `domain_fact`, an `api_contract`, a `naming_convention` or a `reference` owes
**none**. It never turned out any way at all, and inventing an outcome for one
makes retrieval rank on fiction.

### anchor — optional

A symbol or a package, when the story has one. Its absence is normal: experience
is experience without any code.

## What never enters

Each row is a refusal the gate actually emits, named by the word it reports.

| Refused | Reported as | Why |
|---|---|---|
| a log line | `log line` | it records that something happened, not what to do about it |
| a fallback slip | `fallback slip` | its audit and precedent value belongs in the tool lane, the store's separate per-tool table |
| project progress — a sprint phase, a release announcement | `status note` | it was true when written and is false now, and nothing retires it |
| a numbered heading ("4. Testing") | `section heading` | a claim does not begin with its own position in a document |
| a summary too short to be a claim | `not a claim` | fewer than four words names a topic; a claim needs a subject and something said about it |
| a context-compaction artifact | `compaction artifact` | it is a transcript's shadow, meaningless to any later reader |
| nothing at all | `empty` | an entry with no summary claims nothing, so nobody can judge whether it applies to them |
| a fragment of a document | — | **one story = one file = one entry** — no entry is ever minted from a section |

A `#`-prefixed or colon-terminated heading is refused earlier still, by the admission
policy that has always guarded the summary field.

## The cold reader

Every folded story is judged by an agent with **zero session context**, which
answers three questions:

1. **When does this apply?** — restate it. If they cannot, the situation is not
   self-contained. Any word they would need the project explained to understand
   is a failure of the story, not of the reader — and the author is the last
   person who can see it, because it reads perfectly to them.
2. **What would you do differently for having read it?** — if there is no answer,
   it is a comprehensible platitude. Platitudes are what pass every other check,
   which is why this question exists.
3. **Is it the right width?** — name a neighbouring case the claim should also
   cover, and a system where it should NOT hold. If the neighbour is excluded the
   story is too narrow; if the other system is swept in it is too broad. Both
   faults routinely appear in the same entry, and a reader with no context is the
   only one who will notice either.

A duplicate check against the existing stories completes the review. Passing
earns a `reviewed:` stamp in the file's frontmatter.

**The reseed gate admits stamped stories only, and it checks the STAMP, never the
text.** Intelligence sits in front of ingest, never inside it — a gate that tried
to judge meaning would either turn away real knowledge or teach authors to dress
noise up.

## The asymmetry, stated rather than hidden

A direct `record` from any client passes the deterministic shape gate only. Its
quality review is the usage flow: the entry accumulates a score, and a human
reviews and deletes it through the review seat. That path includes the seats'
mandatory outcome records — a standing write channel, not a rare tail — so this
is an accepted asymmetry, taken because the review-and-delete flow covers what a
gate cannot judge.
