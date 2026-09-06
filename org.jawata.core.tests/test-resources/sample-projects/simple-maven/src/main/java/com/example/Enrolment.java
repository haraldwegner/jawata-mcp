package com.example;

/**
 * Row 59's PERFORMING case — a class carrying a type code that IS read through an accessor.
 *
 * <p>Fowler's first step, self-encapsulation, has already happened here: {@link #grade()} is what
 * a generated subclass overrides. Without it a subclass would have nothing to override and the
 * whole change would be inert, which is why row 59 refuses that state by name instead of
 * performing the step silently.</p>
 *
 * <p><b>It is TOP-LEVEL on purpose.</b> Row 59 generates new files beside this one, and a nested
 * class's subclass would need an enclosing instance.</p>
 *
 * <p><b>And the fixture package is shared.</b> {@code simple-maven} is one project read by the
 * whole suite; five times this sprint a fixture written for one row moved a population another
 * row's test was counting. The names here are deliberately unusual for that reason.</p>
 */
public class Enrolment {

    public static final int GRADE_PROVISIONAL = 0;
    public static final int GRADE_CONFIRMED = 1;
    public static final int GRADE_SENIOR_TUTOR = 2;

    private final int grade;

    public Enrolment(int grade) {
        this.grade = grade;
    }

    /** The accessor a generated subclass overrides — Fowler's first step, already taken. */
    public int grade() {
        return grade;
    }

    public String describe() {
        return "enrolment at grade " + grade();
    }
}
