package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28g S9a M5b — the cron itself. Pure, so every case is a value in and a value
 * out; the firing lives in {@link SeatSchedulerFiresUnattendedTest}.
 *
 * <p>The cases that matter are the REFUSALS, because the defect this step exists to not
 * carry is a schedule that cannot fire and does not say so.</p>
 */
class SeatScheduleTest {

    @Test
    @DisplayName("the three forms read: *, a number, and */n")
    void theThreeFormsRead() {
        SeatSchedule every = SeatSchedule.parse("* * * * *");
        SeatSchedule sixAm = SeatSchedule.parse("0 6 * * *");
        SeatSchedule quarterly = SeatSchedule.parse("*/15 * * * *");

        assertAll(
            () -> assertTrue(every.matches(37, 13, 9, 4, 3), "* admits anything"),
            () -> assertTrue(sixAm.matches(0, 6, 1, 1, 0)),
            () -> assertFalse(sixAm.matches(31, 6, 1, 1, 0), "6:31 is not 6:00"),
            () -> assertFalse(sixAm.matches(0, 7, 1, 1, 0), "7:00 is not 6:00"),
            () -> assertTrue(quarterly.matches(0, 1, 1, 1, 1)),
            () -> assertTrue(quarterly.matches(45, 1, 1, 1, 1)),
            () -> assertFalse(quarterly.matches(46, 1, 1, 1, 1), "*/15 is not every minute"));
    }

    @Test
    @DisplayName("every field is read in its own position, so a schedule cannot fire on the wrong axis")
    void everyFieldIsReadInItsOwnPosition() {
        // Five separate schedules, each pinning ONE position, because a parser that read
        // the fields in the wrong order would satisfy any test that varied only one of
        // them — and the order IS the contract (m h dom mon dow).
        assertAll(
            () -> assertTrue(SeatSchedule.parse("5 * * * *").matches(5, 0, 1, 1, 0)),
            () -> assertFalse(SeatSchedule.parse("5 * * * *").matches(0, 5, 1, 1, 0),
                "field 1 is the MINUTE, not the hour"),
            () -> assertTrue(SeatSchedule.parse("* 5 * * *").matches(0, 5, 1, 1, 0)),
            () -> assertTrue(SeatSchedule.parse("* * 5 * *").matches(0, 0, 5, 1, 0)),
            () -> assertTrue(SeatSchedule.parse("* * * 5 *").matches(0, 0, 1, 5, 0)),
            () -> assertTrue(SeatSchedule.parse("* * * * 5").matches(0, 0, 1, 1, 5)),
            () -> assertFalse(SeatSchedule.parse("* * * * 5").matches(0, 0, 1, 5, 1),
                "field 4 is the MONTH and field 5 the weekday, not the other way round"));
    }

