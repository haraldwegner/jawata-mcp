#!/usr/bin/env python3
"""Stage-0: derive the dedup operating point(s) from the 84 committed hand labels.
Reports precision/recall at every candidate threshold, both ways on borderlines,
so the R3 branch (two bands vs corrected-claim-only) is decided on numbers."""
import json

P = ("/home/harald/CursorProjects/jawata-mcp/org.jawata.mcp.tests/"
     "test-resources/embed-goldens/dedup-labels.json")
d = json.load(open(P))
pairs = d["pairs"]
print(f"pairs={len(pairs)}  result-block says: {d['result']}\n")

def sweep(borderline_as):
    """borderline_as: 'label' = trust the label, 'dup' / 'distinct' = force borderlines."""
    rows = []
    for p in pairs:
        lab = p["label"]
        if p.get("borderline") and borderline_as != "label":
            lab = "duplicate" if borderline_as == "dup" else "distinct"
        rows.append((p["score"], lab == "duplicate"))
    total_dup = sum(1 for _, is_d in rows if is_d)
    out = []
    for t in sorted({round(r[0], 4) for r in rows}, reverse=True):
        flagged = [is_d for s, is_d in rows if s >= t]
        if not flagged:
            continue
        tp = sum(1 for x in flagged if x)
        prec = tp / len(flagged)
        rec = tp / total_dup if total_dup else 0
        f1 = 2 * prec * rec / (prec + rec) if (prec + rec) else 0
        out.append((t, prec, rec, f1, len(flagged), tp))
    return out, total_dup

for mode, title in (("label", "as labelled"), ("dup", "borderlines = duplicate"),
                    ("distinct", "borderlines = distinct")):
    rows, total = sweep(mode)
    print(f"=== {title} (duplicates={total}) ===")
    # highest-recall threshold that still holds precision 1.0
    perfect = [r for r in rows if r[1] == 1.0]
    best_p1 = max(perfect, key=lambda r: r[2]) if perfect else None
    best_f1 = max(rows, key=lambda r: r[3])
    if best_p1:
        print(f"  CERTAIN band  : t>={best_p1[0]:.4f}  precision {best_p1[1]:.3f} "
              f" recall {best_p1[2]:.3f} ({best_p1[5]}/{total})  flagged {best_p1[4]}")
    print(f"  best F1       : t>={best_f1[0]:.4f}  precision {best_f1[1]:.3f} "
          f" recall {best_f1[2]:.3f} ({best_f1[5]}/{total})  flagged {best_f1[4]}  F1={best_f1[3]:.3f}")
    # candidate "possible" band: precision >= 0.8
    soft = [r for r in rows if r[1] >= 0.80]
    if soft:
        b = max(soft, key=lambda r: r[2])
        print(f"  POSSIBLE band : t>={b[0]:.4f}  precision {b[1]:.3f} "
              f" recall {b[2]:.3f} ({b[5]}/{total})  flagged {b[4]}")
    print()

print("=== precision/recall around the shipped 0.92 line (as labelled) ===")
rows, total = sweep("label")
for t, prec, rec, f1, n, tp in rows:
    if 0.84 <= t <= 0.95:
        print(f"  t>={t:.4f}  flagged {n:3d}  correct {tp:3d}  precision {prec:.3f}  recall {rec:.3f}")
