package com.example;

/**
 * Sprint 28g S3 / D7e — a MEMBER type asked to move to its OWN package is not
 * "nothing to move".
 *
 * The field report: pointing {@code move kind=class} at a nested type while naming its
 * own package answered {@code INVALID_PARAMETER: "Target package equals source package
 * in the source project; nothing to move"}. That sentence is true of a TOP-LEVEL type
 * and false of this one — what a caller means there is Fowler's Move Inner To Top
 * Level, and the member type becoming its own compilation unit in that same package is
 * a real change rather than a no-op.
 *
 * Two shapes, and the difference between them IS the fixture:
 *
 *   - {@code Standalone} is STATIC and reads nothing of its enclosing class, so it can
 *     stand on its own the moment it is lifted out. No enclosing instance is involved.
 *   - {@code NestMate} is a non-static INNER class that reads its outer's private
 *     field, so a promoted top-level version cannot compile without carrying a
 *     reference to the instance it was born from. That is the half a caller has to be
 *     TOLD about before they apply anything, which is why the response reports it.
 *
 * DO NOT make NestMate static and do not stop it reading {@code outerCount} — the
 * dependence on the enclosing instance is the fixture.
 */
public class NestedPromotionTargets {

    /** Read by {@link NestMate}, which is what makes the nest-mate case real. */
    private final int outerCount;

    /**
     * Builds an enclosing instance.
     *
     * @param outerCount the count its nest mates read
     */
    public NestedPromotionTargets(int outerCount) {
        this.outerCount = outerCount;
    }

    /**
     * The count this instance was built with.
     *
     * @return the count
     */
    public int outerCount() {
        return outerCount;
    }

    /** A standalone collaborator: lifting it out needs nothing from its enclosing class. */
    public static class Standalone {

        private final String label;

        /**
         * Builds a standalone collaborator.
         *
         * @param label the label it carries
         */
        public Standalone(String label) {
            this.label = label;
        }

        /**
         * The label this collaborator was built with.
         *
         * @return the label
         */
        public String label() {
            return label;
        }
    }

    /** A nest mate: it reads the enclosing instance's own field. */
    public class NestMate {

        /**
         * Twice the enclosing instance's count — the read that needs the outer.
         *
         * @return the doubled count
         */
        public int doubled() {
            return outerCount * 2;
        }
    }

    /**
     * Builds a standalone collaborator.
     *
     * @return a new collaborator
     */
    public Standalone standalone() {
        return new Standalone("initial");
    }

    /**
     * Builds a nest mate bound to this instance.
     *
     * @return a new nest mate
     */
    public NestMate nestMate() {
        return new NestMate();
    }
}
