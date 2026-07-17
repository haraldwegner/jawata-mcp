# organize_imports parity — recorded divergences (Sprint 25, spec D1a item 3)

`organize_imports` migrated from a hand-rolled remove+sort implementation onto
JDT's `OrganizeImportsOperation` — a PUBLIC manipulation API, the engine behind the
IDE's Source → Organize Imports and jdt.ls. Golden archived pre-migration
(`bdff274`).

## Summary

| Case | Divergence | Class |
|---|---|---|
| `refactoring-target` | old: removed NOTHING (defect), re-sorted 5 imports; JDT: removes the 4 genuinely unused imports | (a) JDT cures a proven defect |

## refactoring-target — the old tool never removed an unused import

The pre-migration golden itself is the proof: `unusedImports=[]` on a fixture where
`ArrayList`, `Map`, `HashMap` and `IOException` are unused. Root cause (read in the
old source): `findReferencedTypes` visited the whole compilation unit INCLUDING the
import declarations — every import's own qualified name landed in the
referenced-types set, so `referencedTypes.contains(importName)` was always true and
the "Removes unused imports" contract silently never executed. The tool only ever
re-sorted.

JDT's operation removes all four unused imports (only the used `java.util.List`
remains), prunes unused static imports, and honors the project's import-order
configuration instead of the old hardcoded java/javax/other ordering. (The
engine's ADD-missing-imports half is NOT usable headless — see the KNOWN BUG
section below.)

## Response-shape divergence (recorded)

The old `unusedImports` list (which, per the defect, was always empty) is replaced
by honest counts from the engine: `importsAdded` / `importsRemoved`. The
`totalImports`, `hasChanges`, and `organizedImportBlock` keys are unchanged.

## Spec D1a item 3 — HALF-met: prune proven, add-missing is a KNOWN BUG (v2.14.1)

*[Correction, 2026-07-17 (external-audit F1): an earlier version of this
section claimed the full D1a-3 measure closed by a test
(`addsMissingImport_andPrunesUnusedStatic`) that does not exist — it was
written before the add path failed and never revised. This section is the
honest record.]*

**Prune-unused-static: PROVEN.** The OLD tool never pruned an unused STATIC
import; the JDT engine does. Class (1), JDT wins. Proven by
`OrganizeImportsToolTest#prunesUnusedStaticImport` against the
`ImportOrganizeTargets` fixture (an unused `import static java.lang.Math.PI`,
deliberately requiring NO added import).

**KNOWN BUG (filed v2.14.1): add-missing-import NPEs headless.** A file that
needs an import ADDED fails with an NPE deep in JDT's headless import rewrite —
`StringTokenizer(null)` on an unlocated null preference. PRE-EXISTING: fails
identically with the v2.14.1 formatter change disabled (exoneration recorded).
The failure is LOUD (the call errors before any edit; nothing is corrupted).
The null preference was not pinpointed by reading `OrganizeImportsOperation`,
`JavaPreferencesSettings`, or `CodeStyleConfiguration`; locating and seeding it
in `HeadlessJdtConfig` is the ranked follow-up. Until then the D1a-3 measure
("a file with a missing and an unused-static import comes out correct") is
HALF-met — the gap is recorded here and in the tool's own description/Javadoc
(release call: Harald, 2026-07-16; disposition in dossier-25 §C7).
