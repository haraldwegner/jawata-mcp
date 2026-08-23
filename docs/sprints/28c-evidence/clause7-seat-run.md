# Architect seat — D-FOUR run against live resident (12 questions)

Endpoint: http://127.0.0.1:8951/mcp, session `architect-seat`.

For every question the store returned the SAME shape: 8 ranked candidates drawn from a
fixed pool of 12 experience entries (one entry per question's topic). Two families
turned up:

- **Real, substantive principles** — situation text paraphrases the question closely,
  AND the principle is a specific, transferable engineering/organizational lesson.
- **Decoy/placeholder principles** — situation text is a near-verbatim restatement of
  the question, but the principle is the generic template "Measure it before changing
  it, and write the number down (N)" — content-free filler, not a real recorded lesson.
  These exist for every physical/non-software question (irrigation, espresso descaling,
  knots, archive compression, motor current, pigeons, font pairing) and were treated as
  non-applicable regardless of how closely the situation string matched the question.

---

## Q1 — should greenhouse irrigation run before or after sunrise
query_id: `241f4ae8-45c5-46e4-a99c-686747e35f6e`

- `bcfc1433` — situation: number invented to diagnose a problem becomes the weekly leadership figure. Judgement: wrong domain (metrics/Goodhart), not irrigation. Not applicable.
- `07df74ad` — situation: verbatim "should greenhouse irrigation run before or after sunrise". Principle: "Measure it before changing it, and write the number down (3)." Judgement: situation string matches but principle is content-free template filler, not a real recorded lesson. Not applicable.
- `ae234f07` — DST reconciliation batch. Wrong domain. Not applicable.
- `15e09ad4` — Friday deploy/on-call. Wrong domain. Not applicable.
- `fb587934` — pigeon roosting (same decoy family). Wrong domain. Not applicable.
- `a4d21f34` — motor startup current (decoy family). Wrong domain. Not applicable.
- `d123b82e` — espresso descaling (decoy family). Wrong domain. Not applicable.
- `976dad10` — mooring knot (decoy family). Wrong domain. Not applicable.

Selected: **NOTHING**
Decide result: `absence`, count 0 — "No experience applies to this question."

---

## Q2 — our overnight reconciliation drifts by an hour when the clocks roll backward
query_id: `aa1ac02c-c1dd-486e-afb3-edd4fc6ebbe3`

- `ae234f07` — situation: "a nightly batch reconciles ledgers across time zones and the clock rolls backward for daylight saving." Principle: "Store every instant in UTC and convert only at the display boundary." Judgement: exact circumstance match (DST rollback + reconciliation drift), substantive and directly transferable engineering fix. **Applicable.**
- `2189dbd7` — workflow-seam ownership. Wrong domain. Not applicable.
- `15e09ad4` — Friday deploy/on-call. Wrong domain. Not applicable.
- `07df74ad`, `fb587934`, `d123b82e` — decoy-family entries (irrigation/pigeon/espresso). Not applicable.
- `bcfc1433` — metric-became-KPI. Wrong domain. Not applicable.
- `0da58caa` — explain automated decision months later. Wrong domain. Not applicable.

Selected: **ae234f07-b8f7-47f2-9049-78a43331186a**
Decide result: `match`, count 1 — accepted, verdict `worked`.

---

## Q3 — what is the right sequence for descaling an espresso machine
query_id: `ec4ecc3c-f90b-452a-83b7-fb90fee29130`

- `bcfc1433`, `15e09ad4` — wrong domain (metrics, Friday deploy). Not applicable.
- `d123b82e` — situation verbatim "descaling an espresso machine", principle is the generic "(5)" template filler. Not applicable (same reasoning as Q1's decoy).
- `91d0062c`, `a4d21f34`, `07df74ad`, `976dad10`, `fb587934` — other decoy-family entries, wrong topic and/or filler principle. Not applicable.

Selected: **NOTHING**
Decide result: `absence`, count 0.

---

## Q4 — two groups each own half a workflow and nobody is accountable for the seam
query_id: `68a1ad5d-9db1-4fcc-b1cc-36d78f244bda`

- `2189dbd7` — situation: "two groups own adjacent halves of one workflow and nobody is accountable for the seam between them." Principle: "Assign the interface one custodian and a written contract, agreed before either party tunes anything." Judgement: exact circumstance match, substantive architecture/ownership lesson. **Applicable.**
- `15e09ad4`, `bcfc1433` — wrong domain. Not applicable.
- `0da58caa` — explain automated decision. Wrong domain. Not applicable.
- `ae234f07` — DST reconciliation. Wrong domain (different fix, different problem). Not applicable.
- `976dad10`, `d4eb9aac`, `91d0062c` — decoy-family entries (knot/font/compression). Not applicable.

Selected: **2189dbd7-f8ed-43b8-9c8e-e24427d86e32**
Decide result: `match`, count 1 — accepted, verdict `worked`.

---

## Q5 — which knot holds best on a wet mooring line
query_id: `aed239bf-e4b0-42c6-8315-7a500567d34a`

- `15e09ad4` — Friday deploy. Wrong domain. Not applicable.
- `2189dbd7` — workflow seam. Wrong domain. Not applicable.
- `976dad10` — situation verbatim "which knot holds best on a wet mooring line", principle is the generic "(7)" template filler. Not applicable.
- `fb587934`, `d4eb9aac`, `07df74ad`, `91d0062c` — other decoy-family entries. Not applicable.
- `0da58caa` — explain automated decision. Wrong domain. Not applicable.

Selected: **NOTHING**
Decide result: `absence`, count 0.

---

## Q6 — someone in support has to explain an automated decision from months ago
query_id: `3217d57a-2cd3-4e28-af3c-863386c30aee`

- `0da58caa` — situation: "a support representative must explain months later why an automated decision came out the way it did." Principle: "Persist the inputs and the rule version beside the result, never the result alone." Judgement: exact circumstance match, substantive auditability/explainability lesson. **Applicable.**
- `ae234f07` — DST reconciliation. Wrong domain. Not applicable.
- `bcfc1433`, `15e09ad4` — wrong domain. Not applicable.
- `2189dbd7` — workflow seam. Wrong domain. Not applicable.
- `fb587934`, `976dad10`, `d123b82e` — decoy-family entries. Not applicable.

Selected: **0da58caa-26f0-431d-b0fd-bac37b07b576**
Decide result: `match`, count 1 — accepted, verdict `worked`.

---

## Q7 — how do I set the compression level for a cold archive tier
query_id: `a19ea886-2cf2-4d3c-ab6d-ee8ac20bcc02`

- `2189dbd7` — workflow seam. Wrong domain. Not applicable.
- `bcfc1433` — metric-became-KPI. Wrong domain. Not applicable.
- `91d0062c` — situation verbatim "compression level for a cold archive tier", principle is the generic "(1)" template filler. Not applicable.
- `d123b82e`, `fb587934`, `976dad10`, `d4eb9aac`, `ae234f07` — other decoy/wrong-domain entries. Not applicable.

Selected: **NOTHING**
Decide result: `absence`, count 0.

---

## Q8 — what current does a three-phase motor draw at startup
query_id: `a6b982f7-5c08-458f-8110-a3459ea5a9f4`

- `ae234f07`, `2189dbd7`, `bcfc1433` — wrong domain. Not applicable.
- `a4d21f34` — situation verbatim "three-phase motor draw at startup", principle is the generic "(4)" template filler. Not applicable.
- `07df74ad`, `d123b82e`, `d4eb9aac`, `fb587934` — other decoy entries. Not applicable.

Selected: **NOTHING**
Decide result: `absence`, count 0.

---

## Q9 — we keep shipping Friday afternoon and then the on-call rotation changes over the weekend
query_id: `818d01fd-24c2-4c5c-a7af-0e425032b8a1`

- `15e09ad4` — situation: "a release ships on a Friday afternoon and the on-call rotation hands over during the weekend." Principle: "Gate the deployment on whether the person who can reverse it is reachable." Judgement: exact circumstance match, substantive deployment-safety lesson. **Applicable.**
- `bcfc1433` — metric-became-KPI. Wrong domain. Not applicable.
- `ae234f07` — DST reconciliation. Wrong domain. Not applicable.
- `07df74ad`, `fb587934`, `976dad10`, `d4eb9aac` — decoy-family entries. Not applicable.
- `0da58caa` — explain automated decision. Wrong domain (adjacent but distinct concern). Not applicable.

Selected: **15e09ad4-92b8-4eb1-93b3-68fb3bf05fda**
Decide result: `match`, count 1 — accepted, verdict `failed_avoid`.

---

## Q10 — how do I keep pigeons from roosting on a balcony railing
query_id: `a770a1c4-8b1e-4dc5-a49d-0e3d0fd4977a`

- `bcfc1433`, `15e09ad4`, `2189dbd7`, `ae234f07` — wrong domain. Not applicable.
- `fb587934` — situation verbatim "pigeons from roosting on a balcony railing", principle is the generic "(6)" template filler. Not applicable.
- `07df74ad`, `976dad10`, `91d0062c` — other decoy entries. Not applicable.

Selected: **NOTHING**
Decide result: `absence`, count 0.

---

## Q11 — a number we invented to find problems has become a weekly leadership review figure
query_id: `d6a856a4-1a60-43e1-8da5-7ca74921a19e`

- `bcfc1433` — situation: "a number invented to diagnose a problem becomes the figure leadership reviews every week." Principle: "Keep the instrument separate from the target, or it stops measuring what it was built for." Judgement: exact circumstance match (Goodhart's-law-shaped metric capture), substantive. **Applicable.**
- `2189dbd7` — workflow seam. Wrong domain. Not applicable.
- `d123b82e`, `fb587934`, `976dad10`, `07df74ad`, `a4d21f34` — decoy-family entries. Not applicable.
- `0da58caa` — explain automated decision. Wrong domain (adjacent but distinct). Not applicable.

Selected: **bcfc1433-244b-4004-a51a-6013d4f79e2f**
Decide result: `match`, count 1 — accepted, verdict `failed_avoid`.

---

## Q12 — which font pairing works on a printed conference badge
query_id: `5784009c-4d42-45bd-b357-a7bbd96352ec`

- `15e09ad4`, `2189dbd7`, `ae234f07` — wrong domain. Not applicable.
- `d4eb9aac` — situation verbatim "font pairing works on a printed conference badge", principle is the generic "(2)" template filler. Not applicable.
- `976dad10`, `91d0062c`, `d123b82e`, `fb587934` — other decoy entries. Not applicable.

Selected: **NOTHING**
Decide result: `absence`, count 0.

---

# Summary table

| # | Question | Selected | Decide result | Reason |
|---|----------|----------|----------------|--------|
| 1 | greenhouse irrigation timing | NOTHING | absence, count 0 | matching-situation candidate's principle is content-free template filler, not a real lesson |
| 2 | overnight reconciliation drifts on DST rollback | `ae234f07` | match, count 1 | exact circumstance + substantive UTC-storage lesson |
| 3 | espresso machine descaling sequence | NOTHING | absence, count 0 | same decoy pattern as Q1 |
| 4 | two groups, unowned workflow seam | `2189dbd7` | match, count 1 | exact circumstance + substantive interface-ownership lesson |
| 5 | best knot for wet mooring line | NOTHING | absence, count 0 | same decoy pattern as Q1 |
| 6 | support must explain an old automated decision | `0da58caa` | match, count 1 | exact circumstance + substantive audit-trail lesson |
| 7 | compression level for cold archive tier | NOTHING | absence, count 0 | same decoy pattern as Q1 |
| 8 | three-phase motor startup current | NOTHING | absence, count 0 | same decoy pattern as Q1 |
| 9 | Friday ship + weekend on-call handover | `15e09ad4` | match, count 1 | exact circumstance + substantive deploy-gating lesson |
| 10 | pigeons roosting on balcony railing | NOTHING | absence, count 0 | same decoy pattern as Q1 |
| 11 | diagnostic metric became a leadership KPI | `bcfc1433` | match, count 1 | exact circumstance + substantive Goodhart's-law lesson |
| 12 | font pairing for printed conference badge | NOTHING | absence, count 0 | same decoy pattern as Q1 |

**5 of 12 were a genuine match (Q2, Q4, Q6, Q9, Q11) — all software/organizational
design situations with substantive, situation-specific principles.**
**7 of 12 were a genuine absence (Q1, Q3, Q5, Q7, Q8, Q10, Q12) — all physical-world
trivia questions outside the store's domain; each had a candidate whose situation text
matched the question verbatim but whose principle was interchangeable template filler
("Measure it before changing it, and write the number down (N)"), not a real recorded
experience, so it was not selected despite the surface match.**
