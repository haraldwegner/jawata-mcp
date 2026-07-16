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