    @Test
    @DisplayName("the wrong number of fields is refused, and the message says how many it got")
    void theWrongNumberOfFieldsIsRefused() {
        assertAll(
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SeatSchedule.parse("* * * *")).getMessage().contains("got 4")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SeatSchedule.parse("* * * * * *")).getMessage().contains("got 6")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SeatSchedule.parse("   ")).getMessage().contains("got 0"),
                "a blank expression is zero fields, not one empty one"));
    }

    @Test
    @DisplayName("DIVERGENCE 1: a step of zero is REFUSED, where the reference accepts it and never fires")
    void aStepOfZeroIsRefused() {
        // The reference parses */0 happily and guards it at MATCH time with `n != 0`, so
        // the schedule exists, reads as valid, and can never fire. That is the same defect
        // as the swallowed parse error wearing a different hat.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SeatSchedule.parse("*/0 * * * *"));
        assertAll(
            () -> assertTrue(e.getMessage().contains("can never fire"), e.getMessage()),
            () -> assertTrue(e.getMessage().contains("*/0"),
                "and it quotes the field, so a reader can find it: " + e.getMessage()));
    }

    @Test
    @DisplayName("DIVERGENCE 2: a number outside its field's range is REFUSED, for the same reason")
    void anOutOfRangeNumberIsRefused() {
        // Minute 99 never comes round. The reference accepts it as a u32 and the schedule
        // silently never fires.
        assertAll(
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SeatSchedule.parse("99 * * * *")).getMessage().contains("minute is 0-59")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SeatSchedule.parse("* 24 * * *")).getMessage().contains("hour is 0-23")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SeatSchedule.parse("* * 0 * *")).getMessage().contains("day of month is 1-31"),
                "day-of-month starts at 1, so 0 is not a day"),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SeatSchedule.parse("* * * 13 *")).getMessage().contains("month is 1-12")),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SeatSchedule.parse("* * * * 7")).getMessage().contains("day of week is 0-6"),
                "this subset counts Sunday as 0 and has no second spelling for it"));
    }

    @Test
    @DisplayName("DIVERGENCE 3: a step larger than its field is refused — and this one the reference FIRES on")
    void aStepLargerThanItsFieldIsRefused() {
        // Unlike the other two divergences, the reference accepts this AND fires it, at
        // value 0, because v % 90 == 0 when v is 0. So */90 silently means "hourly",
        // which `0 * * * *` says plainly, and nobody writes the first meaning the second.
        IllegalArgumentException minutes = assertThrows(IllegalArgumentException.class,
            () -> SeatSchedule.parse("*/90 * * * *"));
        assertAll(
            () -> assertTrue(minutes.getMessage().contains("larger than the largest minute"),
                minutes.getMessage()),
            () -> assertTrue(minutes.getMessage().contains("fires only at minute 0"),
                "and it names what it WOULD have done, which is the part the author needs: "
                    + minutes.getMessage()),
            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SeatSchedule.parse("* * * * */7")).getMessage()
                .contains("larger than the largest day of week"),
                "the weekday field tops out at 6, so */7 is the same defect one field over"),
            // THE CONTROL on the bound itself: the refusal is `> high`, not `>= high`, so
            // a step EQUAL to the maximum is legal and fires at 0 as the author asked.
            () -> assertTrue(SeatSchedule.parse("*/59 * * * *").matches(0, 0, 1, 1, 0),
                "a step equal to the field's maximum is accepted"),
            () -> assertTrue(SeatSchedule.parse("* * * * */6").matches(0, 0, 1, 1, 6),
                "and so is one on the weekday field"));
    }

    @Test
    @DisplayName("the LEGAL edges of every range are accepted — the control for the refusals above")
    void theLegalEdgesAreAccepted() {
        // Without this, a parser that refused everything would pass every refusal case.
        assertAll(
            () -> assertTrue(SeatSchedule.parse("0 0 1 1 0").matches(0, 0, 1, 1, 0)),
            () -> assertTrue(SeatSchedule.parse("59 23 31 12 6").matches(59, 23, 31, 12, 6)));
    }

    @Test
    @DisplayName("a field that is not a number is refused, and the message says what it reads")
    void aNonNumericFieldIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SeatSchedule.parse("MON * * * *"));
        assertAll(
            () -> assertTrue(e.getMessage().contains("not a number"), e.getMessage()),
            () -> assertTrue(e.getMessage().contains("*, a number, or */n"),
                "and it says what this subset DOES read, so the reader can fix it"));
    }

    @Test
    @DisplayName("Sunday is 0 in cron and 7 in Java, and ONE method knows it")
    void sundayIsZeroInCron() {
        assertAll(
            () -> assertEquals(0, SeatSchedule.cronDayOfWeek(DayOfWeek.SUNDAY),
                "THE case: Java calls Sunday 7, cron calls it 0"),
            () -> assertEquals(1, SeatSchedule.cronDayOfWeek(DayOfWeek.MONDAY)),
            () -> assertEquals(6, SeatSchedule.cronDayOfWeek(DayOfWeek.SATURDAY),
                "and Saturday is the last day either way, so only Sunday moves"));
    }

    @Test
    @DisplayName("a moment is judged with the weekday already converted")
    void aMomentIsJudgedWithTheWeekdayConverted() {
        // 2026-10-04 is a SUNDAY. A schedule naming weekday 0 must fire on it, and one
        // naming 7 cannot even be written — which is what makes the conversion checkable
        // from the outside rather than only through cronDayOfWeek.
        LocalDateTime sunday = LocalDateTime.of(2026, 10, 4, 6, 0);
        assertAll(
            () -> assertTrue(SeatSchedule.parse("0 6 * * 0").matches(sunday),
                "weekday 0 is Sunday"),
            () -> assertFalse(SeatSchedule.parse("0 6 * * 1").matches(sunday),
                "and Monday is not"),
            () -> assertTrue(SeatSchedule.parse("0 6 4 10 *").matches(sunday),
                "the date fields read off the same moment"));
    }
}
