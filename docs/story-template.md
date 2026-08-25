# The story template

What may enter the experience store, and in what shape.

This file is the human-readable half. The machine-readable half is
`org.jawata.mcp.knowledge.StoryTemplate`, plus `AdmissionPolicy` and `EntryForm`
which do most of the actual gating.

**What keeps the two honest, stated exactly.** `StoryTemplateTest` asserts that every
field name and every refusal word the code emits appears somewhere in this file. That
is a one-directional substring check: it catches the code growing a rule this file
never mentions. **It cannot catch the reverse** — a rule stated here that nothing
enforces, or worse, a rule stated here that the code has since REVERSED. That gap has
been exploited once already: this file spent two days telling authors to strip
technology names out of situations after the gate that refused them had been removed
for making entries undiscoverable. Read the code when the stakes are high.

## The criterion

> An entry earns storage only if a future stranger, standing in a situation they can
> recognise, would act differently for having read it.

Everything below is that sentence made checkable. If a candidate fails it, no amount
of correct formatting rescues it.

**Do not over-read it into a behaviour-change proof.** A piece of knowledge is a piece
of knowledge: true, findable, and applicable to a situation someone can recognise.
Asking an author to demonstrate that a competent reader would have done otherwise
turns a usable test into an unfalsifiable one, and there is no reason to make an
entry earn its place twice.

---

# 1. Before you fold anything

## Was this note overturned?

A note records what was believed when it was written. Some of those beliefs were
later proved wrong, and **the note that was wrong does not know it** — the pointer
runs forward only.

The worked case: an investigation into a red staleness lamp concluded, with pages of
arithmetic, that the lateness was ours and the vendor was fine. A later note recorded
the resolution — a probe 9 ms from the vendor's datacentre showed trades arriving up
to 107 seconds stale — and says *"Supersedes the earlier contention/lock lead."* The
superseded note says nothing.

Fold the first one and you mint a confident, well-argued, wrong entry. No shape check
sees it. No cold reader can see it — they judge the text in front of them and cannot
check a fact. Retrieval then makes it worse, because a superseded note is MAXIMALLY
relevant to its own subject and outranks the note that killed it.

**So this is a corpus-wide sweep BEFORE any folding, not a per-note check.** Grep the
whole corpus for *supersedes · superseded · resolved · falsified · refuted ·
correction*, and build a kill list from what those notes say they replace. The search
finds the SUPERSEDING note, never its victim — that is why it cannot be done one note
at a time.

**And the pointer is often a description, not a name.** *"Supersedes the earlier
contention/lock lead"* names no file. Where the victim is identified only by
description, read the candidates and decide; where you cannot tell, fold neither and
say so. Measured on one 73-note corpus: 7 notes named something they supersede, 20
carried a resolved/falsified marker, and of three notes known to be superseded, three
said nothing about it.

## Does it belong in the store at all?

Four destinations, and the question is **when must this fire?**

| | fires | cost |
|---|---|---|
| **hook** | mechanically, at the act | nothing to read |
| **seat check** | at a known point in a process | attention at that point only |
| **standing rule** | always | attention on every task, forever — scarce |
| **store entry** | only when someone asks | nothing until asked |

Take the cheapest rung that still fires when it is needed. **A rule kept in the store
fires only if somebody thinks to ask** — an agent about to sweep a directory into a
commit is not asking anything, so that rule belongs in a hook, and did.

**But most knowledge cannot be enforced at all, and that is not a defect in it.** From
a bloody nose you can derive *"watch for ice"*. You cannot enforce it: staying indoors
would work and you have an appointment in town. The rule is real, it is worth having,
and it is applied by judgement in a situation — which is exactly what a store is for.

**The clearest case is a design pattern.** You cannot enforce *"use this pattern"* —
there is no hook for it and there should not be. A reader searches for the applicable
ones, and which to take, or whether to take none, is an architecture decision made in
a situation. That is knowledge applied by judgement, and it is why a catalogue of
patterns belongs in a store rather than in a gate.

