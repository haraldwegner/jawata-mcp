# extract_variable parity — recorded divergences (Sprint 25, D1c)

`extract_variable` migrated from a hand-rolled string-building extractor onto JDT's
`ExtractTempRefactoring` (the IDE's Extract Local Variable), via the
`RefactoringEngine` seam. Golden archived pre-migration (`3563817`).

## Summary

| Case | Divergence | Class |
|---|---|---|
| `calculated-from-expr` | old tool mis-indented; JDT indents correctly (tab vs space) | (a) JDT fixes a bug |

## calculated-from-expr — JDT fixes the old tool's broken indentation

The OLD tool inserted the declaration at the WRONG indentation and de-indented the
replaced line:

```
                int calculated = input.length() * 2 + 10;   <- 16 spaces (double-indented)
int result = calculated;                                     <- 0 spaces (de-indented)
```

JDT inserts a correctly-indented declaration and keeps the replaced statement
aligned:

```
        int calculated = input.length() * 2 + 10;
		int result = calculated;
```

The residual difference is tabs-vs-spaces on the re-rendered line — JDT's default
formatter uses tabs where the fixture uses spaces. That is a formatter-configuration
artifact (the resident applies the project's `.settings` formatter in real use), not
a correctness difference: the applied result compiles and `ExtractVariableToolTest`
stays green. JDT wins — the old indentation was simply wrong.

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
