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

    /**
     * An arm RETURNS from the enclosing method.
     *
     * <p>Today that skips the statement after the switch. Once the arm is a method on
     * another class, the {@code return} leaves the generated {@code apply} and the
     * statement after the dispatch site RUNS. The file compiles either way, so the
     * strict compile gate cannot see it — which is what makes this a refusal rather
     * than something left to a later check.</p>
     */
    public void shortCircuit(Mode mode) {
        switch (mode) {
            case FAST -> {
                this.total = 0;
                return;
            }
            case SLOW -> {
                this.total = this.total + 1;
            }
            default -> {
                this.total = -1;
            }
        }
        this.total = this.total * 2;
    }

    /**
     * An arm breaks a LABEL declared outside itself.
     *
     * <p>{@code break outer} leaves the switch AND the loop. A trailing-break strip
     * that does not check for a label deletes it outright: the arm moves without it,
     * everything compiles, and the loop no longer stops. That is a silent behaviour
     * change with no compiler complaint anywhere.</p>
     */
    public void scan(Mode mode, int[] values) {
        outer:
        for (int v : values) {
            switch (mode) {
                case FAST -> {
                    this.total = this.total + v;
                    break outer;
                }
                case SLOW -> {
                    this.total = this.total + 1;
                }
                default -> {
                    this.total = 0;
                }
            }
        }
    }

    /**
     * THE CONTROL'S TARGET, and it must live in THIS FILE.
     *
     * <p>The two methods above are refused. Without an accepted case beside them, a
     * tool that refused everything in this file — a parse failure, an unreadable
     * enum, anything — would pass both refusal tests while proving nothing about the
     * shapes they name.</p>
     *
     * <p>A control in a DIFFERENT file does not close that gap: it rules out "refuses
     * everything in the project" and leaves "refuses everything in this file"
     * standing, which is the narrower and more likely confound.</p>
     *
     * <p>Every arm here touches fields only — no method-scope assignment, no bare
     * {@code this}, no control transfer — so it is the same shape as the two above in
     * every respect the operation examines except the one under test.</p>
     */
    public void tally(Mode mode) {
        switch (mode) {
            case FAST -> {
                this.total = this.total + 3;
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
