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

## What kind of entry is this — decide FIRST

Three kinds enter the store, they are not interchangeable, and getting this wrong
is the failure the first fold made on half its entries. The test is one question:

> **Does violating it produce a CLASS of different problems, or the same one
> problem every time?**

**A PRINCIPLE** — a class. *Do not act on an order instruction until the venue
confirms it* is one rule, and breaking it in different places gives you different
damage: a reused slot with a phantom position, a resend after a timeout that
fills twice, a local book that silently disagrees with the venue's. That variety
IS the evidence it is a principle; write it into the entry, because it is what
justifies storage. A principle firing on a neighbouring situation is a SUCCESS.
Type: `lesson` or `failure_mode`.

**A DURABLE FACT** — one condition, one remedy, and it stays true as long as the
thing does. *On a Tycho project the Maven goal must be `verify`.* There is no
class behind it and manufacturing one produces noise; the condition IS the
boundary. A fact firing outside its condition is a FALSE POSITIVE that costs the
reader the time it was stored to save. Type: `domain_fact`.

**A PERISHABLE FACT** — a durable fact about someone else's defect. *This
WebKitGTK release blanks the webview; set the variable.* It is true now and
becomes FALSE when upstream ships the fix, and nothing in the store retires it.
Record the version it was observed against and what would end it, so a later
reader can check rather than trust. Type: `domain_fact`.

**Do not fold a fact into a principle's shape.** Writing a "transferable shape"
paragraph onto a troubleshooting fact to make it fit the lesson template is
inventing a property so the mismatch disappears — the same move as inventing an
outcome for a fact that never turned out any way at all.

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

**The situation stays in the reader's plain words — the scope lives in the
claim.** Both of these were measured, not reasoned:

- Abstracting the situation's WORDING to widen the scope makes the entry harder
  to find, not easier. *"a bulk substitution across text"* lost to *"renaming
  across documentation"*; *"window chrome"* lost to *"the title bar and the
  buttons"*. Widen the claim; leave the situation in the words someone would
  actually type.
- **Naming a technology in the situation gets the entry REFUSED.** A situation
  mentioning `WebKitGTK` is read as a code location, and the admission gate turns
  it away — correctly, since a location matches everything inside it and
  distinguishes nothing. So the vendor boundary the scope test asks for goes in
  the claim and the details: *"if the app embeds a WebKitGTK webview, set …"*.
  The two rules compose in exactly one direction.

**No local vocabulary.** A word that means something only inside one project
makes the situation unreadable to everyone else, and the author is the last
person who can see it — it reads perfectly to them. If a term would need the
project explained first, it does not belong in the situation. *"whether the slot
is free"* fails: a reader who does not know the portfolio is divided into ten
slots cannot tell whether the story is theirs.

### Say it out loud — and leave the incident's numbers out of it

Two rules, and they pull in opposite directions, which is why both are needed.

**Read the situation aloud. If nobody would say it, rewrite it.** A constructed
sentence reads as competent and matches nothing. Of twenty situations written for
one sample, the only one that worked was the one dictated in speech:

> *"you already have a partial fill and want to change the limit price — what do
> you put in the quantity field?"*

Everything else was assembly — *"when an indicator fires on some fraction of
checks"*, *"when you are reformulating someone's requirement into the version that
will be worked from"*. Nobody has ever said either sentence.

**But the incident's own values must NOT be in it.** They are the tightest possible
filter and they filter on the wrong thing:

| written | fails on |
|---|---|
| *"0.28 splits my data best — can I hard-code it?"* | someone whose number is 0.96, or 1.3 — and someone tuning to optimise a score rather than to split |
| *"it's been running forty minutes — is it stuck?"* | four hours |

Numbers, versions and durations are EVIDENCE. They belong in the body, where they
show the claim is real. In the situation they are a coincidence the reader has to
share.

**And use the domain's actual word.** *"Threshold"* is what people say and search
for; *"a cutoff that splits my data"* is a paraphrase of it that also narrows it.
The rule is the same as keeping the nouns concrete — reach for the term in use,
not a description of the term.

### Keep the domain's own nouns — a placeholder noun matches nothing

The situation is written in the words the reader would type. Substituting a
generic noun for the real one is the same over-abstraction as widening the
wording, and it produces a sentence nobody will ever match:

> *when your cleanup pass drops an in-flight job from the map that tracks it
> because the job's own flag says it has finished*

