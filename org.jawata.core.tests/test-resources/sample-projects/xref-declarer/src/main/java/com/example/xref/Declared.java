package com.example.xref;

/**
 * mcp#26 — this type is DECLARED here and merely RESOLVED next door.
 *
 * <p>{@code xref-consumer} names this project in a {@code kind="src" path="/xref-declarer"}
 * classpath entry, so its classpath answers yes to {@code findType("com.example.xref.Declared")}
 * while owning no line of it. A reader that iterates projects and reports the first one that
 * RESOLVES the name attributes the type to the consumer — iteration-order luck presented as
 * provenance.</p>
 */
public class Declared {

    /** Present so the type has a member; nothing here reads it. */
    public String describe() {
        return "declared in xref-declarer";
    }
}
