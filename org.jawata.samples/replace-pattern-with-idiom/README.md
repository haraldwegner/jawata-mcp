---
title: "Replace Pattern with Idiom in Java: Keep the Design, Drop the Ceremony"
shortTitle: Replace Pattern with Idiom
description: "Learn how Replace Pattern with Idiom removes the boilerplate from a pattern the language has absorbed. A single-method interface with anonymous implementations is a lambda spelled the long way; the Strategy survives, the ceremony does not."
category: Refactoring
language: en
tag:
  - Code simplification
  - Idiom
  - Functional decomposition
  - Language migration
---

## Intent of Replace Pattern with Idiom

When the language absorbs a pattern, keep the design and drop the ceremony. The pattern was not a mistake — it was the only way to say something the language could not say directly, and once the language can say it, the boilerplate has stopped carrying meaning and started hiding it.

## Detailed Explanation of Replace Pattern with Idiom with Real-World Examples

Real-world example

> A form letter written before mail merge existed carried its own instructions: *insert the recipient's name here, insert their address here, then the body follows.* The instructions were necessary, and they were also longer than the parts that actually differed between letters. Once the tool can do the merge, keeping the instructions in the letter does not make it more careful — it makes the one line that actually changes harder to find. The letter's design is unchanged; only its spelling is.

In plain words

> Do not delete the pattern. Delete the scaffolding the language used to require, and check that what remains still says what the pattern said.

The direction this runs

> Most refactoring-to-patterns moves run TOWARD a pattern when complexity has earned it. This one runs away from one, and it is not a criticism of the pattern — Strategy is exactly right here and survives the transformation intact. What is removed is the five lines of anonymous-class ceremony that Java once required to express a strategy, and that Java 8 no longer requires. The judgement it demands is knowing which properties the ceremony was carrying, because some of them must be preserved by other means.

## Programmatic Example of Replace Pattern with Idiom in Java

The specimen is a set of retry backoff policies, expressed as a Strategy.

**Before** — a single-abstract-method interface, and three implementations in which five lines of ceremony surround one expression:

```java
public interface Backoff {
    long delayMillis(int attempt);
}

public static final Backoff FIXED = new Backoff() {
    @Override
    public long delayMillis(int attempt) {
        return 100L;
    }
};

public static final Backoff LINEAR = new Backoff() {
    @Override
    public long delayMillis(int attempt) {
        return 100L * attempt;
    }
};
```

The same ceremony reappears at the call site, where a `Supplier` is written as a class in order to return a constant:

```java
public static long totalDelayOfDefault(int attempts) {
    Supplier<Backoff> chosen = new Supplier<Backoff>() {
        @Override
        public Backoff get() {
            return EXPONENTIAL;
        }
    };
    return totalDelay(chosen.get(), attempts);
}
```

**After** — each policy is the expression that distinguishes it, and the supplier collapses to the value it was wrapping:

```java
@FunctionalInterface
public interface Backoff {
    long delayMillis(int attempt);
}

public static final Backoff FIXED = attempt -> 100L;

public static final Backoff LINEAR = attempt -> 100L * attempt;

public static long totalDelayOfDefault(int attempts) {
    return totalDelay(EXPONENTIAL, attempts);
}
```

Two decisions in that transformation are the whole judgement, and a mechanical conversion gets both wrong.

**`@FunctionalInterface` is not decoration.** The `before` interface had exactly one abstract method by accident of how it was written; nothing defended that. Once implementations are lambdas, a second abstract method added later breaks every one of them — so the property the anonymous classes made obvious has to be made explicit, and the annotation hands it to the compiler.

**Not everything becomes a lambda.** A lambda has no name and nowhere to hang a doc comment. `EXPONENTIAL` caps its doubling, and that cap is a decision worth a sentence, so it stays a named method reached by a method reference:

```java
/** Doubling, capped at 2^10 so a long retry loop cannot run away. */
private static long exponentialDelay(int attempt) {
    return 100L * (1L << Math.min(attempt, 10));
}

public static final Backoff EXPONENTIAL = RetryPolicyAfter::exponentialDelay;
```

The consumer, `totalDelay`, is byte-for-byte unchanged. It never depended on how the policies were spelled — which is the evidence that the design was already right and only its expression was dated.

The two files, both compiled by the build and both absent from the shipped product:

* Before: `org.jawata.samples.replacepatternwithidiom.RetryPolicyBefore`
* After: `org.jawata.samples.replacepatternwithidiom.RetryPolicyAfter`

The `Before` file is a specimen, not a defect awaiting repair.

## When to Use Replace Pattern with Idiom in Java

* A strategy or callback is expressed as anonymous classes implementing a single-method interface, and the ceremony is longer than the behaviour it wraps.
* A pattern was adopted to work around a language limitation that a later language version removed.
* The boilerplate is uniform enough that a reader skims it, which means the one line that differs between implementations is being skimmed with it.
* A `Runnable`, `Supplier`, `Comparator` or `Callable` is written as a class body with a single `return`.

## Real-World Applications of Replace Pattern with Idiom in Java

* Comparators written as anonymous classes, replaced by `Comparator.comparing`
* Listener and callback registrations across UI and event frameworks
* Strategy families whose members are stateless and single-expression
* Codebases raising their language level, where the pattern was correct for the old target

## Benefits and Trade-offs of Replace Pattern with Idiom

Benefits:

* The distinguishing expression becomes the whole implementation, so differences are visible instead of buried.
* `@FunctionalInterface` converts a convention into a compiler-enforced property.
* Less code with the same design — the collaboration and its extension points are untouched.
* Call sites shrink alongside the implementations.

Trade-offs:

* A lambda has no name and no place for a doc comment; a policy whose rationale matters needs a named method.
* Stack traces through lambdas are harder to read than traces through named classes.
* The transformation REFUSES a strategy that carries state or implements more than one method — those keep their class, and forcing them is a regression.
* It raises the language level required to build the module.

## Related Refactorings and Patterns

* [Strategy](https://java-design-patterns.com/patterns/strategy/) — the pattern being re-spelled, not removed.
* Inline Class — the same instinct one level up, when a class has stopped carrying its weight.
* Replace Conditional with Polymorphism — the opposite direction, toward a pattern, when the behaviour genuinely varies by type.

## References and Credits

* Joshua Kerievsky, *Refactoring to Patterns*
* Martin Fowler, *Refactoring: Improving the Design of Existing Code*
* [Java Language Specification: Functional Interfaces](https://docs.oracle.com/javase/specs/jls/se21/html/jls-9.html#jls-9.8)
