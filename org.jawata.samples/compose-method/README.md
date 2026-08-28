---
title: "Compose Method in Java: A Method That Reads Like Its Own Summary"
shortTitle: Compose Method
description: "Learn how Compose Method reshapes a long Java method into a short sequence of intention-revealing calls, each one level of abstraction below the caller, so the entry point reads as a summary of what it does rather than as the mechanics of how."
category: Refactoring
language: en
tag:
  - Code simplification
  - Readability
  - Decomposition
  - Extract Method
---

## Intent of Compose Method

Compose Method reshapes a long method into a short sequence of intention-revealing calls, each at one level of abstraction below the caller. The work is not made cleverer, only separated: what the method DOES stays in the entry point, and HOW each step is done moves one level down.

## Detailed Explanation of Compose Method with Real-World Examples

Real-world example

> A recipe's opening lines read "make the pastry, make the filling, assemble, bake." Not one of those is a cooking instruction — they are the names of instructions, and each has its own paragraph further down. A cook who wants the whole shape reads four lines; a cook who wants to know how much butter reads one paragraph. The recipe that instead interleaves creaming butter, chopping onions and preheating the oven in one column is the same recipe and a worse one, because there is no altitude at which you can see only the shape.

In plain words

> Make the top of the method a table of contents. Every line in it should be a name for a step, not a step.

The literature

> Kent Beck names the composed result in *Smalltalk Best Practice Patterns*, and Martin Fowler's *Refactoring* supplies the moves it is built from — Extract Method above all, with Replace Temp with Query and Introduce Parameter Object where locals are carrying the state between steps. The distinguishing test is not method length: it is whether one method mixes levels, so that a reader must hold the mechanics of one job in mind while trying to follow another.

## Programmatic Example of Compose Method in Java

The specimen is a report builder that selects lines, totals them, formats currency and renders a document.

**Before** — one method, four jobs, one indentation level. The parsing rules, the arithmetic and the string building all sit at the same depth as the policy decisions, so nothing tells a reader where one job ends:

```java
public static String report(List<String> lines, double taxRate) {
    StringBuilder out = new StringBuilder();
    out.append("ORDER REPORT\n");
    double total = 0;
    int counted = 0;
    for (String line : lines) {
        if (line == null || line.isBlank()) { continue; }
        int sep = line.lastIndexOf(':');
        if (sep < 0) { continue; }
        String name = line.substring(0, sep).trim();
        String amount = line.substring(sep + 1).trim();
        double value;
        try {
            value = Double.parseDouble(amount);
        } catch (NumberFormatException e) { continue; }
        if (value <= 0) { continue; }
        total += value;
        counted++;
        out.append("  ").append(name).append(" .......... ")
            .append(String.format("%.2f", value)).append('\n');
    }
    // ... tax, and five more append calls rendering the summary block
}
```

**After** — the entry point is the summary. Each name in it is a method one level below:

```java
public static String report(List<String> lines, double taxRate) {
    List<Item> items = parse(lines);
    double net = total(items);
    return render(items, net, net * taxRate);
}
```

Two things carried the transformation, and neither is Extract Method by itself.

**A name for what the loop was juggling.** The `before` loop kept `name` and `amount` as locals that only made sense together. Naming that pair is what lets `parse` return something and `total` accept something:

```java
private record Item(String name, double value) { }
```

**The skip conditions gathered into one decision.** Four `continue` statements — blank line, no separator, unparseable amount, non-positive value — were four exits scattered through the loop body. As one method they become one question, *is this line an item?*, answered in one place:

```java
private static Item parseLine(String line) {
    if (line == null || line.isBlank()) { return null; }
    int sep = line.lastIndexOf(':');
    if (sep < 0) { return null; }
    try {
        double value = Double.parseDouble(line.substring(sep + 1).trim());
        return value > 0 ? new Item(line.substring(0, sep).trim(), value) : null;
    } catch (NumberFormatException notAnAmount) {
        return null;
    }
}
```

`total` and `render` follow the same rule: totalling is a function of the parsed items, rendering is a function of the totals. Neither knows how a line is parsed.

The two files, both compiled by the build and both absent from the shipped product:

* Before: `org.jawata.samples.composemethod.OrderReportBefore`
* After: `org.jawata.samples.composemethod.OrderReportAfter`

The `Before` file is a specimen, not a defect awaiting repair — its faults are the point.

## When to Use Compose Method in Java

* One method interleaves several jobs at different levels of abstraction, so a reader must hold parsing, arithmetic and formatting in mind at once.
* The jobs inside a method change at different times, and a change to one risks the others because they share a scope.
* Locals are carrying state between phases of a method, and naming what they hold together would give a type its own reason to exist.
* A method can only be verified by reading it end to end, because no part of it can be understood alone.

## Real-World Applications of Compose Method in Java

* Report and document builders that select, aggregate and format in one pass
* Request handlers that validate, dispatch and serialise inside one entry point
* Batch jobs whose `run` method reads a source, transforms records and writes a sink
* Parsers where the tokenising rules have grown into the loop that consumes them

## Benefits and Trade-offs of Compose Method

Benefits:

* The entry point states intent, so the shape of the operation is readable without reading the mechanics.
* Each extracted step is independently testable and independently changeable.
* Jobs that change at different times stop sharing a scope: a new output format can no longer break the parsing rules.
* Naming the steps often surfaces a missing type — here, `Item`.

Trade-offs:

* More members. Five where there was one, and each one costs a name.
* Chasing a single detail now means following a call instead of scrolling.
* Applied to a method that is long but does exactly ONE thing, it buys nothing and adds indirection — length is not the trigger, mixed levels are.
* Over-applied, it produces a cloud of one-line methods that is harder to read than the original.

## Related Refactorings and Patterns

* Extract Method — the constituent move; Compose Method is the goal that decides where to cut.
* Replace Temp with Query — removes the locals that otherwise have to be threaded through the new methods.
* Introduce Parameter Object — the move that produced `Item` here.
* [Template Method](https://java-design-patterns.com/patterns/template-method/) — the pattern form of the same shape, when the named steps must also vary by subtype.

## References and Credits

* Kent Beck, *Smalltalk Best Practice Patterns*
* Martin Fowler, *Refactoring: Improving the Design of Existing Code*
* [Compose Method (refactoring.com)](https://refactoring.com/catalog/)