Every noun there is a placeholder — "cleanup pass", "in-flight job", "the map",
"the flag" — standing in for orders, a broker, and the record we keep of them.
The result reads as competent English and matches either everything or nothing.

**The check:** point at each noun and name the real thing it replaced. If you can,
put the real thing back. A reader searching for this problem is holding orders and
a broker, not jobs and maps.

This composes with the width rule below in one direction only: widen the CLAIM to
the class, keep the SITUATION in the domain's concrete language.

### Name what was actually being built — the body, not only the situation

The placeholder-noun rule above was written about the situation. **It applies to
the body too, and the failure there is worse, because an entry can narrate a whole
incident without ever saying what the thing WAS.**

A draft ran: *"the requirement was that the human sees the outgoing text once,
already reviewed. It was built on a hook that fires after the text has already
streamed."* Every noun is a role — a check, a hook, the human, the text. The read
it got: *"Is this about the communicator or what? This is not concrete at all."*

What it needed was one sentence saying what was under construction: **a gate that
reviews an agent's outgoing messages to the human before the human reads them.**

**Concrete is not the same as local, and that is the balance to hold.** *"The
communicator gate"* is our word and means nothing to anyone else. *"A gate that
reviews an agent's messages before the human sees them"* is concrete AND readable
by a stranger. Name the thing in plain terms; do not name it by its house name,
and do not replace it with its role.

### Four scopes, and a note usually carries more than one

A lesson holds at one of four levels, and the level decides how the situation is
written. Getting it wrong sends the entry to the wrong readers in both directions.

| scope | holds for | the situation says |
|---|---|---|
| **universal** | everyone, everywhere | the bare condition, no venue, no technology |
| **business / regulatory** | everyone in a domain — it comes from outside the technology (market structure, a regulator, a business model) | the domain condition, and where it was observed |
| **architecture / tech stack** | anyone whose system has this shape | the SHAPE — *"the broker pushes acknowledgements over websocket and has a REST API"* |
| **vendor** | this vendor's implementation and nobody else's | NAME the vendor |

**You must be able to EVIDENCE the scope you claim, and the levels differ in what
counts.** Scope inflation is the over-widening fault wearing new clothes, and it is
seductive for the same reason: the wider claim reads as the more insightful one.

- **business / regulatory** — NAME THE RULE. Reg SHO 201 is a regulation every US
  broker obeys; that is what a regulatory-scope claim looks like. If you cannot
  name the rule, you do not have this scope.
- **architecture** — you must be able to say what about the shape produces the
  behaviour. "Websocket delivery is not guaranteed" is a property of the transport,
  so any broker on it inherits it.
- **vendor** — free. It is what you observed.
- **universal** — the hardest to earn, and it needs the mechanism, not a survey.

**When you cannot evidence a wider scope, write the narrow one and SAY the wider is
unverified.** A worked failure: *"extended-hours sessions take limit orders only —
that is market structure, not one venue's preference"* was written from a single
observation at a single broker, with an invented rationale about auctions and
reference prices attached to make it sound settled. The honest version names the
venue and states plainly that the wider scope is unverified — which is also more
useful, because it tells the reader to go and check rather than to trust.

**And the important half: one source note usually carries several of these, and
folding it into one entry forces a single scope that is wrong for the rest.**

A single postmortem about a missed order acknowledgement contained four:

- *until an order is acknowledged you do not know its status* — universal, true on
  a guaranteed-delivery session too
- *acknowledgements pushed over websocket are not guaranteed; the REST read is the
  truth* — architecture, true of any broker built that way
- *Alpaca SKIPS an order update while the previous message is not fully processed* —
  vendor, and true of nobody else
- *extended-hours sessions take limit orders only* — business/regulatory, market
  structure rather than one venue's preference

**Split them.** Each gets its own situation, because each has different readers. A
person asking *"why was my amendment ignored?"* needs the vendor entry; a person
asking *"should I wait for the acknowledgement?"* needs the universal one. One
merged entry can only carry one situation, so it serves one of them and hides the
other.

**The tell that you have merged scopes:** a paragraph inside the entry that begins
"this part is vendor-specific" or "the rule of thumb transfers but the mechanism
does not". That labelling is honest, and it is a sign that two entries are wearing
one coat.

### The situation must be as wide as the claim — the commonest authoring fault

Measured on a folded sample: **four independent cold readers, on four different
entries, said the same thing** — the situation was narrower than the claim above
it. It is a habit, not a slip, and it has one cause: the situation gets written
from the INCIDENT and the claim gets written from the CLASS.

The four, verbatim from the readers:

