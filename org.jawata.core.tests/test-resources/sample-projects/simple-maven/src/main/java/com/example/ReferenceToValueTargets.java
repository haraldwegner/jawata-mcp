package com.example;

/**
 * Fixtures for Fowler row 2, Change Reference to Value, as data kind=reference_to_value.
 *
 * <p>The canonical class is mutable and compared by identity, which is the pair of properties
 * the row inverts. The second already decides its own identity, which is the refusal that
 * matters most: replacing a hand-written equality would change what the class means.</p>
 */
public class ReferenceToValueTargets {

    /**
     * A DECOY, and it is declared FIRST on purpose.
     *
     * <p>Its member class has the same simple name as {@link Money}, which Java allows because
     * a simple name is unique inside a scope and not inside a file. A recipe that carries its
     * target between steps as a simple name and re-resolves it by searching the file
     * depth-first finds whichever comes first — so with this class ABOVE {@code Money}, every
     * step of a request about {@code Money} lands here instead. Declared below it, the search
     * is accidentally right and the defect is invisible, which is the state the fixture was in
     * when the architect found the bug by reading rather than by running.</p>
     */
    public static class Legacy {

        /**
         * Same simple name as its sibling one level up, and nothing else in common.
         *
         * <p>Package-private ON PURPOSE: the model search this decoy exists to catch does not
         * filter by visibility, so it still finds this one first — while the tests anchor on a
         * declaration that is public, which now matches only the real target. A decoy that
         * also matched the anchor would move the tests rather than the defect. THIS COMMENT
         * MUST NOT SPELL THE ANCHOR EITHER: the first version did, the line search matched the
         * comment, and the caret landed on this class — the fixture rule earned at row 54,
         * broken in the very sentence explaining the fixture.</p>
         */
        static class Money {

            private String currency;
            private long amount;

            Money(String currency, long amount) {
                setCurrency(currency);
                setAmount(amount);
            }

            public void setCurrency(String currency) {
                this.currency = currency;
            }

            public void setAmount(long amount) {
                this.amount = amount;
            }

            public String currency() {
                return currency;
            }

            public long amount() {
                return amount;
            }
        }
    }

    /**
     * Two 1-arg setters of ONE name, on different fields. Both are plain setting methods, so
     * the row must remove both.
     *
     * <p><b>This is a CASE, not a control, and the first version of this comment claimed
     * otherwise.</b> It said a name key would resolve both steps to whichever overload comes
     * first, doing one field twice and the other never. A mutation restoring the name lookup
     * left all seven tests GREEN, and the reason is that each step REMOVES its setter: step one
     * takes the first {@code setPlace}, and by step two only the other one is left, so the
     * ambiguous key self-corrects here. The overload hazard is real about the KEY and benign
     * about this row's own sequence — which is a different sentence from the one that was
     * written, and the difference is the whole of what a mutation is for.</p>
     */
    public static class Seat {

        private String label;
        private int number;

        public Seat(String label, int number) {
            setPlace(label);
            setPlace(number);
        }

        public void setPlace(String label) {
            this.label = label;
        }

        public void setPlace(int number) {
            this.number = number;
        }

        public String label() {
            return label;
        }

        public int number() {
            return number;
        }
    }

    /** Two setters, both called from the constructor, and no identity of its own. */
    public static class Money {

        private String currency;
        private long amount;

        public Money(String currency, long amount) {
            setCurrency(currency);
            setAmount(amount);
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public void setAmount(long amount) {
            this.amount = amount;
        }

        public String currency() {
            return currency;
        }

        public long amount() {
            return amount;
        }
    }

    /** Somebody already chose how two of these compare. */
    public static class Ticket {

        private final String serial;

        public Ticket(String serial) {
            this.serial = serial;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Ticket && ((Ticket) other).serial.equals(serial);
        }

        @Override
        public int hashCode() {
            return serial.hashCode();
        }
    }
}
