package com.example;

import java.io.PrintStream;

/**
 * Sprint 28d fixture — the <b>composition_over_inheritance</b> detector's PROOF
 * OF LIFE. Two classes here must be flagged, one by each arm of the rule; a
 * third extends the same concrete parent and must NOT be flagged, so a zero next
 * door in {@link InheritanceCleanTargets} is evidence of discrimination rather
 * than evidence that the detector never fires.
 *
 * <p>{@link Ledger} bequeaths SIX inheritable members — the field
 * {@code balance} plus {@code total()}, {@code credit(int)}, {@code debit(int)},
 * {@code describe()} and {@code isEmpty()}. Every number below is a fraction of
 * that six.</p>
 */
public class InheritanceTargets {
}

/** The concrete parent. Six inheritable members; nothing about it is abstract. */
class Ledger {

    protected int balance;

    int total() {
        return balance;
    }

    void credit(int amount) {
        balance += amount;
    }

    void debit(int amount) {
        balance -= amount;
    }

    String describe() {
        return "ledger(" + balance + ")";
    }

    boolean isEmpty() {
        return balance == 0;
    }
}

/**
 * FLAGGED by the no-override arm. It overrides NOTHING of Ledger, so no caller
 * holding a {@code Ledger} can observe any difference — the {@code extends} is
 * buying an implementation. (It touches 2 of 6 = 33%, above the 25% threshold,
 * so the percentage arm deliberately does NOT fire: this case isolates arm 1.)
 */
class ReportingLedger extends Ledger {

    void report(PrintStream out) {
        credit(1);
        out.println(total());
    }
}

/**
 * FLAGGED by the percentage arm. It overrides one method and touches only that
 * one member: 1 of 6 = 16%, below the 25% threshold. It has taken on the whole
 * of Ledger's surface, and its future changes, for a sixth of the benefit.
 */
class AuditLedger extends Ledger {

    @Override
    String describe() {
        return "audit:" + super.describe();
    }
}

/**
 * NOT flagged. It overrides three members and touches all six (100%) — this is
 * what genuinely participating in a hierarchy looks like, and it must survive
 * both arms.
 */
class FullLedger extends Ledger {

    @Override
    void credit(int amount) {
        super.credit(amount);
    }

    @Override
    void debit(int amount) {
        super.debit(amount);
    }

    @Override
    boolean isEmpty() {
        return balance == 0;
    }

    int doubled() {
        return total() * 2;
    }

    String label() {
        return describe();
    }
}