| Claim covers | Situation said | Consequence |
|---|---|---|
| any command whose input is a directory | "commit, package or publish" | never fires on a CI artifact upload |
| any derivation of a work-bearing document | "reformulating someone's requirement" | never fires on your own draft, or on a summary |
| any instruction with an observed failure to name | "for the second time" | the count is not the trigger; the observation is |
| adding a tray icon with Linux as a target | "the icon goes out through libayatana-appindicator" | assumes the reader already knows the answer they came for |

The last one is the sharpest and the easiest to commit: **a situation must not
presuppose the thing the entry exists to tell you.** If recognising it requires
knowing what the entry teaches, it fires only for people who no longer need it.

**The check, after writing both:** read the claim, then read the situation, and
ask whether every case the claim covers would recognise itself in the situation.
Where it would not, the situation is a description of the incident and needs
widening to the class — WITHOUT abstracting its vocabulary, which is the opposite
error and is covered above.

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

### the boundary — every principle owes one

A principle that fires on a neighbouring situation and then just asserts is worse
than silence: the reader gets authority without reach, and spends their afternoon
finding out the remedy was for someone else. So every principle names **one
neighbouring case it DOES cover, and one it does NOT** — and where a vendor
mechanism is involved, says which half is the vendor's.

The worked example, because it carries both directions at once:

> Knowing an order's state before you touch it again holds against every venue,
> and against anything that acknowledges asynchronously. But Alpaca *serialises*
> — it skips your next message until the previous one is performed — so "wait for
> the acknowledgement" is Alpaca's mechanism, not the rule. Other venues
> pipeline. Carry the rule; check the protocol.

A fact owes no boundary: its condition already is one. If you find yourself
writing a boundary for a fact, re-read the classification above — you are folding
it into the wrong shape.

### outcome — experiences only

`worked` · `failed_avoid` · `unproven` (genuinely still open).

A `domain_fact`, an `api_contract`, a `naming_convention` or a `reference` owes
**none**. It never turned out any way at all, and inventing an outcome for one
makes retrieval rank on fiction.

### anchor — optional

A symbol or a package, when the story has one. Its absence is normal: experience
is experience without any code.

## Before folding a note, find out whether it was overturned

A note records what was believed when it was written. Some of those beliefs were
later proved wrong, and **the note that was wrong does not know it** — the pointer
runs forward only.

A worked case, and the cost is the point. An investigation into a red staleness
lamp concluded, with pages of arithmetic and a ruled-out list, that the lateness
was ours and the vendor was fine. Days of work rested on one step: *a vendor
failure would be seen by thousands of other customers, and they are not seeing it.*
A later note recorded the resolution — a probe run 9 ms from the vendor's own
datacentre showed trades arriving up to 107 seconds stale, our side provably
clean. It says *"Supersedes the earlier contention/lock lead."* **The superseded
note says nothing.**

Fold the first one and you mint a confident, well-argued, wrong entry. No shape
check sees it. No cold reader can see it — they judge the text in front of them
and cannot check a fact. Retrieval then makes it worse, because a superseded note
is MAXIMALLY relevant to its own subject and outranks the note that killed it.

**So the fold resolves supersession before it judges anything.** The corpus names
its own: search it for *supersedes*, *superseded*, *resolved*, *falsified*,
*refuted*, *correction*. Where one note overturns another, the overturned one is
not folded — and if it is folded for the reasoning it contains, the entry states
what was concluded, that it was wrong, and what was true instead. **That entry is
usually better than either note**, because a documented wrong turn tells a reader
which reasoning to distrust.

Measured on one corpus of 73 notes: 7 name something they supersede, 20 carry a
RESOLVED / FALSIFIED / REFUTED / CORRECTION marker, and of three notes known to be
superseded, **three said nothing about it**.

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
answers four questions:

1. **Which kind is it, and is that right?** — principle, durable fact, or
   perishable fact. Apply the test: does violating it produce a class of
   different problems, or one? A fact wearing a principle's shape is the most
   common defect and the reader is the only one who catches it.
2. **When does this apply?** — restate it. If they cannot, the situation is not
   self-contained. Any word they would need the project explained to understand
   is a failure of the story, not of the reader — and the author is the last
   person who can see it, because it reads perfectly to them.
3. **What would you do differently for having read it?** — if there is no answer,
   it is a comprehensible platitude. Platitudes are what pass every other check,
   which is why this question exists.
4. **Is it the right width?** — name a neighbouring case the claim should also
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
