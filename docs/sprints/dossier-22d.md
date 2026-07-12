# Sprint 22d dossier — scratch (moves to docs/sprints/dossier-22d.md on the spike branch)

## C0 — Tycho baselines (recorded 2026-07-12, main @ 093e064 = v2.8.1)

| Baseline | Expected | Actual | Provenance |
|---|---|---|---|
| Full `mvn clean verify` wall-clock | ~28:46 | **28:46** | local run 2026-07-11 (task b36ssvs73), same machine, v2.8.1 tree content (pre-bump; code identical) |
| Suite | 1179/0/0 (5 skip) | **1179/0/0 (5 skip)** | same run; surefire aggregate 147+1032 |
| Dist: release asset (linux-x64 tar.gz) | — | **53,412,605 B (~51 MB)** | gh release view v2.8.1 |
| Dist: local product archive (linux x86_64) | — | **51,569,779 B** | org.jawata.product/target/products/ |
| Dist: unpacked product tree | — | **60 MB** | du -sh linux/gtk/x86_64/jawata |
| tools/list | 43 | **43** (sorted snapshot: scratchpad/22d/tools-baseline.txt) | live v2.8.1 resident, 8800, fresh |
| Sentinel `JreTypeResolutionTest` | 4/4 | **4/4/0/0** | local surefire 2026-07-11 23:14 (v2.8.1 verify) + CI release run 29168787387 green |

Branch: `spike/22d-de-tycho` created from main @ 093e064. Main untouched.
