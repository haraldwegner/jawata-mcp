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
remains), prunes unused static imports, ADDS missing imports for unresolved names
(ambiguous candidates skipped headless — the jdt.ls pattern), and honors the
project's import-order configuration instead of the old hardcoded
java/javax/other ordering.

## Response-shape divergence (recorded)

The old `unusedImports` list (which, per the defect, was always empty) is replaced
by honest counts from the engine: `importsAdded` / `importsRemoved`. The
`totalImports`, `hasChanges`, and `organizedImportBlock` keys are unchanged.
