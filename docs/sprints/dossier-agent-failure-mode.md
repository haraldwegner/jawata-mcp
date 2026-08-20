# Dossier — the failure mode jawata exists for

*(Sprint-29 proof material. Written 2026-08-20, from documented sessions — every
episode below is in the transcripts and the issue trackers, none is constructed.)*

## The claim

A coding agent's native skill is **fluency**: producing designs, explanations and
test suites that are internally consistent. Consistency is not correctness. An
agent can hold every fact in context and still not model meaning or consequences —
context is not meaning. The result is work that reads perfectly and misses the
goal, defended by tests that confirm the design's own vocabulary. This failure
mode is structural, not a prompting defect, and it does not announce itself:
**the better the agent, the more convincing the miss.**

jawata exists because the counter cannot come from inside the agent. It has to be
external: compiler-grade ground truth, gates the agent cannot narrate around, and
a supervision surface where a human sees what the agent actually did rather than
what it says it did.

## The evidence — one product, four documented episodes

All four happened to the same vendor-flagship agent, on its best behavior, building
jawata itself.

**1. The ignored answer.** The experience store injected the exact prior solution
to a classpath defect — the right record, on every single prompt — while the agent
diagnosed that very defect from scratch. Asked afterwards, relevance was obvious in
seconds. No evaluation had ever happened: the knowledge passed through context as
ambience, and "not relevant" was a story constructed after the fact to explain not
having looked. Delivery worked; attention never turned. Nothing in the agent's
output shows the miss — the session reads as diligent work.

**2. The design that contradicts the product.** The same agent designed the
experience store's schema and keyed knowledge to code locations — symbols,
packages — because the adjacent tool (the compiler layer) uses that currency.
Nobody asked the one-sentence question "what happens to this key when the code is
refactored?" although **refactoring is the product**: a refactoring tool whose
memory is invalidated by refactoring. An experience is a principle plus the
conditions where it applies; the code it was learned on is one volatile example.
The design inherited the shape of the nearest tool instead of the shape of the
goal, and it was internally consistent enough that days of work stood on it.

**3. The tests that flattered the design.** The store shipped with a green suite —
record at X, recall at X, pass. Every test asked whether the mechanism does what
the mechanism says. The test derived from the *goal* — record a lesson, refactor
the code it was learned on, ask the question in new words, does the principle
still arrive? — was never written, and could not have been written from inside the
design, because the design defines survival as key-validity. Samples were chosen
because they fit; the gate was satisfied instead of the goal. A green suite was
the costume of the missing evaluation.

**4. The hidden 58.** On the reference workspace, one project reported 2 compile
errors for months. Its build was silently aborting on an incomplete build path;
when jawata's resolve phase completed the path, the true count surfaced: 58 —
48 language-level violations behind a prefs file carrying committed git
merge-conflict markers that the IDE had parsed without complaint since the day of
the bad merge. Tools, like agents, degrade silently; a small number is not a
verdict, it may be a measurement that stopped early.

## Why this is the pitch and not the confession

Every episode above was **caught** — and none was caught by the agent's own
narrative. They were caught by mechanisms that do not depend on the agent's
goodwill: a human reading the actual retrieval output against the actual work; a
blocking guard whose call does not proceed without a declaration (bound 100% of
the time, all session, every session — while every advisory signal was reasoned
past); an adversarial re-measure against ground truth; a resolve phase that
refused to let an aborted build impersonate a clean one.

That is the product thesis in one line: **agents cannot be improved out of this;
they can be instrumented out of it.** The instrument must own ground truth the
agent cannot re-narrate (the compiler), enforcement that rides the agent's own
tool calls (a refused call is red at the moment of ignoring — the only feedback
shape agents reliably respond to), and a surface where the human judges disputes.
The vendor's answer to agent error is a better agent — which produces more
convincing errors. jawata's answer is a harness where convincing is not enough.

## The transferable lesson

Judge agent work by outcomes against frozen, goal-derived measures — never by the
coherence of its account. A benchmark fixed before the code exists, a test set
derived from the world's invariants (what must survive renaming, rewording,
refactoring), a gate that blocks instead of advises. Where an agent's tests
confirm its design's own vocabulary, the design is grading itself.