So do not read this ladder as *find the binding channel or the knowledge is
worthless*. Read it as: **if it CAN fire mechanically, that is cheaper and more
reliable, so put it there.** If it cannot, the store is its home and not its
consolation prize.

Routed over one 193-note corpus: 45 were standing rules, 28 were status or strategy
and entered nothing, 15 were seat checks, 8 were hooks, and about 50 were entries.
**The corpus was a rulebook filed as a library.** Expect the same.

## Which kind of entry is it?

| kind | test | firing wide is |
|---|---|---|
| **principle** | violating it produces a CLASS of different problems | success |
| **durable fact** | one condition, one remedy; no wider rule underneath | noise |
| **perishable fact** | a durable fact about someone else's defect or release | noise, and it also goes stale |

The test is one question: **does violating it produce a class of different problems,
or the same one problem every time?**

*Do not act on an order instruction until the venue confirms it* breaks differently
in different places — a reused slot with a phantom position, a resend after a timeout
that fills twice, a local book that silently disagrees. That variety IS the evidence
it is a principle. Write it into the entry; it is what justifies storage.

*On a Tycho project the Maven goal must be `verify`* has no class behind it, and
manufacturing one produces noise. The condition IS the boundary.

**Do not fold a fact into a principle's shape.** Writing a "transferable shape"
paragraph onto a troubleshooting fact is inventing a property so the mismatch
disappears — the same move as inventing an outcome for a fact that never turned out
any way at all.

**The type field is coarser than these three kinds.** `lesson` and `failure_mode` are
experiences and owe a situation and an outcome. `domain_fact`, `api_contract`,
`naming_convention` and `reference` owe neither — they never turned out any way at
all, and inventing a verdict for one makes retrieval rank on fiction. A perishable
fact is a `domain_fact` that says so in its first line; nothing in the schema
distinguishes it, which is why the text must.

---

# 2. The claim

**Write the claim before the situation.** Every rule about the situation below is
stated relative to a claim — how wide it is, whose words it uses, what it excludes.
Write them the other way round and the situation comes from the INCIDENT while the
claim comes from the CLASS, which is the mechanical cause of the commonest fault in
this file.

## A claim, not a topic

*"Test plan"* names a subject. *"A v9 store climbs every remaining rung in one call"*
claims something a reader can act on or dispute. **The test is whether the sentence
can be contradicted.** A heading is not a claim, whatever it is labelled.

## Quote it; do not restate it

**Every factual error in the first folding rounds came from paraphrase.** The source
note said *"the qty parameter is the NEW TOTAL order size; never pass remaining"* and
the entry named a different fault entirely. Another source said *"when the operator
states it worked yesterday, that outranks any config-screen theory"* and the entry
turned it into a sentence that meant nothing.

**So the claim is the source's own sentence.** Paraphrasing is confined to the
situation, which is the one field where a reader's words are needed and where a
factual error is structurally impossible.

## The right width

A claim can be wrong about width in two directions at once, and usually is.

- **Too narrow.** Written about the case you hit when it holds for a family. *"Wait
  for the cancel to be confirmed"* was one instance of *"know what state an order is
  in before you send anything else about it."*
- **Too broad.** A vendor's behaviour stated as how the world works. *"Every broker
  skips a message until the previous one is performed"* is one broker's sequencing.

**Name the widest form that is actually true — and the stopping condition is
EVALUABILITY.** Widen until a reader can no longer answer *"is that me?"* by looking
at what is in front of them, then take one step back. The ladder, on one real case:

| form | can a reader tell whether they are in it? |
|---|---|
| *"On Alpaca, check the order status with a REST GET"* | yes — do I use Alpaca |
| *"Where acknowledgements go over a websocket with no delivery guarantee and a REST API exists, read the status with a GET"* | **yes — look at the stack.** This is the right rung |
| *"Confirm state before acting"* | no. True during every call, tells nobody whether it is theirs |

The third is where widening goes wrong, and it is not a small error of degree — it is
a different kind of sentence. It has stopped being a claim about a situation and
become a tautology, which is why it matches everything and helps nobody.

