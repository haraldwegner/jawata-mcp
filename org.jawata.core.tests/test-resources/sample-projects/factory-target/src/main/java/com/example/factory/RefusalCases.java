package com.example.factory;

import java.util.function.Consumer;

/**
 * Sprint 28d S8.13 — the two SHAPE refusals of Replace Conditional with
 * Polymorphism, one method each.
 *
 * <p>These exist because the operation ADVERTISES both refusals, in its class
 * javadoc and in the tool description a client reads, and neither was exercised by
 * anything. A refusal nothing tests is a claim — and here it is a claim a client is
 * being told to rely on.</p>
 *
 * <p>Each method is otherwise a PERFECTLY GOOD candidate: an enum discriminator, an
 * arrow switch, two non-default arms and a default, no fall-through. That is
 * deliberate. If the fixture failed some other precondition, a refusal would prove
 * nothing about the shape it is supposed to be about — the operation would be
 * declining for a reason the test never named.</p>
 */
public class RefusalCases {

    enum Mode { FAST, SLOW, IDLE }

    private int total;

    /**
     * An arm ASSIGNS {@code step}, which is a PARAMETER of this method.
     *
     * <p>It could travel into the generated class as a parameter — it is read here
     * too. But Java passes it by value, so the write would land on a copy and be
     * lost: the refactoring would change behaviour while every file still compiled.
     * That is the worst kind of transformation and the reason this is a refusal
     * rather than a rewrite.</p>
     */
    public void accumulate(Mode mode, int step) {
        switch (mode) {
            case FAST -> {
                step = step * 2;
                this.total = this.total + step;
            }
            case SLOW -> {
                this.total = this.total + 1;
            }
            default -> {
                this.total = 0;
            }
        }
    }

    /**
     * An arm passes {@code this} to somebody.
     *
     * <p>Once the arm's body is a class of its own, {@code this} IS that class — so
     * the reference would silently come to mean the behaviour object instead of the
     * context. Here the receiving type is {@code Consumer<RefusalCases>}, so the
     * compiler would in fact catch it; the refusal exists for the cases where it
     * would NOT, such as a parameter typed {@code Object}.</p>
     */
    public void announce(Mode mode, Consumer<RefusalCases> sink) {
        switch (mode) {
            case FAST -> {
                sink.accept(this);
            }
            case SLOW -> {
                this.total = this.total + 1;
            }
            default -> {
                this.total = 0;
            }
        }
    }

    public int total() {
        return total;
    }
}
