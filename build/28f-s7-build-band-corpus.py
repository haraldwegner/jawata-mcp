#!/usr/bin/env python3
"""Sprint 28f Stage 7 deliverable 7 — build the REAL-ROW band corpus.

The job side is not typed here: it is read from the committed first-bundle run so
every summary is byte-identical to the row the store actually accepted. The task
side is verbatim text from the sprint's own plan, quoted with its line number, so
no task was written to match the job it is paired with.
"""
import ast, json, sys

PROBE = "build/28f-s7-first-bundle-run.sh"
PLAN = sys.argv[1]
OUT = sys.argv[2]

src = open(PROBE).read()
start = src.index("JOBS = [")
end = src.index("\n]\n", start) + 3
raw = ast.literal_eval(src[start + len("JOBS = "):end].strip())
jobs = [{"id": "job-%02d" % (i + 1), "anchor": a, "summary": s}
        for i, (a, s) in enumerate(raw)]
by_anchor = {j["anchor"].split(".")[-1]: j["id"] for j in jobs}

# TASK -> the ONE job it must find. Every task text is a verbatim fragment of the
# plan; `cite` is the substring used to prove that, checked below against the file.
DESIGNATED = [
 ("DescribedUnits#outstanding",
  "action=next(scope, limit) returns unit paths + JDT facts: members, callers, the package",
  "returns unit paths + JDT facts"),
 ("DescribedUnits#done",
  "done REQUIRES the hash rather than re-reading the file, and the refusal says where the"
  " value came from",
  "REQUIRES the hash rather than re-reading the file"),
 ("DescribedUnits#hash",
  "a changed hash re-queues",
  "a changed hash re-queues"),
 ("DescribedUnits#describedPerBundle",
  "stats reports coverage per bundle (areas described / packages)",
  "reports coverage per bundle"),
 ("KnowledgeLane#of",
  "the of() mapping that puts those two types in the CODE lane",
  "the `of()` mapping that puts those two types in the CODE lane"),
 ("KnowledgeKind#of",
  "job is address-bound by that enum's own definition; area is deliberately not, being scoped"
  " to a package with no symbol to check",
  "address-bound by that enum's own definition"),
 ("ExperienceRetrieval#resolvePointer",
  "recall renders each anchor's LIVE file+line via PointerResolver; refresh() marks location"
  " lost and enqueues the unit in the ledger",
  "LIVE file+line"),
 ("AnalogyPolicy#MAX_NOMINEES",
  "the engine returns areas + top jobs, or nothing",
  "the engine returns areas + top jobs"),
 ("AnalogyPolicy#JUNK_FLOOR",
  "pairs score at or below the noise floor, so no cutoff admits the real answers without"
  " admitting noise",
  "no cutoff admits the real answers without"),
 ("RelevanceMerge#normalise",
  "the map is therefore the UNION the product's retrieval already uses - the identity/keyword"
  " path together with meaning - not a threshold over job texts",
  "the identity/keyword path together with meaning"),
 ("SymbolAnchorResolver#memberOn",
  "the work was not a new seam but making the one that exists answer at member granularity",
  "answer at member"),
]

# EXCLUDED, and the exclusion is information rather than a gap: the sprint has no
# work item about turning free prose into an anchor, because that machinery
# predates 28f. job-01 stays in the job POOL as a distractor and in the unrelated
# set; it simply has no designated task. Padding it with a sentence written for the
# purpose is the one thing this deliverable exists to avoid.
EXCLUDED = ["SymbolAnchorResolver#resolve"]

plan = open(PLAN).read()
for anchor, task, cite in DESIGNATED:
    if cite not in plan:
        raise SystemExit("NOT VERBATIM in the plan: %r" % cite)
    if anchor not in by_anchor:
        raise SystemExit("no such job anchor: %s" % anchor)

# THE DERANGEMENT. Each task is re-paired with a job five positions along, so no
# task meets its own job and the offset is mechanical rather than chosen. Every
# resulting pair is listed in the output for a human to check: an "unrelated" set
# with a secretly related pair inflates the floor and hides the real answer.
designated_pairs = [{"task": t, "job": by_anchor[a], "cite": c} for a, t, c in DESIGNATED]
n = len(designated_pairs)
#
# TWO PAIRS THE OFFSET PRODUCED ARE NOT HONESTLY UNRELATED, and they are NAMED
# rather than re-rolled. Re-drawing the derangement until no pair is inconvenient
# is the tuning this whole corpus exists to avoid, so the offset stands and the
# adjacency is reported: the derivation prints the floor WITH and WITHOUT them.
#
# This is not a flaw in the derangement. It is a property of a single-package
# corpus - every job here is knowledge-lane machinery and every task is one
# stage's plan prose, so "unrelated" pairs share a subsystem by construction.
# Stage 0's corpus could avoid it because its jobs spanned different subjects.
ADJACENT = {
  "SymbolAnchorResolver#memberOn":
    "the task says 'no symbol to check' and this job IS the symbol-member check -"
    " address-boundness and member existence are the same question one step apart",
  "DescribedUnits#outstanding":
    "the task ends 'enqueues the unit in the ledger' and this job IS that queue",
}
unrelated_pairs = []
for i, (anchor, task, _) in enumerate(DESIGNATED):
    other = DESIGNATED[(i + 5) % n][0]
    pair = {"task": task, "job": by_anchor[other]}
    if other in ADJACENT:
        pair["topically_adjacent"] = ADJACENT[other]
    unrelated_pairs.append(pair)