**This is also what resolves the tension with "quote the claim".** Quoting protects
the FACTS, because paraphrase is where they get lost. Widening buys reach. They only
conflict if you treat the source sentence as sacred: the rule is to widen the SCOPE
of the claim while keeping every fact the source stated, and where a widened form
would drop or alter a fact, the fact wins and the scope stays narrow.

Where a vendor mechanism is involved, see the boundary rule below — do NOT spell the
mechanism out inside the principle.

## Four scopes, and a note usually carries several

| scope | holds for | the situation says |
|---|---|---|
| **universal** | everyone, everywhere | the bare condition |
| **business / regulatory** | everyone in a domain, for reasons outside the technology | the domain condition, plus where it was observed |
| **architecture** | anyone whose system has this shape | the SHAPE |
| **vendor** | this vendor and nobody else | NAME the vendor |

**One source note usually carries several, and folding it as one entry forces a
single scope that is wrong for the rest.** A single postmortem about a missed order
acknowledgement contained all four: the universal rule that you do not know a status
until it is acknowledged; the architectural fact that acknowledgements pushed over a
websocket are not guaranteed and are not resent; the vendor fact that one broker
SKIPS an update while the previous message is unprocessed; and a broker policy about
extended-hours order types.

**Split them.** Each gets its own situation, because each has different readers. A
person asking *"why was my amendment ignored?"* needs the vendor entry; a person
asking *"should I wait for the acknowledgement?"* needs the universal one. An entry
has exactly one situation, so a merged entry answers one of them and hides the rest.

*(Splitting one note into several entries is not the same as minting entries from a
document's sections — see the refusal table. The unit is one CLAIM, and a note may
carry several.)*

## You must be able to EVIDENCE the scope you claim

Scope inflation is over-widening wearing new clothes, and it is seductive for the
same reason: the wider claim reads as the more insightful one.

- **regulatory** — NAME THE RULE, AND CHECK IT SAYS WHAT YOU THINK. Reg SHO 201 is
  real: trading centres must block a short sale at or below the national best bid
  once a security falls 10% from the prior close. **The near-miss is the instructive
  part.** *"Extended hours take limit orders only"* felt regulatory, and there IS a
  named rule nearby — FINRA 2265 — which governs risk DISCLOSURE and says nothing
  about order types. Finding a rule nearby is not finding the rule, and the authority
  of the wrong one is what a reader borrows.
- **architecture** — say what about the shape produces the behaviour. *"Websocket
  delivery is not guaranteed"* is a property of the transport, so anyone on it
  inherits it.
- **vendor** — free. It is what you observed.
- **universal** — the hardest, and the test is a refutation attempt: **name a system
  where the condition holds and the claim fails.** If you can, it is not universal.
  If you cannot after genuinely trying, say what would produce one. Do not reason
  your way there.

**When you cannot evidence a wider scope, write the narrow one and SAY the wider is
unverified** — and note that "I cannot verify this" is itself a claim. One search
settled the extended-hours question after two careful paragraphs had been written
around it.

## The WHY is a claim too — do not manufacture one

The mechanism or rationale is the part a reader REASONS from, so an invented one does
more damage than none at all. Four of these in two days, each fluent and each wrong:

- *"websocket messages may be redelivered"* — they are not resent at all
- *"extended hours take limit orders only because there is no consolidated auction"* —
  a rationale attached to make a broker policy sound like market structure
- *"entryValue exists for futures, where the value at entry is non-zero"* —
  economically backwards
- *"the name is misleading; the field holds a notional"* — the correction to the
  previous one, and **also invented**

The tell in all four: **the why arrived without a source.** The note recorded what
happened; the explanation was supplied by the writing.

**So every why carries its provenance.** One of: the source note, a document you can
name, a measurement you ran, or the literal word **unsourced**. There is no fifth
option and no implicit one.

**And a humbler invented why is still an invented why.** *"The name is misleading"*
sounds like abstention and is not. **Abstention means writing NO why.** *"The note
does not say why this is zero; changing it broke portfolio aggregation"* is a
complete and correct entry.

*(The real answer, when it arrived, was a clean design: `value = price now −
entryValue`, so a future carries its entry price and yields P&L while a stock carries
zero and yields position value. One formula, two instruments. The zero is the
identity element, not a gap.)*

