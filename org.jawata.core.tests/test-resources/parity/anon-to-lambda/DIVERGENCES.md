# convert_anonymous_to_lambda parity — recorded divergences (Sprint 25, spec D1a item 4)

`convert_anonymous_to_lambda` migrated from a hand-rolled string-building converter
onto JDT's `LambdaExpressionsFixCore` (the IDE's convert-to-lambda clean-up engine,
headless by design — jdt.ls runs the same class). Goldens archived pre-migration
(`aa32c0b`).

## Summary

| Case | Divergence | Class |
|---|---|---|
| `runnable-simple` | **none** — byte-identical | — |
| `comparator-two-params` | lambda identical; JDT additionally removes the now-unused `import java.util.Comparator;` | (a) JDT more thorough |

## runnable-simple — no divergence

`() -> System.out.println(...)` identical old-vs-JDT. True parity.

## comparator-two-params — JDT cleans up the import the conversion orphans

Both tools produce the same lambda (`(a, b) -> a.length() - b.length()`). After the
conversion the type `Comparator` is no longer referenced; JDT removes the orphaned
import in the same change, the old tool left it dangling. JDT wins.

## Behaviour newly covered (engine eligibility, not goldens)

The old tool checked only `this`-usage; fields declared inside the anonymous class
were silently IGNORED (the conversion dropped them — broken code caught only by the
compile gate). The engine's `isFunctionalAnonymous` + finders refuse fields,
this/super references, recursion, and annotation-losing conversions upfront — the
existing rejection tests (this-reference, multiple methods, non-functional
interface) pass unchanged against the engine. Response-shape divergence, recorded:
the old synthesized `lambdaExpression` string is gone (JDT performs the real
rewrite); tests assert the diff/on-disk lambda — the same pattern as
inline_method's dropped `inlinedCode`.
