package com.example;

/**
 * Row 59 REFUSES this — it is FINAL, so it cannot be subclassed at all.
 *
 * <p>The refusal points at the sibling cure for the same smell,
 * {@code refactor_to_pattern kind=replace_type_code_with_class}, which needs no subclassing.</p>
 */
public final class Bursary {

    public static final int AWARD_FULL = 0;
    public static final int AWARD_PARTIAL = 1;

    private final int award;

    public Bursary(int award) {
        this.award = award;
    }

    public int award() {
        return award;
    }
}
