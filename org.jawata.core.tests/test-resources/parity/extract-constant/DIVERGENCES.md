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

## Refreshed 2026-09-04 — the indentation is now the FILE's, not JDT's default

The entry above called JDT's output "correctly indented" and its own example shows the
replaced line starting with TABS, in a fixture that uses spaces. That was accepted at the
time as the engine winning over a hand-rolled tool, and on the comparison being made it
was: JDT's placement was right where the old tool's was wrong.

The indentation itself was still wrong. A JDT refactoring reads the formatter preference
store when it is given no options, and that default is tabs — so every extract emitted
tab-indented code into space-indented files. The same defect was found in the nine
statement rules and in Decompose Conditional, which routes through Extract Method: three
appearances of one shape.

The engines take a formatter-options map on a constructor overload nobody here was using.
They are given one now, resolved by `FormatterOptions` — an explicit project formatter
config wins, and a project declaring nothing gets spaces at 4. A repository that says tabs
still gets tabs.

Golden refreshed. The change is indentation only; the placement this file documents is
unchanged.
