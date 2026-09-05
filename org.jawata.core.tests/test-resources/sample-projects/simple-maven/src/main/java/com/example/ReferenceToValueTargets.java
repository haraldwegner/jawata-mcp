package com.example;

/**
 * Fixtures for Fowler row 2, Change Reference to Value, as data kind=reference_to_value.
 *
 * <p>The canonical class is mutable and compared by identity, which is the pair of properties
 * the row inverts. The second already decides its own identity, which is the refusal that
 * matters most: replacing a hand-written equality would change what the class means.</p>
 */
public class ReferenceToValueTargets {

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
