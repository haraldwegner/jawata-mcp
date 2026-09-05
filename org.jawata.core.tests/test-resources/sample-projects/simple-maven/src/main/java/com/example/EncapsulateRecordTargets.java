package com.example;

/**
 * Fixtures for Fowler row 10, Encapsulate Record, as data kind=encapsulate_record.
 *
 * <p>The canonical class hands two fields out by name and also declares a constant, so one
 * run exercises the encapsulation and the skip in the same call. The second class is already
 * in the state the operation produces, which is the refusal.</p>
 *
 * <p>The nested shape is deliberate: the composed rows re-resolve their target between steps,
 * and a member class is where a name-keyed lookup goes wrong.</p>
 */
public class EncapsulateRecordTargets {

    /** Two public fields and a constant beside them. */
    public static class Coordinate {

        public static final String DATUM = "WGS84";

        public double latitude;
        public double longitude;

        public Coordinate(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public double distanceFromOrigin() {
            return Math.sqrt(latitude * latitude + longitude * longitude);
        }
    }

    /** Already owns its state, so there is nothing here for this operation to do. */
    public static class Sealed {

        private int reading;

        public Sealed(int reading) {
            this.reading = reading;
        }

        public int getReading() {
            return reading;
        }
    }
}
