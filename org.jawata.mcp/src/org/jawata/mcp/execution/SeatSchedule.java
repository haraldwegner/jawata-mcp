package org.jawata.mcp.execution;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A five-field cron expression — {@code m h dom mon dow} — over the subset the seats
 * need: {@code *}, a plain number, and {@code *}{@code /n} steps (Sprint 28g S9a M5b).
 *
 * <h2>Anything it cannot read is LOUD</h2>
 *
 * <p>{@link #parse} throws. That is the whole point of this type: the Rust reference
 * promises exactly this in its own doc — <em>"anything else is a loud parse error, never
 * a silently-ignored schedule"</em> — and then its {@code due_seats} converts the error
 * back into "no schedule" with an {@code .ok()}, so a typo'd cron disables a seat and
 * says nothing. {@link SeatScheduler} is where that promise is kept rather than broken;
 * this type only has to refuse.</p>
 *
 * <h2>THREE refusals the reference does NOT make, and the third is not like the others</h2>
 *
 * <p>Two of them are one defect wearing two hats. The reference parses {@code *}{@code /0}
 * and every out-of-range number happily, and both then never match — a schedule that cannot
 * fire and does not say so. {@code *}{@code /0} is a step of nothing, and minute 99 is not a
 * minute. Both are refused HERE, at parse, where a human is still holding the expression,
 * and refusing them changes the outcome of nothing the reference would ever have fired on.</p>
 *
 * <p><strong>The third one DOES diverge on an expression the reference fires, and that is
 * deliberate rather than an oversight.</strong> A step larger than its field's maximum —
 * {@code *}{@code /90} in the minute field — the reference accepts AND fires, at value 0
 * only, because {@code v % 90 == 0} is true when v is 0. So {@code *}{@code /90} silently
 * means "every hour, on the hour", which is what {@code 0 * * * *} says plainly. Nobody
 * writes the first intending the second: they mean every ninety minutes, which five cron
 * fields cannot express at all. It is refused, with a message naming what it WOULD have
 * done, because firing on a reading its author did not intend is the same harm as not
 * firing — and the author is still there to be told.</p>
 *
 * <h2>Day-of-week is cron's numbering, not Java's</h2>
 *
 * <p>Cron counts Sunday as 0; {@link DayOfWeek} counts Monday as 1 and Sunday as 7.
 * {@link #cronDayOfWeek} is the one place that converts, so no caller has to remember
 * which numbering it is holding.</p>
 */
public record SeatSchedule(Field minute, Field hour, Field dayOfMonth, Field month,
                           Field dayOfWeek) {

    /** One field of the expression. */
    public record Field(Kind kind, int value) {

        public enum Kind { ANY, EXACT, STEP }

        public Field {
            Objects.requireNonNull(kind, "a field with no kind matches nothing and says nothing");
            if (kind == Kind.STEP && value <= 0) {
                throw new IllegalArgumentException(
                    "a step of " + value + " can never fire; */n needs a positive n");
            }
        }

        public static final Field ANY = new Field(Kind.ANY, 0);

        public static Field exact(int value) {
            return new Field(Kind.EXACT, value);
        }

        public static Field step(int every) {
            return new Field(Kind.STEP, every);
        }

        /** Does this field admit the given value? */
        public boolean matches(int candidate) {
            return switch (kind) {
                case ANY -> true;
                // Belt below this type's own abstraction: the constructor refuses a
                // non-positive step, and this record is public and constructible.
                case STEP -> value > 0 && candidate % value == 0;
                case EXACT -> value == candidate;
            };
        }
    }

    /** What each position of the expression is allowed to hold, in the order cron writes them. */
    private enum Position {
        MINUTE("minute", 0, 59),
        HOUR("hour", 0, 23),
        DAY_OF_MONTH("day of month", 1, 31),
        MONTH("month", 1, 12),
        DAY_OF_WEEK("day of week", 0, 6);

        private final String label;
        private final int low;
        private final int high;

        Position(String label, int low, int high) {
            this.label = label;
            this.low = low;
            this.high = high;
        }
    }

    /**
     * Read an expression, or throw saying what is wrong with it.
     *
     * @throws IllegalArgumentException on anything this subset cannot read — the wrong
     *     number of fields, a value that is not a number, a step of zero or less, or a
     *     number outside the range its position allows
     */
    public static SeatSchedule parse(String expression) {
        Objects.requireNonNull(expression, "there is no schedule to parse");
        String[] parts = expression.strip().split("\\s+");
        if (parts.length != Position.values().length || expression.isBlank()) {
            throw new IllegalArgumentException("schedule '" + expression + "' must have "
                + Position.values().length + " fields (m h dom mon dow), got "
                + (expression.isBlank() ? 0 : parts.length));
        }
        Field[] fields = new Field[parts.length];
        for (int i = 0; i < parts.length; i++) {
            fields[i] = field(parts[i], Position.values()[i], expression);
        }
        return new SeatSchedule(fields[0], fields[1], fields[2], fields[3], fields[4]);
    }

    private static Field field(String part, Position at, String expression) {
        if ("*".equals(part)) {
            return Field.ANY;
        }
        if (part.startsWith("*/")) {
            int every = number(part.substring(2), part, at, expression);
            if (every <= 0) {
                throw new IllegalArgumentException(badField(part, at, expression)
                    + ": a step of " + every + " can never fire");
            }
            if (every > at.high) {
                throw new IllegalArgumentException(badField(part, at, expression)
                    + ": a step of " + every + " is larger than the largest " + at.label
                    + " (" + at.high + "), so it fires only at " + at.label + " 0");
            }
            return Field.step(every);
        }
        int value = number(part, part, at, expression);
        if (value < at.low || value > at.high) {
            throw new IllegalArgumentException(badField(part, at, expression) + ": the "
                + at.label + " is " + at.low + "-" + at.high + ", so " + value
                + " can never come round");
        }
        return Field.exact(value);
    }

    private static int number(String text, String part, Position at, String expression) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(badField(part, at, expression)
                + ": '" + text + "' is not a number. This subset reads *, a number, or */n");
        }
    }

    private static String badField(String part, Position at, String expression) {
        return "schedule '" + expression + "' field " + (at.ordinal() + 1) + " (" + at.label
            + ") '" + part + "'";
    }

    /**
     * Does this schedule fire at the given wall-clock minute?
     *
     * @param dayOfWeek CRON's numbering, Sunday 0 — see {@link #cronDayOfWeek}
     */
    public boolean matches(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
        return this.minute.matches(minute)
            && this.hour.matches(hour)
            && this.dayOfMonth.matches(dayOfMonth)
            && this.month.matches(month)
            && this.dayOfWeek.matches(dayOfWeek);
    }

    /** Does this schedule fire at the given moment? */
    public boolean matches(LocalDateTime at) {
        Objects.requireNonNull(at, "a schedule cannot be asked about no moment");
        return matches(at.getMinute(), at.getHour(), at.getDayOfMonth(), at.getMonthValue(),
            cronDayOfWeek(at.getDayOfWeek()));
    }

    /**
     * Java's day-of-week in cron's numbering: Sunday 0 through Saturday 6.
     *
     * <p>The ONE place the two numberings meet. {@link DayOfWeek#getValue} answers 1 for
     * Monday through 7 for Sunday, so Sunday is the only day whose number changes.</p>
     */
    public static int cronDayOfWeek(DayOfWeek day) {
        Objects.requireNonNull(day, "there is no day to convert");
        return day == DayOfWeek.SUNDAY ? 0 : day.getValue();
    }
}
