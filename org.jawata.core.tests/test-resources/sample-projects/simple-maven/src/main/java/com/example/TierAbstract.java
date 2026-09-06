package com.example;

/**
 * An ABSTRACT parent that declares NO abstract method, and that is the whole point of it.
 *
 * <p>The first version declared one, and the fixture could not reach the refusal it was written
 * for: implementing an abstract method IS an override by
 * {@code IMethodBinding.overrides()}, so the override refusal — which sits earlier — answered
 * instead. That is a REACHABILITY fact about the row rather than a fixture accident, and it is
 * worth stating: a concrete class under an abstract parent that declares abstract members
 * always overrides something, so the abstract-parent refusal is reachable only where the parent
 * declares none.</p>
 */
public abstract class TierAbstract {

    private final String tag;

    protected TierAbstract() {
        this.tag = "abstract";
    }

    public String tag() {
        return tag;
    }
}