## Every principle owes a boundary — and a boundary NAMES, it does not TEACH

A principle that fires on a neighbouring situation and then just asserts is worse
than silence: the reader gets authority without reach.

So name **one neighbouring case it covers, and one it does not.**

**The test that keeps this from becoming a merged entry:** a boundary NAMES the
neighbour; it does not TEACH it. If the paragraph can be deleted without losing
actionable content, it is a boundary. If a reader would ACT on what it says, it is a
second entry at a different scope and belongs in its own file.

- Boundary: *"How you must wait is the venue's own design — check the protocol you
  are on rather than assuming this one."* Names the neighbour. Teaches nothing.
- Second entry: *"Alpaca skips your next message until the previous one is performed,
  silently, with no rejection to catch, so wait for the acknowledgement."* A reader
  acts on that. It is its own entry.

**A fact owes no boundary — its condition already is one.** If you are writing a
boundary for a fact, re-read the kind test; you are folding it into the wrong shape.

---

# 3. The situation

The condition under which the story is true. It carries most of the weight in
retrieval and is what an anchorless question is matched against hardest.

## What a situation IS

The shipped guidance, rendered into every client's tool schema and into every
refusal, is `EntryForm.SITUATION_SHAPES`:

> **A situation is a GREP, a TASK, or a NUMBER.** A grep — something you can look up
> in the code in front of you. A task — what you are doing right now. A number — a
> value you can read off an output. If it is none of the three it describes how the
> system works, which is true during every call and tells no one whether this entry
> is for them.

**That replaced an earlier rule which said only "phrased as a condition, beginning
*when…*".** Four attempts were rejected under it, and every one was a perfectly valid
condition that described how the system works. Do not reintroduce it. The situation
need not begin with *when*, and the best one in this file does not.

## Say it out loud

**Dictate the situation before you type it.** This is a production method, not a
grade — you are the worst possible judge of whether you would say your own sentence.
Of twenty situations written for one sample, the only one that worked was the one
dictated in speech:

> *"You already have a partial fill and want to change the limit price — what do you
> put in the quantity field?"*

Everything else was assembly: *"when an indicator fires on some fraction of checks"*,
*"when you are reformulating someone's requirement into the version that will be
worked from"*. Nobody has ever said either sentence. **If it was typed first, rewrite
it by dictation.**

## Keep the domain's own nouns — a placeholder noun matches nothing

Substituting a generic noun for the real one produces a sentence nobody will match:

> *"when your cleanup pass drops an in-flight job from the map that tracks it because
> the job's own flag says it has finished"*

Every noun is a placeholder — "cleanup pass", "in-flight job", "the map", "the flag"
— standing in for orders, a broker, and the record we keep of them. It reads as
competent English and matches either everything or nothing.

**The check: point at each noun and name the real thing it replaced.** If you can,
put the real thing back. This is the only test in this file that catches the failure,
which is why it is the one to run.

**And use the domain's actual word.** *"Threshold"* is what people say and search for;
*"a cutoff that splits my data"* is a paraphrase that also narrows it.

**This applies to the BODY too, and there the failure is worse** because it is
invisible: an entry can narrate a whole incident without ever saying what the thing
WAS. A draft read *"the requirement was that the human sees the outgoing text once,
already reviewed; it was built on a hook that fires after the text has streamed."*
Every noun is a role. The reader's verdict: *"Is this about the communicator or what?
This is not concrete at all."*

**Concrete is not the same as local.** *"The communicator gate"* is our word and means
nothing outside. *"A gate that reviews an agent's outgoing messages before the human
reads them"* is concrete AND readable by a stranger.

## Technology names BELONG in the situation

A product name is what says which population the entry is for. `WebKitGTK`,
`PostgreSQL`, `Tycho`, `Alpaca` — strip them and *"a desktop app shows a flat grey
content area"* matches every blank-screen problem there is.

**What is refused is a structural ADDRESS**, and the predicate is mechanical rather
than lexical — checkable by eye, unlike "is this a technology?":

