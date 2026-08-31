#!/usr/bin/env python3
"""Sprint 28d Stage 11 (D9) — the cure-exemplar density table.

Reads calibration.sh's findings TSV and produces the table the C11 exit
requires. Committed because a number nobody can regenerate is not published.

THE BASIS, stated because getting it wrong is what the first cut did:

  * The NUMERATOR is main-source only. Detectors skip test roots
    (AbstractAstDetector.scopedSourceFiles -> isTestSource), so every finding
    comes from main source.
  * The DENOMINATOR must therefore be main-source only too. The first version
    used the resident's `sourceFileCount`, which counts main AND test, and so
    published 1,957 files where 1,332 was the truth — a 47% inflated
    denominator under an explicit "main source" label, and it sat under every
    predicted count in the table.
  * File counting is RECURSIVE. Some corpus modules are themselves multi-module
    (page-object/sample-application, microservices-api-gateway/*), so their
    sources live a level deeper; a non-recursive count silently loses them.
  * The rate is taken over the corpus MINUS the cure folders, not the whole
    corpus. Including the folder under test in its own baseline drags the
    baseline toward the observation and shrinks whatever effect exists.
  * The kind -> folder map is ONE-TO-MANY: ocp, divergent_change and
    shotgun_surgery each declare THREE cure designs, so their cure-file count
    is the sum over all three folders.

Usage:  build/calibration-density.py <findings.tsv> [corpus-root]
"""
import collections
import os
import sys

tsv = sys.argv[1] if len(sys.argv) > 1 else None
fork = sys.argv[2] if len(sys.argv) > 2 else os.environ.get(
    "JAWATA_FORK", "/home/harald/CursorProjects/java-design-patterns")
if not tsv:
    sys.exit(__doc__)

# kind -> the fork folder(s) that are its DECLARED cure, read off CureCatalog.
# `singleton` is deliberately absent: its detector's subject IS that folder's
# pattern, so the folder should be FULL of them — it is not a cure pair, and
# scoring it as one produced a meaningless 182x "contradiction" in the first cut.
CURES = {
    "switch_statements": ["state"],
    "type_code": ["type-object"],
    "ocp": ["state", "command", "template-method"],
    "divergent_change": ["state", "command", "template-method"],
    "shotgun_surgery": ["state", "command", "template-method"],
    "cqs": ["command-query-responsibility-segregation"],
    "coupling": ["dependency-injection", "mediator"],
    "composition_over_inheritance": ["delegation", "strategy"],
    "encapsulation": ["private-class-data"],
}


def main_files(module):
    """Main-source .java files under a module, RECURSIVELY (nested modules)."""
    root = os.path.join(fork, module)
    n = 0
    for dirpath, _dirs, names in os.walk(root):
        if os.sep + os.path.join("src", "main", "java") + os.sep in dirpath + os.sep:
            n += sum(1 for f in names if f.endswith(".java"))
    return n


counts = collections.defaultdict(dict)
for i, line in enumerate(open(tsv)):
    if i == 0:
        continue
    mod, kind, c = line.rstrip("\n").split("\t")
    counts[kind][mod] = int(c)

modules = sorted({m for per in counts.values() for m in per})
files = {m: main_files(m) for m in modules}
total_files = sum(files.values())

print(f"corpus: {len(modules)} modules swept, {total_files} MAIN-source files")
print(f"{'kind':<30} {'total':>6} {'in cure':>8} {'cure files':>11} "
      f"{'predicted':>10}  verdict")
print("-" * 96)
for kind, folders in sorted(CURES.items()):
    per = counts.get(kind, {})
    total = sum(per.values())
    in_cure = sum(per.get(f, 0) for f in folders)
    cure_files = sum(files.get(f, 0) for f in folders)
    out_files = total_files - cure_files
    rate_out = (total - in_cure) / out_files if out_files else float("nan")
    predicted = rate_out * cure_files
    if cure_files == 0:
        verdict = "cure folder absent from the corpus"
    elif total == 0:
        verdict = "NO SIGNAL — the detector never fired anywhere"
    elif in_cure > predicted:
        # An EXCESS is informative even against a small prediction; only a ZERO
        # is uninformative there. The first cut tested underpower FIRST and so
        # labelled the one row carrying a finding "underpowered" while it
        # showed 4 observed against 0.73 predicted.
        ratio = (in_cure / cure_files) / rate_out if rate_out else float("inf")
        verdict = (f"CONTRADICTED — {in_cure} observed vs {predicted:.2f} "
                   f"predicted, {ratio:.1f}x the corpus rate")
    elif predicted < 1.0:
        verdict = (f"UNDERPOWERED — only {predicted:.2f} findings predicted "
                   f"here, so an observed {in_cure} shows nothing")
    else:
        verdict = f"CONSISTENT — {in_cure} observed vs {predicted:.2f} predicted"
    print(f"{kind:<30} {total:>6} {in_cure:>8} {cure_files:>11} "
          f"{predicted:>10.2f}  {verdict}")
