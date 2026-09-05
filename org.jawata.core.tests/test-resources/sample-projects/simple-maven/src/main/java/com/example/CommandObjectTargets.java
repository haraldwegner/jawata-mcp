package com.example;

/**
 * Fixtures for Fowler row 41, Replace Command with Function, as
 * {@code change_method_signature kind=replace_command_with_function}.
 *
 * <p>The shape is a class whose whole job is one method, built and run in the same expression. The
 * refusals are the ones the fork census made the priority: a method a supertype declares (27 of
 * its 33 candidates), a field the constructor computes, a field written afterwards, several
 * methods, and a use that HOLDS the command instead of running it.</p>
 *
 * <h2>The name of this file is a correction, and so is one class in it</h2>
 *
 * <p>These fixtures were first written into {@code CommandTargets.java}, which already existed —
 * a Sprint 19 fixture whose test addresses a {@code switch} <b>by line number</b>. Overwriting it
 * broke that test in a way nothing local could see. And a class here was first called
 * {@code Held}, which sorts before {@code HelloWorld} and so displaced it in
 * {@code SearchSymbolsRankingTest}'s "the project's own class ranks first" assertion.</p>
 *
 * <p>Both are the same hazard this sprint has now recorded four times: {@code simple-maven} is
 * SHARED and monotonically growing, so a fixture written for one row is an input to every test
 * that reads this project. Only the full suite can see it.</p>
 *
 * <p>These are nested classes on purpose — one file, so a reader can see the whole set, and the
 * lookup has to descend into member types.</p>
 */
public class CommandObjectTargets {

    /** The shape: two fields, one constructor that only assigns them, one method. */
    public static class Discount {

        private final double base;
        private final int rate;

        public Discount(double base, int rate) {
            this.base = base;
            this.rate = rate;
        }

        public double execute(int rounding) {
            return Math.round((base * (100 - rate) / 100.0) * rounding) / (double) rounding;
        }
    }

    /** REFUSED: a supertype declares it, so the caller chose this one by constructing it. */
    public static class Loud implements Speaker {

        private final String words;

        public Loud(String words) {
            this.words = words;
        }

        @Override
        public String say() {
            return words.toUpperCase(java.util.Locale.ROOT);
        }
    }

    /** The supertype that makes {@link Loud} a dispatch rather than a waste. */
    public interface Speaker {

        String say();
    }

    /** REFUSED: the constructor COMPUTES a field rather than taking it. */
    public static class Computed {

        private final double total;

        public Computed(double base, double tax) {
            this.total = base + tax;
        }

        public double execute() {
            return total;
        }
    }

    /** REFUSED: a field is written after construction, so the object carries state over time. */
    public static class Accumulating {

        private int count;

        public Accumulating(int count) {
            this.count = count;
        }

        public int execute() {
            count = count + 1;
            return count;
        }
    }

    /** REFUSED: two instance methods, so which one the function is would be a choice. */
    public static class TwoJobs {

        private final int value;

        public TwoJobs(int value) {
            this.value = value;
        }

        public int execute() {
            return value;
        }

        public int twice() {
            return value * 2;
        }
    }

    /** REFUSED at its use site: something RETAINS it, which is what a command is for. */
    public static class Retained {

        private final int value;

        public Retained(int value) {
            this.value = value;
        }

        public int execute() {
            return value;
        }
    }
}
