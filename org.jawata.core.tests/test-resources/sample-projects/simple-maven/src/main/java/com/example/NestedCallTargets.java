package com.example;

/**
 * Sprint 28g S3 / D7b — the shape where a signature change mis-targets the ENCLOSING call.
 *
 * The field report: changing {@code pipeline.SLMonitor}'s constructor, whose calls sat as the
 * SECOND ARGUMENT of a six-argument {@code new OrbEventProcessor(...)}, produced a diff that
 * replaced the WHOLE OUTER CALL at four test sites — five arguments of a constructor that was
 * not the target, gone. It was caught only because the change was staged rather than applied.
 * That subject no longer exists (SLMonitor was removed with the slot-encapsulation work), so
 * the shape lives here instead, which is where a regression test belongs anyway.
 *
 * The shape is: {@code new Outer(new Inner(..), ..)} — the target call is an ARGUMENT of
 * another constructor call, so the two nodes are nested and only the inner one is the target.
 * FOUR call sites, matching the report, because a defect that rewrote one would be an accident
 * and one that rewrites all four is the behaviour.
 *
 * DO NOT REORDER, and do not add a fifth site: a test counts these four and asserts each
 * outer call keeps its own arguments.
 */
public class NestedCallTargets {

    /** The TARGET of the signature change. */
    public static class Inner {
        private final int a;
        private final int b;

        public Inner(int a, int b) {
            this.a = a;
            this.b = b;
        }

        public int sum() {
            return a + b;
        }
    }

    /** NOT the target. Its calls merely ENCLOSE the target's. */
    public static class Outer {
        private final Inner inner;
        private final String label;
        private final int scale;

        public Outer(Inner inner, String label, int scale) {
            this.inner = inner;
            this.label = label;
            this.scale = scale;
        }

        public int value() {
            return inner.sum() * scale;
        }

        public String label() {
            return label;
        }
    }

    public static Outer siteOne() {
        return new Outer(new Inner(1, 2), "siteOneLabel", 11);
    }

    public static Outer siteTwo() {
        return new Outer(new Inner(3, 4), "siteTwoLabel", 22);
    }

    public static Outer siteThree() {
        return new Outer(new Inner(5, 6), "siteThreeLabel", 33);
    }

    public static Outer siteFour() {
        return new Outer(new Inner(7, 8), "siteFourLabel", 44);
    }
}
