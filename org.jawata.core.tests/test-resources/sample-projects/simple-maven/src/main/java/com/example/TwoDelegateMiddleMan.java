package com.example;

/**
 * Two shapes row 36 is CREDITED with handling and nothing exercised — a C6 audit checked.
 *
 * <p>The receiver is written {@code this.department.x()} here, not the bare
 * {@code department.x()} of {@link MiddleManPerson}. Both are the same forwarder to a
 * reader; to the parser one is a {@code FieldAccess} over a {@code ThisExpression} and the
 * other a {@code SimpleName}, and recognising only the second is how a forwarder goes
 * unseen.</p>
 *
 * <p>And the class forwards to TWO fields. That was refused outright once, on the reasoning
 * that two delegates are two middle men — which is true and is not a reason to refuse, since
 * removing one at a time is exactly what Fowler does. The caller names which with
 * {@code delegateField}.</p>
 */
public class TwoDelegateMiddleMan {

    private final Department department = new Department("Grace");
    private final Ledger ledger = new Ledger();

    public String manager() {
        return this.department.manager();
    }

    public int balance() {
        return this.ledger.balance();
    }
}

class Ledger {

    public int balance() {
        return 7;
    }
}
