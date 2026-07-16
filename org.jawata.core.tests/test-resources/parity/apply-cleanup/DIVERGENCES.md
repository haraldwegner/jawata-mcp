# apply_cleanup parity — recorded divergences (Sprint 25, spec D1a item 5)

`apply_cleanup` migrated its per-file rewrites onto JDT's clean-up engines —
`VariableDeclarationFixCore` (add_final) and `RedundantModifiersCleanUp`
(redundant_modifiers), the classes behind the IDE's Source → Clean Up. The
`SourceScan` sweep shell (honest missed-file reporting) is unchanged. Goldens
archived pre-migration (`aa32c0b`).

## Summary

| Case | Divergence | Class |
|---|---|---|
| `add-final-cleanup-targets` | **diff byte-identical**; `editCount` 3 → 12 | metadata semantics only |
| `redundant-modifiers-cleanup-targets` | **diff byte-identical**; `editCount` 5 → 10 | metadata semantics only |

## Both cases — the transformation is identical; only the count semantics changed

On the `CleanupTargets` fixture the JDT engines produce byte-for-byte the same
source changes the hand-rolled rewrites did (params/locals finalized, reassigned
`acc` untouched; implicit interface modifiers stripped). The `editCount` extra
changed meaning: the old tool counted DECLARATIONS changed (3) / modifier TOKENS
removed (5); the engines' rewrites are reported as leaf text edits (12 / 10).
Recorded; the on-disk regression assertions are unaffected.

## Coverage newly gained (engine scope, beyond the fixture)

The old add_final only finalized parameters directly owned by a method's parameter
list (its own comment admitted catch-clause and enhanced-for parameters were not
covered) — `VariableDeclarationFixCore` covers those declaration forms. The old
redundant_modifiers walked `TypeDeclaration.getTypes()` only, missing nested enums
and records in interfaces — the engine covers them.
