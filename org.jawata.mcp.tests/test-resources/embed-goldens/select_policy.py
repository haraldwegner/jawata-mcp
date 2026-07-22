#!/usr/bin/env python3
"""Stage-0 selection: fix the rule, the margin, and the count rule — with margin,
not fitted exactly to a data point."""
rows = []
with open("profiles.tsv") as f:
    hdr = f.readline().rstrip("\n").split("\t")
    for line in f:
        d = dict(zip(hdr, line.rstrip("\n").split("\t")))
        for k in ("top1", "top2", "top3", "median", "p90", "p99", "mean", "sd",
                  "designated_score"):
            d[k] = float(d[k])
        d["stand"] = d["top1"] - d["median"]
        rows.append(d)

speak = [d for d in rows if d["cue_id"].startswith(("cue-", "ctl-pos"))]
abst  = [d for d in rows if d["cue_id"].startswith(("ctl-non", "ctl-abs"))]

print("=== the separating band (statistic: top1 - median) ===")
s = sorted(rows, key=lambda d: d["stand"])
for d in s:
    kind = "SPEAK " if d["cue_id"].startswith(("cue-", "ctl-pos")) else "abstain"
    print(f"  {d['stand']:.4f}  {kind}  {d['cue_id']}")

lo = max(d["stand"] for d in abst if d["stand"] < 0.2945 or True and d["stand"] < 0.29)
kept = [d for d in speak if d["stand"] >= 0.29]
print(f"\nhighest correctly-abstaining control below the band: {lo:.4f}")
lowest_spoken = min(d["stand"] for d in speak if d["stand"] >= 0.29)
print(f"lowest real cue above the band:                       {lowest_spoken:.4f}")
T = round((lo + lowest_spoken) / 2, 3)
print(f"=> MARGIN-CENTRED THRESHOLD T = {T}  (band width {lowest_spoken - lo:.4f})")

print(f"\n=== outcome at T = {T} ===")
spoke_cal = [d["cue_id"] for d in rows if d["cue_id"].startswith("cue-") and d["stand"] >= T]
miss_cal  = [d["cue_id"] for d in rows if d["cue_id"].startswith("cue-") and d["stand"] < T]
spoke_pos = [d["cue_id"] for d in rows if d["cue_id"].startswith("ctl-pos") and d["stand"] >= T]
spoke_bad = [d["cue_id"] for d in abst if d["stand"] >= T]
print(f"calibration spoken : {len(spoke_cal)}/12   missed: {miss_cal}")
print(f"positive controls  : {len(spoke_pos)}/4")
print(f"controls SILENCED  : {len(abst) - len(spoke_bad)}/{len(abst)}   still speaks: {spoke_bad}")

print(f"\n=== count rule: how many entries clear (median + T) ===")
print(f"{'cue':<12} {'n>=T':>5}  top1/top2/top3 minus median")
for d in rows:
    n = sum(1 for k in ("top1", "top2", "top3") if d[k] - d["median"] >= T)
    mark = "" if d["cue_id"].startswith(("cue-", "ctl-pos")) else "   (should be 0)"
    print(f"  {d['cue_id']:<10} {n:>3}   "
          f"{d['top1']-d['median']:.3f} / {d['top2']-d['median']:.3f} / "
          f"{d['top3']-d['median']:.3f}{mark}")

n_multi = sum(1 for d in rows if d["cue_id"].startswith(("cue-", "ctl-pos"))
              and sum(1 for k in ("top1","top2","top3") if d[k]-d["median"] >= T) > 1)
print(f"\nreal cues that would show MORE THAN ONE analogy: {n_multi}/16"
      f"   (today's fixed cap is 2)")
