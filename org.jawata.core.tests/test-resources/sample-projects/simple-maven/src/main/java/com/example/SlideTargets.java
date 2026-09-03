package com.example;

/**
 * Fixture for Slide Statements (Sprint 28d-rescue, row 62).
 *
 * <p>One declaration must slide down and three must stay. Each refusal is a case where
 * something between the declaration and its use could change what the initializer
 * computes, so moving it would change the value.</p>
 */
public class SlideTargets {

    private int scale = 2;

    /** SLIDES: the initializer reads only parameters, and nothing in between touches them. */
    public int slidesDown(int width, int height) {
        int area = width * height;
        int label = width + 1;
        int spacing = height + 2;
        return area + label + spacing;
    }

    /**
     * NOT slid: a statement in between reassigns one of the locals the initializer
     * reads, so moving the declaration would compute a different area.
     */
    public int reassignedInBetween(int width, int height) {
        int area = width * height;
        width = width * 3;
        int spacing = height + 2;
        return area + width + spacing;
    }

    /**
     * NOT slid: the initializer reads a FIELD, and a call in between could change it.
     * The rule refuses on the field alone rather than trying to prove the call harmless.
     */
    public int readsAField(int width) {
        int scaled = width * scale;
        bumpScale();
        int other = width + 1;
        return scaled + other;
    }

    /**
     * NOT slid: the initializer CALLS something, so sliding it past other statements
     * moves when the call happens.
     */
    public int callsSomething(int width) {
        int computed = compute(width);
        int other = width + 1;
        int third = width + 2;
        return computed + other + third;
    }

    private void bumpScale() {
        scale++;
    }

    private int compute(int value) {
        scale++;
        return value * scale;
    }
}
