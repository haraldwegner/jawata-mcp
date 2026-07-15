# rename_symbol parity — recorded divergences (Sprint 25, D1a)

The `rename_symbol` tool migrated from a hand-rolled AST binding-key walker onto
JDT's own rename processors (`RenameTypeProcessor` / `RenameFieldProcessor` /
`RenameVirtualMethodProcessor` / `RenameNonVirtualMethodProcessor` /
`RenameLocalVariableProcessor` / `RenameEnumConstProcessor` /
`RenameTypeParameterProcessor`) driven through the `RefactoringEngine` seam.

`RenameParityTest` staged the OLD tool over four cases and archived the results as
goldens **before** the migration (commit `cc5868d`). After migration the goldens
were re-recorded from the JDT tool; the `git diff cc5868d -- .../parity/rename/` is
the parity evidence. Every difference is one of the three sanctioned classes and is
recorded here — none is hidden.

## Summary

| Case | Element | Divergence | Class |
|---|---|---|---|
| `field-username-to-userfullname` | Field | **none** — byte-identical | — |
| `local-to-renamedlocal` | LocalVariable | **none** — byte-identical | — |
| `method-add-to-sum` | Method (cross-file ×4) | one block-boundary blank line | (b) formatting |
| `type-helloworld-to-greeting` | Class (+ file rename) | preview omits the declaration/constructor hunks | (a)/structural |

The applied behaviour of every case is preserved and independently verified by
`RenameSymbolToolTest` (renameField / renameLocalVariable / renameMethod /
renameClass_alsoRewritesConstructors / stagedMode — all green after migration).

## field / local — no divergence

Byte-identical old-vs-JDT. The JDT field and local processors resolve exactly the
same occurrences the hand-rolled walker did (declaration + every reference,
including the getter/setter bodies for the field). True parity.

## method-add-to-sum — class (b), cosmetic

`add → sum` rewrites the same four files (Calculator declaration, SearchPatterns ×2,
UserService, SampleTest) with the same edits in both tools. The only difference is a
single empty line at one file-block boundary in the concatenated unified diff — a
rendering artifact, not a change in what is rewritten. Accepted; no behavioural
divergence.

## type-helloworld-to-greeting — structural (JDT wins), applied result complete

JDT models a **primary-type** rename as a **compilation-unit rename**: renaming the
type `HelloWorld` (the primary type of `HelloWorld.java`) renames the *file* to
`Greeting.java`, and the `class HelloWorld → class Greeting` + constructor renames
are carried by that compilation-unit rename as a Java-model operation — they produce
**no LTK text edits**. The change tree (verified live) is:

```
DynamicValidationRefactoringChange "Rename Type"
  CompilationUnitChange "HelloWorld.java"  MultiTextEdit(2)   <- the main() references
  RenameCompilationUnitChange "HelloWorld.java -> Greeting.java"  <- decl + constructors + file
```

`ChangeEngine.previewDiff` renders text edits, so the **staged** diff shows the
reference updates plus the `fileRenamed: HelloWorld.java -> Greeting.java` header
field — the declaration/constructor rename is conveyed by that marker, not by a diff
hunk. The **applied** result is complete and correct: `class Greeting`,
`public Greeting()`, `public Greeting(String)` on disk in `Greeting.java`, asserted
by `RenameSymbolToolTest.renameClass_alsoRewritesConstructors` (green). The default
`auto_apply: true` path is unaffected.

This is JDT-wins (its model is the correct one — a public type and its file rename
together), recorded here. A follow-up could synthesize the declaration hunk into the
staged preview for primary-type renames; noted as a dogfood candidate for Stage 7,
not a correctness gap (the applied change is complete and the staged response names
the rename via `symbolKind` + `fileRenamed`).