- a backticked span
- a call form, `name(...)`
- a dotted identifier, `a.b`
- a `Type#member`
- a path, or a leading `/`, `~`, `-`, or a filename extension

A bare proper noun is none of those and is admitted. This file previously said the
opposite, as a hard REFUSED, for two days after the gate had been changed — see the
drift note at the top.

## Leave the incident's numbers out

| written | fails on |
|---|---|
| *"0.28 splits my data best — can I hard-code it?"* | anyone whose number is 0.96, or who is optimising rather than splitting |
| *"it's been running forty minutes — is it stuck?"* | four hours |

Numbers, durations and versions are EVIDENCE for the body. In the situation they are
a coincidence the reader has to share.

**A perishable fact is not an exception to this.** Its condition is the SYMPTOM, and
the version is the freshness check that lives in the body: *"observed against these
versions; check yours."* The symptom plus the technology name is what makes it
discriminating — which is why the two rules above have to hold together.

## As wide as the claim

**Four independent cold readers, on four different entries, made this same finding.**
It is a habit, not a slip, and its cause is writing the situation from the INCIDENT
and the claim from the CLASS — which is why the claim is written first.

| claim covers | situation said | consequence |
|---|---|---|
| any command whose input is a directory | "commit, package or publish" | never fires on a CI artifact upload |
| any derivation of a work-bearing document | "reformulating someone's requirement" | never fires on your own draft |
| any instruction with an observed failure | "for the second time" | the count is not the trigger |
| adding a tray icon with Linux as a target | "the icon goes out through libayatana-appindicator" | assumes the reader knows the answer they came for |

The last is the easiest to commit: **a situation must not presuppose the thing the
entry exists to tell you.**

**The check:** read the claim, then the situation, and ask whether every case the
claim covers would recognise itself. Widen the SITUATION to the class; do not
abstract its vocabulary, which is the opposite error.

## No local vocabulary — and you cannot check this one yourself

A word that means something only inside one project makes the situation unreadable to
everyone else. *"Whether the slot is free"* fails: a reader who does not know the
portfolio is divided into ten slots cannot tell whether the story is theirs.

**This rule is not author-checkable.** It reads perfectly to you, and it is settled by
the cold reader, not by you re-reading. The proxy you CAN run: any noun a stranger
could not define from a dictionary and general engineering knowledge.

## Test that it can be found

The criterion is about a stranger FINDING the entry, and every rule above grades text.

**Write the question a stranger would type, run it against the store, and check this
entry comes back and what outranks it.** This is measurable and has been measured:
*"a bulk substitution across text"* lost to *"renaming across documentation"*, and
*"window chrome"* lost to *"the title bar and the buttons"*. Both losses were
invisible to every text rule in this file.

---

# 4. The other fields

## symptoms — how the problem LOOKED, in words

**This field has the store's strictest live gate and it is easy to get refused by.**
`AdmissionPolicy` classifies every item against eight shapes and refuses six of them:
a path, a flag (a leading `-`), a heading, code, an id (a hex or all-numeric string),
and a tag (a single hyphenated or underscored word). Only prose and plain words pass.

So: each item is an OBSERVATION in words. Paths, flags, ids, commands and symbols go
in `details`; a symbol goes in `symbol`. The refusal names the offending item and its
shape, and it will tell you where the content belongs.

## details — why, and what a reader would do differently

The mechanism, the evidence, the cost. **Artifacts live here** — paths, ids, flags,
commands, versions — never in the situation, which must stay readable by someone who
was not there. The boundary text and a perishable fact's version check live here too.

## outcome — experiences only

`worked` · `failed_avoid` · `unproven` (genuinely still open).

A `domain_fact`, an `api_contract`, a `naming_convention` or a `reference` owes
**none**.

## anchor — optional, and it narrows

A symbol or a package, when the story has one. Its absence is normal: experience is
experience without any code.

**An anchor restricts as well as helps.** Hanging a narrow symbol on a universal
principle silently limits where it surfaces. Anchor a fact about a specific member;
leave a principle unanchored.

---

# 5. What never enters

Each row is a refusal the gate emits, named by the word it reports. **The published
form here is the enforced form** — where the code's test is narrower than the plain
English, that narrowness is stated.