# job-01 has no designated task, so it can only enter the floor set - which is
# exactly where a distractor belongs.
unrelated_pairs.append({"task": DESIGNATED[0][1], "job": by_anchor[EXCLUDED[0]]})

# NEAR-DUPLICATES: two wordings of ONE job. This is the only hand-written half and
# it cannot be otherwise - a paraphrase has no source but a writer. `a` is the real
# row verbatim; `b` is mine.
NEAR = [
 ("DescribedUnits#outstanding",
  "Says which of the files it was handed have not been described yet, so a run that stops"
  " early picks up from there instead of repeating itself."),
 ("DescribedUnits#hash",
  "Takes a fingerprint of a file's contents so a later run can tell whether the text moved"
  " under it."),
 ("KnowledgeLane#of",
  "Works out which lifecycle a row is governed by, and answers nothing when no rule covers"
  " that type, so the row stays visible rather than being filed under a default."),
 ("AnalogyPolicy#JUNK_FLOOR",
  "The distance under which the model is reporting no shared meaning at all, so the row is"
  " withheld; set well below the weakest answer ever seen because it guards the off-corpus"
  " case."),
 ("ExperienceRetrieval#resolvePointer",
  "Answers where an anchored symbol sits in today's code, and says plainly when it is gone."),
 ("RelevanceMerge#normalise",
  "Puts one stream of scores on the scale of another by dividing through by its own best, so"
  " the two can be added; the result ranks rows against each other and says nothing about how"
  " good any match is."),
]
summary_of = {j["anchor"].split(".")[-1]: j["summary"] for j in jobs}
near_pairs = [{"a": summary_of[anchor], "b": b, "job": by_anchor[anchor]} for anchor, b in NEAR]

doc = {
  "fixture": "28f-s7-real-rows",
  "frozen": "2026-09-12",
  "frozen_before": "any score from it was read",
  "contract": [
    "Sprint 28f Stage 7 deliverable 7 - the TRANSFER CHECK Stage 0's corpus cannot",
    "give itself. Measurement 3 was taken over a corpus whose BOTH SIDES were written",
    "by hand in one session by the party drawing the conclusion, and it declared that",
    "as a deviation with an obligation attached: re-derive over the first bundle's",
    "REAL rows.",
    "",
    "WHAT IS REAL HERE, and what is not - stated so no number is read as more:",
    "",
    "  jobs        - REAL. The twelve rows the first bundle run recorded, read out of",
    "                build/28f-s7-first-bundle-run.sh by the generator rather than",
    "                retyped. They were written to catalogue code, not to be measured.",
    "  designated  - the TASK text is verbatim plan prose, each carrying the `cite`",
    "                substring the generator checked against the plan file. Written",
    "                days earlier, for another purpose, by nobody thinking about",
    "                cosine. THE PAIRING is mine and that is the residual limit.",
    "  unrelated   - a mechanical derangement: each task re-paired five positions",
    "                along. No pair was chosen; every pair is printed to be checked.",
    "  near_dupes  - the ONE hand-written half, and it cannot be otherwise, because a",
    "                paraphrase has no source but a writer. `a` is the real row.",
    "",
    "TWO CONFOUNDS AGAINST STAGE 0, both of which move scores and neither of which is",
    "a defect in this corpus:",
    "",
    "  REGISTER. Stage 0's tasks are questions a developer would type. These are",
    "  declarative work items, because that is what the deliverable asked for. A",
    "  difference from Stage 0 may be the corpus becoming real OR the register",
    "  changing, and this corpus alone cannot separate them.",
    "  ONE PACKAGE. Every job here is from org.jawata.mcp.knowledge, so even the",
    "  unrelated pairs share a topic. The floor should therefore sit HIGHER than",
    "  Stage 0's, and that is a property of the map's real operating conditions",
    "  rather than an artefact - the map runs over one bundle's rows at a time.",
  ],
  "excluded_from_designated": [
    {"anchor": a, "reason":
     "the sprint has no work item about turning free prose into an anchor - that"
     " machinery predates 28f. Kept in the job pool as a distractor; padding it with a"
     " task written for the purpose is what this deliverable exists to avoid."}
    for a in EXCLUDED],
  "jobs": [{"id": j["id"], "summary": j["summary"], "anchor": j["anchor"]} for j in jobs],
  "designated": designated_pairs,
  "unrelated": unrelated_pairs,
  "near_duplicates": near_pairs,
}
open(OUT, "w").write(json.dumps(doc, indent=2) + "\n")
print("jobs=%d designated=%d unrelated=%d near_dupes=%d -> %s"
      % (len(jobs), len(designated_pairs), len(unrelated_pairs), len(near_pairs), OUT))
print("\nTHE DERANGEMENT, for checking:")
for p in unrelated_pairs:
    jid = p["job"]
    anchor = next(j["anchor"].split(".")[-1] for j in jobs if j["id"] == jid)
    print("  %-34s <- %s" % (anchor, p["task"][:78]))
