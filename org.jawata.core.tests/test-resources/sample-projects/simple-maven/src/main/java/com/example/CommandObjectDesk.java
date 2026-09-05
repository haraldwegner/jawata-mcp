package com.example;

/**
 * Uses of {@link CommandObjectTargets}' commands in ANOTHER FILE — built and run in one expression,
 * except the one that is deliberately held.
 */
public class CommandObjectDesk {

    /** Builds and runs in one expression, which is the shape this row can rewrite. */
    public double discounted(double base) {
        return new CommandObjectTargets.Discount(base, 10).execute(100);
    }

    /** A SECOND use, because a rewrite that reached one is not evidence it reaches all. */
    public double alsoDiscounted(double base) {
        return new CommandObjectTargets.Discount(base, 25).execute(10);
    }

    /** Chooses an implementation by constructing it — the dispatch the refusal protects. */
    public String loudly(String words) {
        CommandObjectTargets.Speaker speaker = new CommandObjectTargets.Loud(words);
        return speaker.say();
    }

    /** Runs the computed-field case, so it has a use and refuses for the constructor instead. */
    public double computed(double base, double tax) {
        return new CommandObjectTargets.Computed(base, tax).execute();
    }

    /** Runs the accumulating case, so it refuses for the field written afterwards. */
    public int accumulated(int start) {
        return new CommandObjectTargets.Accumulating(start).execute();
    }

    /** Runs the two-method case, so it refuses for having two jobs. */
    public int twoJobs(int value) {
        return new CommandObjectTargets.TwoJobs(value).execute();
    }

    /** RETAINS the command and runs it later, which is exactly what a command is for. */
    public int retained(int value) {
        CommandObjectTargets.Retained command = new CommandObjectTargets.Retained(value);
        return command.execute();
    }
}