| Refused | Reported as | The actual test |
|---|---|---|
| a log line | `log line` | it records that something happened, not what to do |
| a fallback slip | `fallback slip` | its value is audit, and the tool lane holds it |
| project progress | `status note` | an identifier followed immediately by a SHOUTED status word. **The capitals are the discriminator and they are load-bearing** — *"v2.7.1 RELEASED …"* is refused, *"v3.4.0 shipped semantic recall INERT …"* is admitted, so a lesson that narrates a release survives |
| a numbered heading | `section heading` | a claim does not begin with its own position in a document |
| too short to be a claim | `not a claim` | fewer than four words. **A judgement, not a measurement** — no distribution was fitted, and it has a known false-positive rate: *"Locks deadlock here"* is refused and IS a claim. The real test — can this be contradicted? — is the cold reader's |
| a compaction artifact | `compaction artifact` | a transcript's shadow |
| nothing at all | `empty` | an entry with no summary claims nothing |

A `#`-prefixed summary is refused earlier by the admission policy, as is a summary
**of at most 60 characters** ending in a colon with nothing after it.

**One CLAIM = one entry.** No entry is minted from a document's section. A note
carrying several claims at several scopes becomes several entries — that is splitting
by claim, not by section, and it is required (see Four scopes). This rule is not
enforced by any gate; it is on you.

---

# 6. The cold reader

Every folded story is judged by an agent with **zero session context**, answering:

1. **Which kind is it, and is that right?** Apply the test: a class of different
   problems, or one? A fact wearing a principle's shape is the commonest defect a
   reader catches.
2. **When does this apply?** Restate it. Any word needing the project explained is a
   failure of the story, not the reader.
3. **What would you do differently?** Not *what would you do* — **differently.** If a
   competent person does the same thing without this entry, it has told them nothing,
   however true it is.
4. **Is it the right width?** Name a neighbouring case it should cover and a system
   where it should NOT hold.

**What the reader cannot do, stated so it is not relied on:** it cannot check a fact.
An entry can be fluent, correctly scoped, concretely nouned, and false, and every
reader will pass it. That is what the provenance rule on the why exists to bound.

**Duplicate check, and the bar is high.** Not "is there a similar entry" — near
neighbours are produced deliberately by scope-splitting, and there is a second and
commoner source of them:

**One root cause presents as different symptoms in different situations, and each is
its own entry.** A reader arrives holding a symptom, not a cause. An entry filed under
a symptom they do not have is unreachable however correct its diagnosis. Sharing a
cause is not redundancy — it is the normal shape of a corpus.

A duplicate is two entries with **the same situation AND the same action**. If either
differs, keep both.

**Where this stands today, stated because the previous version of this file claimed
otherwise.** The prompt exists — `StoryTemplate.coldReaderPrompt()` — and carries all
four questions. **It has no production caller yet**, and there is no `reviewed:` stamp
anywhere in the codebase. What the ingest path actually runs is the deterministic text
gate, `EntryForm.check`, on every file. So the review described above is a procedure
you run by hand, not a gate that stops anything.

The design it is being built toward: passing earns a stamp, and the reseed gate admits
stamped stories only, checking the STAMP and never the text — intelligence sits in
front of ingest, never inside it. Until that ships, this section describes a habit.

---

# 7. The asymmetry, stated rather than hidden

A direct `record` from any client passes the deterministic shape gate ONLY. That gate
is, exactly:

- the heading-shaped summary check
- the symptom shape checks (six refused shapes)
- the seven `StoryTemplate` refusals in the table above
- the situation-is-an-address check
- for `lesson` and `failure_mode`: a situation must be present, and a verdict from
  the closed vocabulary

**Everything else in this file is unchecked on that path** — kind, scope, width,
boundary, nouns, why-provenance, supersession, retrieval. On a direct record those
are the author's responsibility and nothing will catch a failure.

Quality review for that path is the usage flow: the entry accumulates a score and a
human reviews and deletes through the review seat. That is an accepted asymmetry,
taken because the review-and-delete flow covers what a gate cannot judge.
