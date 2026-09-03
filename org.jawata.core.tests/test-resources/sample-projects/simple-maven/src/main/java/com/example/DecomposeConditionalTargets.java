package com.example;

import java.time.LocalDate;

/**
 * Fixture for Decompose Conditional (Sprint 28d-rescue, row 8).
 *
 * <p>{@code computeCharge} is Fowler's own example. Everything below it is a shape the
 * operation must refuse, and each refusal is a way the rewrite would otherwise say
 * something false about the code.</p>
 */
public class DecomposeConditionalTargets {

    private static final LocalDate SUMMER_START = LocalDate.of(2026, 6, 1);
    private static final LocalDate SUMMER_END = LocalDate.of(2026, 9, 1);

    private final double quantity = 10;
    private final double winterRate = 2;
    private final double winterServiceCharge = 5;
    private final double summerRate = 1;

    private double charge;
    private int cached;

    /** Fowler's example: a compound test and a branch on each side. */
    public void computeCharge(LocalDate date) {
        if (date.isBefore(SUMMER_START) || date.isAfter(SUMMER_END)) {
            charge = quantity * winterRate + winterServiceCharge;
        } else {
            charge = quantity * summerRate;
        }
    }

    /** An else-if chain: one decision with three arms, not a two-sided conditional. */
    public String band(int n) {
        if (n < 0) {
            return "negative";
        } else if (n == 0) {
            return "zero";
        } else {
            return "positive";
        }
    }

    /** The condition writes. Extracting it would move the write onto a parameter. */
    public boolean assigningCondition() {
        if ((cached = (int) charge) > 0) {
            charge = 1;
        } else {
            charge = 2;
        }
        return cached > 0;
    }

    /**
     * TWO top-level conditionals. A symbol names a method, not a line, so nothing in
     * `symbol=...#twoDecisions` says which of these was meant — and picking would
     * decompose a conditional the caller did not name.
     */
    public void twoDecisions(int n) {
        if (n > 0) {
            charge = 10;
        } else {
            charge = 20;
        }
        if (n > 100) {
            cached = 1;
        } else {
            cached = 2;
        }
    }

    /** No conditional at all — a symbol naming this has nothing to find. */
    public double flatRate() {
        return quantity * summerRate;
    }

    /** No else branch at all, so naming one is asking for something that is not there. */
    public void noElse(boolean flag) {
        if (flag) {
            charge = 3;
        }
    }
}
