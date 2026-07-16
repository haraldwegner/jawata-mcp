# extract_constant parity — recorded divergences (Sprint 25, D1c)

`extract_constant` migrated from a hand-rolled string-building extractor onto JDT's
`ExtractConstantRefactoring` (the IDE's Extract Constant), via the
`RefactoringEngine` seam. Golden archived pre-migration (`3563817`).

## Summary

| Case | Divergence | Class |
|---|---|---|
| `default-prefix-from-literal` | constant placement + tab-vs-space formatting | (a)/(b) JDT convention |

## default-prefix-from-literal — JDT's placement + formatting

Both tools produce `private static final String DEFAULT_PREFIX = "PREFIX_";` and
rewrite the use site to `DEFAULT_PREFIX`. They differ in:

- **Placement:** the old tool inserted the constant after the last existing constant
  (`MAX_SIZE`); JDT computes its own declaration location
  (`computeConstantDeclarationLocation`) and, for a dependency-free literal, places it
  at the top of the type body. Both compile; JDT's placement is its deterministic
  convention.
- **Formatting:** JDT's default formatter re-renders an adjacent line with a tab
  where the fixture uses spaces (see the extract-variable note) — a
  formatter-configuration artifact, not a correctness difference.

Applied result compiles and `ExtractConstantToolTest` stays green (`static final` +
`DEFAULT_PREFIX =` on disk). JDT wins — its placement respects field-ordering rules
the string-builder ignored.
