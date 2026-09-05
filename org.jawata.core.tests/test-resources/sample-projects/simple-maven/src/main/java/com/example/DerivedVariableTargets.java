package com.example;

/**
 * Row 45 (Replace Derived Variable with Query) fixtures — the canonical case and each refusal.
 *
 * <p>A new file in an EXISTING package, for the reason the row 22, 54, 9 and 65 fixtures
 * state. Method bodies are distinct on the clone axis, and no comment quotes the output.</p>
 *
 * <p><b>No writer computes the derived value from a PARAMETER, and that is deliberate rather
 * than incidental.</b> The first version of this file assigned in a constructor —
 * {@code this.total = itemCount * unitPrice} with parameters of those names — where the
 * expression reads the PARAMETERS while the identical line in an ordinary method reads the
 * FIELDS. Text-equal, meaning-different, and it is exactly the pair a text comparison cannot
 * tell apart. {@code Quote} below keeps that shape on purpose, as the refusal.</p>
 */
public class DerivedVariableTargets {

    /** THE CANONICAL CASE: two writers, one rule, and a reader that can go stale. */
    public static class Cart {
        private int itemCount;
        private int unitPrice;
        private int total;

        public void countAt(int count) {
            this.itemCount = count;
            this.total = itemCount * unitPrice;
        }

        public void priceAt(int price) {
            this.unitPrice = price;
            this.total = itemCount * unitPrice;
        }

        public String receipt() {
            return "due " + total;
        }
    }

    /** THE PUBLIC CASE: another file reads it, so the query must keep public visibility. */
    public static class Invoice {
        private int netAmount;
        public int grossAmount;

        public void netAt(int net) {
            this.netAmount = net;
            this.grossAmount = netAmount * 120 / 100;
        }
    }

    /** REFUSAL — the writers disagree, so it is not derived from one rule. */
    public static class Report {
        private int rows;
        private int summary;

        public void rowsAt(int count) {
            this.rows = count;
            this.summary = rows * 2;
        }

        public void widen() {
            this.summary = rows * 3;
        }

        public int shown() {
            return summary;
        }
    }

    /** REFUSAL — derived from a PARAMETER, which a no-argument query cannot see. */
    public static class Quote {
        private int amount;

        public void priceFor(int units, int rate) {
            this.amount = units * rate;
        }

        public int amount() {
            return amount;
        }
    }

    /**
     * REFUSAL — the SHADOWING PAIR, and the one a text comparison cannot tell apart.
     *
     * <p>Both writers assign {@code credits * rate}. In {@code rateAt} those are FIELDS; in
     * the constructor they are its own PARAMETERS, which a no-argument query cannot see. The
     * two expressions agree textually and mean different things, so only a per-writer scan
     * catches it — checking the first writer alone would depend on which one the search
     * happened to return first.</p>
     */
    public static class Ledger {
        private int credits;
        private int rate;
        private int balance;

        public Ledger(int credits, int rate) {
            this.credits = credits;
            this.rate = rate;
            this.balance = credits * rate;
        }

        public void rateAt(int updated) {
            this.rate = updated;
            this.balance = credits * rate;
        }

        public int owed() {
            return balance;
        }
    }

    /**
     * REFUSAL — derived by CALLING something, so a query would not give the same answer.
     *
     * <p>Both writers agree textually and read only fields, so every other condition passes.
     * Upstream's circuit breaker has this shape with a clock, which is where it was found.</p>
     */
    public static class Session {
        private int startedAt;
        private int stamp;

        public void begin() {
            this.startedAt = 1;
            this.stamp = counter();
        }

        public void refresh() {
            this.stamp = counter();
        }

        public int shownStamp() {
            return stamp;
        }

        private int counter() {
            return startedAt + 1;
        }
    }

    /** REFUSAL — nothing assigns it, so it is ordinary state rather than a cached answer. */
    public static class Constants {
        private int limit = 10;

        public int limit() {
            return limit;
        }
    }
}
