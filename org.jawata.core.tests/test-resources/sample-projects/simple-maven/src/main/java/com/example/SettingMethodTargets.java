package com.example;

/**
 * Fixtures for Fowler row 37, Remove Setting Method, as data kind=remove_setting_method.
 *
 * <p>Four shapes: the canonical one this row exists for, and one for each refusal that is not
 * about the caller. The caller refusal needs a second file, because a call from another class
 * is exactly what makes it a refusal.</p>
 *
 * <p>No comment here quotes what the operation emits. A fixture that contains the string its
 * own test searches for makes the test pass on the comment, which is how a row in this stage
 * once shipped a vacuous assertion.</p>
 */
public class SettingMethodTargets {

    /**
     * The canonical shape: the constructor is the only caller, so the setter can go and the
     * field can be settled at construction.
     */
    public static class Shipment {

        private String carrier;
        private int weight;

        public Shipment(String carrier, int weight) {
            setCarrier(carrier);
            this.weight = weight;
        }

        public void setCarrier(String carrier) {
            this.carrier = carrier;
        }

        public String carrier() {
            return carrier;
        }

        public int weight() {
            return weight;
        }
    }

    /**
     * Its setter is called from another class, which is the state most setters are in and the
     * refusal a reader should expect to meet.
     */
    public static class Booking {

        private String reference;

        public Booking(String reference) {
            this.reference = reference;
        }

        public void setReference(String reference) {
            this.reference = reference;
        }

        public String reference() {
            return reference;
        }
    }

    /** Not a setting method: the body does something beyond the assignment. */
    public static class Meter {

        private int reading;
        private boolean dirty;

        public void setReading(int reading) {
            this.reading = reading;
            this.dirty = true;
        }

        public boolean dirty() {
            return dirty;
        }
    }

    /**
     * The setter has no outside caller, so it goes — but another method writes the field, so
     * the value is not settled at construction and the modifier cannot follow.
     */
    public static class Counter {

        private int hits;

        public Counter(int hits) {
            setHits(hits);
        }

        public void setHits(int hits) {
            this.hits = hits;
        }

        public void bump() {
            hits = hits + 1;
        }

        public int hits() {
            return hits;
        }
    }
}
