# inline_variable parity — recorded divergences (Sprint 25, spec D1a item 2)

`inline_variable` migrated from a hand-rolled usage-walker onto JDT's
`InlineTempRefactoring` (the IDE's Inline Local Variable), via the
`RefactoringEngine` seam. Golden archived pre-migration (`bdff274`).

## Summary

| Case | Divergence | Class |
|---|---|---|
| `trimmed-two-usages` | old tool left a blank line (with trailing spaces) where the declaration was; JDT removes the line | (a) JDT cleaner |

## trimmed-two-usages — JDT removes the declaration line, not just its text

Both tools substitute the initializer at both usages identically
(`System.out.println(input.trim())` / `...input.trim().length())`). The old tool's
`DeleteEdit` covered only the statement's characters, leaving an empty line with
trailing whitespace; JDT's rewrite removes the line. Applied result identical in
behavior; JDT's is the cleaner text.

## Defect cured (proven by regression, not a golden)

The old tool deleted the WHOLE declaration statement when inlining one fragment of
a multi-variable declaration (`int a = 1, b = 2;` → `b` lost — broken code caught
only by the compile gate; the old code even carried an apologetic response note).
JDT removes exactly the inlined fragment. Pinned by
`InlineVariableToolTest.multiVariableDeclaration_siblingSurvives` on the
`MultiVarInline` fixture. Also newly covered by the engine: name-capture
qualification (`this.x` where a local shadows) and generic type-argument
preservation — neither was checked before.
