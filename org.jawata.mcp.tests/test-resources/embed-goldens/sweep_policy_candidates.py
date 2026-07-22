#!/usr/bin/env python3
"""Stage-0 candidate-rule sweep: can ANY shape rule separate real cues from nonsense?

Contract (from the signed spec + frozen accept-sets):
  - MUST speak on >= 11 of 12 calibration cues and on ALL 4 positive controls
  - SHOULD abstain on every nonsense / plausible-but-absent control
Reports the best achievable for each candidate rule family, and every rule's failure.
"""
import itertools

rows = []
with open("profiles.tsv") as f:
    hdr = f.readline().rstrip("\n").split("\t")
    for line in f:
        p = line.rstrip("\n").split("\t")
        d = dict(zip(hdr, p))
        for k in ("top1", "top2", "top3", "median", "p90", "p99", "mean", "sd",
                  "designated_score"):
            d[k] = float(d[k])
        d["designated_rank"] = int(d["designated_rank"])
        rows.append(d)

def stats(d):
    return {
        "top1":          d["top1"],
        "z":             (d["top1"] - d["mean"]) / d["sd"],
        "gap":           d["top1"] - d["top2"],
        "gap_rel":       (d["top1"] - d["top2"]) / d["top1"] if d["top1"] > 0 else 0,
        "top1_minus_med": d["top1"] - d["median"],
        "top1_over_p99": d["top1"] / d["p99"] if d["p99"] > 0 else 0,
        "top1_minus_p99": d["top1"] - d["p99"],
        "peakiness":     (d["top1"] - d["p90"]) / d["sd"],
        "top3_spread":   d["top1"] - d["top3"],
    }

for d in rows:
    d["s"] = stats(d)

calib   = [d for d in rows if d["cue_id"].startswith("cue-")]
pos     = [d for d in rows if d["cue_id"].startswith("ctl-pos")]
non     = [d for d in rows if d["cue_id"].startswith("ctl-non")]
absent  = [d for d in rows if d["cue_id"].startswith("ctl-abs")]
must_speak = calib + pos
must_abstain = non + absent

names = list(calib[0]["s"].keys())

print(f"{len(calib)} calibration + {len(pos)} positive controls must SPEAK; "
      f"{len(non)} nonsense + {len(absent)} plausible-absent must ABSTAIN\n")

print("=== per-statistic ranges (overlap = cannot separate) ===")
print(f"{'statistic':<16} {'real min':>9} {'real max':>9} {'abstain min':>12} {'abstain max':>12}  separable?")
for n in names:
    rmin = min(d["s"][n] for d in must_speak)
    rmax = max(d["s"][n] for d in must_speak)
    amin = min(d["s"][n] for d in must_abstain)
    amax = max(d["s"][n] for d in must_abstain)
    sep = "YES" if rmin > amax else "no"
    print(f"{n:<16} {rmin:9.4f} {rmax:9.4f} {amin:12.4f} {amax:12.4f}  {sep}")

print("\n=== best achievable per statistic (allowing 1 of 12 calibration misses) ===")
print(f"{'statistic':<16} {'threshold':>10} {'speak kept':>11} {'abstained':>10}")
best_overall = None
for n in names:
    cand = sorted({d["s"][n] for d in rows})
    best = None
    for t in cand:
        spoken_cal = sum(1 for d in calib if d["s"][n] >= t)
        spoken_pos = sum(1 for d in pos if d["s"][n] >= t)
        if spoken_cal >= 11 and spoken_pos == len(pos):
            abst = sum(1 for d in must_abstain if d["s"][n] < t)
            if best is None or abst > best[1]:
                best = (t, abst, spoken_cal)
    if best:
        print(f"{n:<16} {best[0]:10.4f} {best[2]:>7}/12+4 {best[1]:>7}/{len(must_abstain)}")
        if best_overall is None or best[1] > best_overall[1]:
            best_overall = (n, best[1], best[0])
    else:
        print(f"{n:<16} {'—':>10} {'cannot hold 11/12 + 4/4':>30}")

print("\n=== 2-D rules (both conditions must hold to SPEAK) ===")
found = []
for a, b in itertools.combinations(names, 2):
    ca = sorted({d["s"][a] for d in rows})
    cb = sorted({d["s"][b] for d in rows})
    best = None
    for ta in ca:
        for tb in cb:
            sc = sum(1 for d in calib if d["s"][a] >= ta and d["s"][b] >= tb)
            sp = sum(1 for d in pos if d["s"][a] >= ta and d["s"][b] >= tb)
            if sc >= 11 and sp == len(pos):
                ab = sum(1 for d in must_abstain
                         if not (d["s"][a] >= ta and d["s"][b] >= tb))
                if best is None or ab > best[0]:
                    best = (ab, ta, tb, sc)
    if best and best[0] > 0:
        found.append((best[0], a, b, best[1], best[2], best[3]))
found.sort(reverse=True)
for ab, a, b, ta, tb, sc in found[:8]:
    print(f"  {a} >= {ta:.4f} AND {b} >= {tb:.4f}  -> speak {sc}/12+4, "
          f"abstain {ab}/{len(must_abstain)}")
if not found:
    print("  none abstains on ANY control while holding 11/12 + 4/4")

print("\n=== the dominance check (why) ===")
worst_real = min(must_speak, key=lambda d: d["s"]["top1"])
print(f"weakest real cue: {worst_real['cue_id']}  " +
      "  ".join(f"{k}={v:.4f}" for k, v in worst_real["s"].items()))
for d in must_abstain:
    dominates = all(d["s"][n] >= worst_real["s"][n] for n in
                    ("top1", "z", "gap", "top1_minus_med"))
    if dominates:
        print(f"  DOMINATES it on top1+z+gap+top1-med: {d['cue_id']}  " +
              "  ".join(f"{k}={v:.4f}" for k, v in d["s"].items()))
