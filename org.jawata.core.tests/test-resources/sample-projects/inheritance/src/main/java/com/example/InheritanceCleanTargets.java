package com.example;

import java.util.ArrayList;

/**
 * Sprint 28d fixture — the <b>composition_over_inheritance</b> detector's ZERO.
 *
 * <p>Every class here extends something, and every one of them is shaped so it
 * WOULD be flagged if its exclusion were removed — that is the whole point of
 * the file. A fixture full of classes that no rule could reach proves nothing;
 * these are near misses, each held out for one named, documented reason.</p>
 */
public class InheritanceCleanTargets {
}

/** An ABSTRACT parent: five inheritable members, offered for extension. */
abstract class Shape {

    protected String name;

    abstract double area();

    String label() {
        return name;
    }

    double perimeter() {
        return 0;
    }

    boolean visible() {
        return true;
    }
}

/**
 * NOT flagged — the parent is ABSTRACT. Without that exclusion this would fire
 * on the percentage arm: it touches 1 of the 5 inherited members (20%, below
 * the 25% threshold). An abstract parent is a Template Method; inheritance is
 * the relationship it was designed for.
 */
class Dot extends Shape {

    @Override
    double area() {
        return 0;
    }
}

/** A CONCRETE, source-declared parent with four inheritable methods — and a Throwable. */
class DomainFailure extends RuntimeException {

    private final String code;

    DomainFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }

    String describe() {
        return code + ": " + getMessage();
    }

    boolean fatal() {
        return false;
    }

    int severity() {
        return 1;
    }
}

/**
 * NOT flagged — a Throwable hierarchy. Without that exclusion this would fire on
 * BOTH arms: it overrides nothing and touches 0 of DomainFailure's 4 inheritable
 * members. Subclassing an exception is the language's own mechanism for
 * distinguishing failures, and every {@code catch} clause uses it polymorphically.
 */
class MissingAccount extends DomainFailure {

    MissingAccount(String account) {
        super("MISSING", account);
    }
}

/**
 * NOT flagged — the superclass lives outside the scanned source. Without that
 * exclusion this would fire on the no-override arm. Extending a JDK or
 * dependency type is that library's protocol, not a modelling choice the reader
 * can revisit, and its surface would dominate every percentage.
 */
class Names extends ArrayList<String> {

    private static final long serialVersionUID = 1L;

    void addTwice(String name) {
        add(name);
        add(name);
    }
}

/**
 * NOT flagged HERE — {@code refused_bequest} owns it. Without that exclusion it
 * would fire on the percentage arm (it touches 1 of Ledger's 6 members, 16%),
 * and the class would be reported twice for two readings of the same override.
 * The finding that names the smaller, more actionable unit — the method — wins.
 */
class ReadOnlyLedger extends Ledger {

    @Override
    void credit(int amount) {
        throw new UnsupportedOperationException("read only");
    }
}
